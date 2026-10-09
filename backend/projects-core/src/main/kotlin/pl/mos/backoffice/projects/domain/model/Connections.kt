package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.TaskExecutor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Identifier of a [Connection] (ADR 0030). */
@JvmInline
value class ConnectionId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ConnectionId(UUID.randomUUID())
        fun of(value: String) = ConnectionId(UUID.fromString(value))

        /** Fixed id of the legacy `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` credential. */
        val LEGACY_JIRA = ConnectionId(UUID.nameUUIDFromBytes("backoffice:legacy-jira-environment".toByteArray()))
    }
}

enum class ConnectionType { JIRA }

/**
 * Who owns a connection (ADR 0030, ADR 0035). `USER` is a personal connection: created by a user for themselves under
 * My account; used only with that person's action (their approval of a write, or a run they started) and never
 * readable by others (administrators see its metadata, never use it).
 */
enum class ConnectionScope { PLATFORM, PROJECT, USER }

/**
 * Kind of Atlassian API token (ADR 0035). `CLASSIC` carries every permission of its account and is sent to the site
 * URL; `SCOPED` ("API token with scopes") is limited to the scopes chosen when it was created and must be sent to the
 * Atlassian API gateway `https://api.atlassian.com/ex/jira/<cloudId>`.
 */
enum class JiraTokenKind { CLASSIC, SCOPED }

/**
 * Jira project permissions Back Office checks with `GET /rest/api/3/mypermissions` (ADR 0030). The first three are
 * the ones capabilities need; the rest are checked to report over-privileged accounts.
 */
enum class JiraPermission {
    BROWSE_PROJECTS, ADD_COMMENTS, TRANSITION_ISSUES, EDIT_ISSUES, DELETE_ISSUES, ASSIGN_ISSUES, CREATE_ISSUES, ADMINISTER_PROJECTS, MANAGE_WATCHERS;

    companion object {
        /** Every permission the verification asks Jira about, in the order of the query parameter. */
        val CHECKED: List<JiraPermission> = entries.toList()
    }
}

/**
 * What Back Office may do through a connection. Each capability maps to the Jira project permissions it needs;
 * [write] marks capabilities that change Jira (the basis for "approval before write"). New values can be added
 * (create issues, edit fields...) without changing the selection rule.
 */
enum class ConnectionCapability(val write: Boolean, val jiraPermissions: Set<JiraPermission>) {
    /** Read issues (Fetch issue, the intake watcher). */
    ISSUE_READ(write = false, jiraPermissions = setOf(JiraPermission.BROWSE_PROJECTS)),

    /** Comment on issues (Notify Jira). */
    ISSUE_COMMENT(write = true, jiraPermissions = setOf(JiraPermission.BROWSE_PROJECTS, JiraPermission.ADD_COMMENTS)),

    /** Move issues through their workflow (Transition issue). */
    ISSUE_TRANSITION(write = true, jiraPermissions = setOf(JiraPermission.BROWSE_PROJECTS, JiraPermission.TRANSITION_ISSUES)),
    ;

    companion object {
        /** Jira permissions needed by [capabilities] together. */
        fun jiraPermissionsFor(capabilities: Collection<ConnectionCapability>): Set<JiraPermission> =
            capabilities.flatMapTo(sortedSetOf()) { it.jiraPermissions }

        /**
         * Capabilities a task executor needs from its connection, or an empty set for executors that use none.
         * Transition lists the issue's transitions before it moves the issue, so it also reads.
         */
        fun requiredBy(executor: TaskExecutor): Set<ConnectionCapability> = when (executor) {
            TaskExecutor.FETCH_ISSUE -> setOf(ISSUE_READ)
            TaskExecutor.NOTIFY -> setOf(ISSUE_COMMENT)
            TaskExecutor.TRANSITION_ISSUE -> setOf(ISSUE_TRANSITION, ISSUE_READ)
            TaskExecutor.LLM, TaskExecutor.SPEC_KIT, TaskExecutor.IMPLEMENT -> emptySet()
        }
    }
}

/**
 * A secret sealed with envelope encryption (ADR 0030): [ciphertext] is the secret encrypted with a random data key,
 * [wrappedKey] is that data key encrypted with the key-encryption key [keyId]. Both are base64 and contain their
 * nonces. Core code can store and compare it but never decrypt it.
 */
data class SealedSecret(val keyId: String, val wrappedKey: String, val ciphertext: String) {
    override fun toString(): String = "SealedSecret(keyId=$keyId)"
}

enum class VerificationStatus { OK, WARNING, FAILED }

enum class MessageSeverity { ERROR, WARNING, INFO }

enum class VerificationCode {
    AUTHENTICATION_FAILED, SITE_UNREACHABLE, HTTP_ERROR, PROJECT_NOT_FOUND, NO_PROJECT_TO_CHECK, MISSING_PERMISSIONS, EXCESS_PERMISSIONS,
    SECRET_UNAVAILABLE, VERIFIED,

    /** ADR 0035 (scoped tokens): the declared scopes or an endpoint call show that a capability's scopes are missing. */
    MISSING_SCOPES,

    /** ADR 0035: the token declares write scopes no capability of the connection needs. */
    EXCESS_SCOPES,

    /** ADR 0035: the token declares a broad classic write scope (`write:jira-work`) or the account permissions are broad. */
    BROAD_SCOPE,
}

data class VerificationMessage(
    val severity: MessageSeverity,
    val code: VerificationCode,
    val text: String,
    val projectKey: String? = null,
    val permissions: List<JiraPermission> = emptyList(),
)

data class VerifiedAccount(val accountId: String, val displayName: String, val emailAddress: String?)

data class ProjectPermissionCheck(
    val projectKey: String,
    val granted: Set<JiraPermission>,
    val missing: Set<JiraPermission>,
    val excess: Set<JiraPermission>,
)

/** Result of checking a connection against Jira (`myself` + `mypermissions`), stored on the connection. */
data class ConnectionVerification(
    val status: VerificationStatus,
    val checkedAt: Instant,
    val account: VerifiedAccount?,
    val projects: List<ProjectPermissionCheck>,
    val messages: List<VerificationMessage>,
) {
    val missing: Set<JiraPermission> get() = projects.flatMapTo(sortedSetOf()) { it.missing }
    val excess: Set<JiraPermission> get() = projects.flatMapTo(sortedSetOf()) { it.excess }
}

enum class ExpiryStatus { NONE, OK, EXPIRING_SOON, EXPIRED }

/**
 * Aggregate root (ADR 0030): a least-privilege credential for an external system, owned by the platform or by one
 * project. The secret is only ever held sealed ([SealedSecret]); it is never returned or logged. [legacy] marks the
 * read-only connection built from the `JIRA_*` environment variables (not persisted, every capability).
 */
data class Connection(
    val id: ConnectionId,
    val scope: ConnectionScope,
    val projectId: ProjectId?,
    val type: ConnectionType,
    val name: String,
    val siteUrl: String,
    val accountEmail: String,
    val capabilities: Set<ConnectionCapability>,
    val allowedProjectKeys: List<String> = emptyList(),
    val verifyProjectKey: String? = null,
    val secret: SealedSecret?,
    val secretRotatedAt: Instant? = null,
    val secretExpiresAt: LocalDate? = null,
    val createdAt: Instant? = null,
    val createdBy: Actor? = null,
    val updatedAt: Instant? = null,
    val updatedBy: Actor? = null,
    val verification: ConnectionVerification? = null,
    val legacy: Boolean = false,
    /** The expiry date the last reminder was sent for (ADR 0030 gaps): one reminder per expiry date. */
    val expiryRemindedFor: LocalDate? = null,
    /** Owner of a personal connection ([ConnectionScope.USER]); null for platform and project connections. */
    val ownerUserId: UUID? = null,
    /** Display name of [ownerUserId] when the connection was created (audit, administrators' list). */
    val ownerName: String? = null,
    /** ADR 0035: classic token (site URL) or scoped token (Atlassian API gateway with [cloudId]). */
    val tokenKind: JiraTokenKind = JiraTokenKind.CLASSIC,
    /** Jira Cloud site id (`/_edge/tenant_info`), required for [JiraTokenKind.SCOPED]. */
    val cloudId: String? = null,
    /** Scopes declared for a scoped token (what was ticked at id.atlassian.com); empty for classic tokens. */
    val tokenScopes: List<String> = emptyList(),
    /**
     * A person confirmed that a classic token can do everything its account can and that Back Office only limits each step
     * to its capability (ADR 0035). Required to save a classic token.
     */
    val classicTokenAcknowledged: Boolean = false,
) {
    init {
        if (name.isBlank()) throw ValidationException(MUST_NOT_BE_BLANK, "name")
        if (name.length > MAX_NAME) throw ValidationException("must be at most $MAX_NAME characters", "name")
        if (!legacy) {
            if (!SITE_URL.matches(siteUrl)) throw ValidationException("must be an https URL such as https://acme.atlassian.net", "siteUrl")
            if (!EMAIL.matches(accountEmail)) throw ValidationException("must be an e-mail address", "accountEmail")
        }
        if (capabilities.isEmpty()) throw ValidationException("select at least one capability", "capabilities")
        allowedProjectKeys.firstOrNull { !JIRA_KEY.matches(it) }?.let {
            throw ValidationException("$it is not a Jira project key (${JIRA_KEY.pattern})", "allowedProjectKeys")
        }
        if (verifyProjectKey != null && !JIRA_KEY.matches(verifyProjectKey)) {
            throw ValidationException("$verifyProjectKey is not a Jira project key (${JIRA_KEY.pattern})", "verifyProjectKey")
        }
        if ((scope == ConnectionScope.PROJECT) != (projectId != null)) {
            throw ValidationException("project connections need a project, platform connections must not have one", "scope")
        }
        if ((scope == ConnectionScope.USER) != (ownerUserId != null)) {
            throw ValidationException("personal connections need an owner, other connections must not have one", "scope")
        }
        when (tokenKind) {
            JiraTokenKind.SCOPED -> {
                if (cloudId.isNullOrBlank() || !CLOUD_ID.matches(cloudId)) {
                    throw ValidationException("a scoped token needs the Jira cloud id of the site (a UUID, from <site>/_edge/tenant_info)", "cloudId")
                }
                if (tokenScopes.isEmpty()) throw ValidationException("list the scopes chosen when the scoped token was created", "tokenScopes")
                tokenScopes.firstOrNull { !SCOPE.matches(it) }?.let { throw ValidationException("$it is not an Atlassian scope (${SCOPE.pattern})", "tokenScopes") }
            }
            JiraTokenKind.CLASSIC -> if (tokenScopes.isNotEmpty()) throw ValidationException("classic tokens have no scopes", "tokenScopes")
        }
    }

    /** A personal connection (ADR 0035): only usable with its owner's action. */
    val personal: Boolean get() = scope == ConnectionScope.USER

    /** True when [userId] owns this personal connection. */
    fun ownedBy(userId: UUID?): Boolean = personal && userId != null && ownerUserId == userId

    /**
     * Base URL of REST calls (ADR 0035): the site for classic tokens, the Atlassian API gateway for scoped ones
     * (`<gateway>/ex/jira/<cloudId>`). Issue links always use [siteUrl].
     */
    fun apiBaseUrl(gatewayUrl: String): String = when (tokenKind) {
        JiraTokenKind.CLASSIC -> siteUrl.trimEnd('/')
        JiraTokenKind.SCOPED -> "${gatewayUrl.trimEnd('/')}/ex/jira/$cloudId"
    }

    /** True when the connection may touch issues of the Jira project [jiraProjectKey] (null = unknown, allowed). */
    fun allowsProject(jiraProjectKey: String?): Boolean =
        allowedProjectKeys.isEmpty() || jiraProjectKey == null || jiraProjectKey.uppercase() in allowedProjectKeys

    fun covers(required: Set<ConnectionCapability>): Boolean = capabilities.containsAll(required)

    /** Capabilities beyond [required] (what least privilege counts). */
    fun excess(required: Set<ConnectionCapability>): Set<ConnectionCapability> = capabilities - required

    /** Keys the verification checks: the allow-list, else the sample key. */
    fun keysToVerify(sample: String? = null): List<String> =
        allowedProjectKeys.ifEmpty { listOfNotNull(sample?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: verifyProjectKey) }

    fun expiryStatus(now: Instant): ExpiryStatus {
        val expires = secretExpiresAt ?: return ExpiryStatus.NONE
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        return when {
            expires.isBefore(today) -> ExpiryStatus.EXPIRED
            !expires.isAfter(today.plusDays(EXPIRY_WARNING_DAYS)) -> ExpiryStatus.EXPIRING_SOON
            else -> ExpiryStatus.OK
        }
    }

    /**
     * The daily reminder (ADR 0030): the token expires within [EXPIRY_REMINDER_DAYS] days (or has expired) and no
     * reminder was sent for this expiry date yet.
     */
    fun needsExpiryReminder(now: Instant): Boolean {
        val expires = secretExpiresAt ?: return false
        if (expiryRemindedFor == expires) return false
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        return !expires.isAfter(today.plusDays(EXPIRY_REMINDER_DAYS))
    }

    /** Days until the token expires (negative: expired), or null without an expiry date. */
    fun daysUntilExpiry(now: Instant): Long? =
        secretExpiresAt?.let { java.time.temporal.ChronoUnit.DAYS.between(now.atZone(ZoneOffset.UTC).toLocalDate(), it) }

    fun update(
        name: String,
        siteUrl: String,
        accountEmail: String,
        capabilities: Set<ConnectionCapability>,
        allowedProjectKeys: List<String>,
        verifyProjectKey: String?,
        secretExpiresAt: LocalDate?,
        actor: Actor,
        now: Instant,
        tokenKind: JiraTokenKind = this.tokenKind,
        cloudId: String? = this.cloudId,
        tokenScopes: List<String> = this.tokenScopes,
        classicTokenAcknowledged: Boolean = this.classicTokenAcknowledged,
    ): Connection = copy(
        name = name.trim(),
        siteUrl = normalizeSiteUrl(siteUrl),
        accountEmail = accountEmail.trim(),
        capabilities = capabilities,
        allowedProjectKeys = normalizeKeys(allowedProjectKeys),
        verifyProjectKey = verifyProjectKey?.trim()?.uppercase()?.ifEmpty { null },
        secretExpiresAt = secretExpiresAt,
        updatedAt = now,
        updatedBy = actor,
        tokenKind = tokenKind,
        cloudId = if (tokenKind == JiraTokenKind.SCOPED) cloudId else null,
        tokenScopes = if (tokenKind == JiraTokenKind.SCOPED) normalizeScopes(tokenScopes) else emptyList(),
        classicTokenAcknowledged = classicTokenAcknowledged,
        // what was verified no longer describes the connection when the account, site or token kind changed
        verification = verification.takeIf {
            this.siteUrl == normalizeSiteUrl(siteUrl) && this.accountEmail == accountEmail.trim() && this.tokenKind == tokenKind
        },
    )

    fun rotate(secret: SealedSecret, expiresAt: LocalDate?, actor: Actor, now: Instant): Connection =
        copy(secret = secret, secretRotatedAt = now, secretExpiresAt = expiresAt, updatedAt = now, updatedBy = actor, verification = null)

    fun verified(result: ConnectionVerification): Connection = copy(verification = result)

    override fun toString(): String = "Connection(id=$id, scope=$scope, name=$name, capabilities=$capabilities, tokenKind=$tokenKind, legacy=$legacy)"

    companion object {
        /** Jira Cloud ids are UUIDs. */
        val CLOUD_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Atlassian OAuth scope names: `read:jira-work`, `write:comment:jira`, `read:issue.transition:jira`. */
        val SCOPE = Regex("^[a-z]+:[a-z0-9._-]+(:[a-z0-9._-]+)?$")

        fun normalizeScopes(scopes: List<String>): List<String> = scopes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()

        const val MAX_NAME = 80
        const val EXPIRY_WARNING_DAYS = 30L

        /** Reminders (turn card, desktop notification) start this many days before the token expires. */
        const val EXPIRY_REMINDER_DAYS = 14L
        private const val MUST_NOT_BE_BLANK = "must not be blank"
        /** https only; plain http is accepted for localhost (local development, tests). */
        val SITE_URL = Regex("^(https://[A-Za-z0-9.-]+|http://(localhost|127\\.0\\.0\\.1))(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$")
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val JIRA_KEY = Regex("^[A-Z][A-Z0-9_]{0,19}$")

        fun normalizeSiteUrl(url: String): String = url.trim().trimEnd('/')

        /** Associated data of the secret's encryption: a sealed secret only opens for the connection it was sealed for. */
        fun secretAssociatedData(id: ConnectionId): String = "connection:$id"

        fun normalizeKeys(keys: List<String>): List<String> = keys.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct().sorted()

        /** The Jira project key of an issue key (`SHOP-12` -> `SHOP`), or null when it is not an issue key. */
        fun jiraProjectOf(issueKey: String?): String? =
            issueKey?.trim()?.substringBefore('-', missingDelimiterValue = "")?.uppercase()?.takeIf { JIRA_KEY.matches(it) }
    }
}

/** A failed Jira call during verification ([VerificationCode.AUTHENTICATION_FAILED], SITE_UNREACHABLE, HTTP_ERROR, PROJECT_NOT_FOUND). */
data class ProbeFailure(val code: VerificationCode, val detail: String)

/** What `mypermissions` answered for one Jira project: the granted permissions, or why it could not be read. */
data class ProjectProbe(val projectKey: String, val granted: Set<JiraPermission>?, val failure: ProbeFailure? = null)

/**
 * ADR 0035: one read call a scoped token needs for a capability (a JQL search, the comments or the transitions of a
 * sample issue) and how Jira answered. `401` / `403` mean the token lacks the scopes of that endpoint. Write calls are
 * never made by a verification.
 */
data class ScopeProbe(val capability: ConnectionCapability, val endpoint: String, val status: Int?, val ok: Boolean, val detail: String? = null)

/** Raw answers of Jira to a verification (`myself`, then `mypermissions` per project); mapped by `ConnectionVerificationPolicy`. */
data class JiraProbe(
    val account: VerifiedAccount?,
    val failure: ProbeFailure?,
    val projects: List<ProjectProbe> = emptyList(),
    /** Endpoint checks of a scoped token (ADR 0035); empty for classic tokens. */
    val scopeProbes: List<ScopeProbe> = emptyList(),
)
