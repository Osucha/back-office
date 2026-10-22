package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Identifier of a [ScmConnection] (ADR 0051). */
@JvmInline
value class ScmConnectionId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ScmConnectionId(UUID.randomUUID())
        fun of(value: String) = ScmConnectionId(UUID.fromString(value))
    }
}

enum class ScmProvider { GITLAB }

/** GitLab access levels (Reporter, Developer, Maintainer, Owner). */
object GitLabAccess {
    const val REPORTER = 20
    const val DEVELOPER = 30
    const val MAINTAINER = 40
    fun name(level: Int?): String? = when {
        level == null -> null
        level >= 50 -> "Owner"
        level >= MAINTAINER -> "Maintainer"
        level >= DEVELOPER -> "Developer"
        level >= REPORTER -> "Reporter"
        else -> "Guest"
    }
}

/**
 * What a connection's token may be used for (ADR 0051). Read capabilities may reach an agent as a tool; write
 * capabilities are used only by platform code (the runner's push, Back Office's write operations), never by agents.
 * [apiScope] is the GitLab token scope the capability needs on the REST API (null = checked over git);
 * [minAccess] the GitLab role it needs.
 */
enum class ScmCapability(val write: Boolean, val apiScope: String?, val minAccess: Int) {
    GIT_READ(false, null, GitLabAccess.REPORTER),
    CI_READ(false, "read_api", GitLabAccess.REPORTER),
    PUSH_BRANCH(true, null, GitLabAccess.DEVELOPER),
    MR_WRITE(true, "api", GitLabAccess.DEVELOPER),
    MR_COMMENT(true, "api", GitLabAccess.REPORTER),
    PIPELINE_RUN(true, "api", GitLabAccess.DEVELOPER),
}

/**
 * A token of one project repository (ADR 0051): several per repository are expected, one per permission level. The
 * token is stored only sealed; [verification] is the last test against the SCM.
 */
data class ScmConnection(
    val id: ScmConnectionId,
    val projectId: ProjectId,
    val repositoryKey: String,
    val name: String,
    val provider: ScmProvider,
    val capabilities: Set<ScmCapability>,
    val secret: SealedSecret,
    val secretExpiresAt: LocalDate?,
    val verification: ScmVerification?,
    val createdAt: Instant,
    val createdBy: Actor,
    val updatedAt: Instant,
    val updatedBy: Actor,
) {
    init {
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (name.length > 80) throw ValidationException("at most 80 characters", "name")
        if (capabilities.isEmpty()) throw ValidationException("choose at least one", "capabilities")
    }

    /** Declared read-only and verified to hold no write powers: the only kind an agent tool may receive. */
    val verifiedReadOnly: Boolean
        get() = capabilities.none { it.write } && verification != null &&
            verification.status != VerificationStatus.FAILED && verification.writePowers.isEmpty()

    fun expiryStatus(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): ExpiryStatus = when {
        secretExpiresAt == null -> ExpiryStatus.NONE
        !secretExpiresAt.isAfter(today) -> ExpiryStatus.EXPIRED
        !secretExpiresAt.isAfter(today.plusDays(Connection.EXPIRY_WARNING_DAYS)) -> ExpiryStatus.EXPIRING_SOON
        else -> ExpiryStatus.OK
    }

    companion object {
        /** Binds the sealed token to its connection. */
        fun secretAssociatedData(id: ScmConnectionId): String = "scm-connection:$id"
    }
}

/** What the SCM said about a token (raw facts of one test; null = could not be determined). */
data class ScmProbe(
    val failure: String? = null,
    val gitRead: Boolean? = null,
    val gitPush: Boolean? = null,
    /** Token scopes from the API; null when the token cannot call the API at all. */
    val scopes: List<String>? = null,
    val tokenName: String? = null,
    val tokenExpiresAt: LocalDate? = null,
    val accessLevel: Int? = null,
    val defaultBranch: String? = null,
    val defaultBranchProtected: Boolean? = null,
    /** Lowest access level allowed to merge into the protected default branch (0 = no one). */
    val mergeAccessLevel: Int? = null,
)

data class ScmMessage(val severity: MessageSeverity, val code: String, val text: String)

/** Result of testing a token against its declared capabilities (ADR 0051, ADR 0030 style). */
data class ScmVerification(
    val status: VerificationStatus,
    val checkedAt: Instant,
    val tokenName: String?,
    val scopes: List<String>?,
    val role: String?,
    val tokenExpiresAt: LocalDate?,
    val granted: Set<ScmCapability>,
    val missing: Set<ScmCapability>,
    /** Writes the token can do beyond what is declared, e.g. "git push", "api scope", "merge into main". */
    val writePowers: List<String>,
    val messages: List<ScmMessage>,
)

/** Turns a [ScmProbe] into a [ScmVerification] for the declared capabilities. */
object ScmVerificationPolicy {

    fun evaluate(declared: Set<ScmCapability>, probe: ScmProbe, now: Instant): ScmVerification {
        val messages = mutableListOf<ScmMessage>()
        fun msg(severity: MessageSeverity, code: String, text: String) = messages.add(ScmMessage(severity, code, text))
        if (probe.failure != null) {
            msg(MessageSeverity.ERROR, "UNREACHABLE", probe.failure)
            return ScmVerification(VerificationStatus.FAILED, now, probe.tokenName, probe.scopes, GitLabAccess.name(probe.accessLevel),
                probe.tokenExpiresAt, emptySet(), declared, emptyList(), messages)
        }
        val scopes = probe.scopes?.toSet()
        val level = probe.accessLevel
        fun hasScope(scope: String) = scopes != null && (scope in scopes || "api" in scopes)
        fun roleOk(c: ScmCapability) = level == null || level >= c.minAccess
        val granted = ScmCapability.entries.filter { c ->
            when (c) {
                ScmCapability.GIT_READ -> probe.gitRead == true
                ScmCapability.PUSH_BRANCH -> probe.gitPush == true
                else -> hasScope(c.apiScope!!) && roleOk(c)
            }
        }.toSet()
        val missing = declared - granted
        missing.forEach { c ->
            val why = when {
                c == ScmCapability.GIT_READ -> "the token cannot clone the repository (scope read_repository, role Reporter)"
                c == ScmCapability.PUSH_BRANCH -> "the token cannot push to the repository (scope write_repository, role Developer)"
                scopes == null -> "the token cannot call the API (it needs scope ${c.apiScope})"
                !hasScope(c.apiScope!!) -> "the token lacks scope ${c.apiScope}"
                else -> "the token's role ${GitLabAccess.name(level)} is below ${GitLabAccess.name(c.minAccess)}"
            }
            msg(MessageSeverity.ERROR, "MISSING_${c.name}", "$c: $why")
        }

        val powers = mutableListOf<String>()
        if (probe.gitPush == true && ScmCapability.PUSH_BRANCH !in declared) powers += "git push"
        if (scopes != null && "api" in scopes && declared.none { it.apiScope == "api" }) powers += "api scope"
        if (scopes != null && "write_repository" in scopes && ScmCapability.PUSH_BRANCH !in declared && probe.gitPush != true) {
            powers += "write_repository scope"
        }
        val branch = probe.defaultBranch ?: "the default branch"
        val canMerge = level != null && probe.mergeAccessLevel != null && probe.mergeAccessLevel > 0 && level >= probe.mergeAccessLevel
        if (canMerge || (probe.defaultBranchProtected == false && probe.gitPush == true)) powers += "merge into $branch"
        if (powers.isNotEmpty()) {
            msg(MessageSeverity.WARNING, "EXCESS", "The token can do more than declared: ${powers.joinToString()}. Use a token with fewer rights.")
        }
        if (probe.defaultBranchProtected == false) {
            msg(MessageSeverity.WARNING, "UNPROTECTED_DEFAULT_BRANCH", "$branch is not protected: anyone who can push can change it directly.")
        }
        if (level != null && level >= GitLabAccess.MAINTAINER) {
            msg(MessageSeverity.WARNING, "ROLE_TOO_HIGH", "Role ${GitLabAccess.name(level)} is more than any capability needs (at most Developer).")
        }
        if (scopes == null && declared.none { it.apiScope != null }) {
            msg(MessageSeverity.INFO, "NO_API", "The token cannot call the API, so its role and expiry were not checked.")
        }
        val status = when {
            missing.isNotEmpty() -> VerificationStatus.FAILED
            messages.any { it.severity == MessageSeverity.WARNING } -> VerificationStatus.WARNING
            else -> VerificationStatus.OK
        }
        return ScmVerification(status, now, probe.tokenName, probe.scopes, GitLabAccess.name(level), probe.tokenExpiresAt,
            granted, missing, powers, messages)
    }
}

/** Picks the least-privileged connection of a repository that has the needed capabilities (ADR 0051 point 3). */
object ScmConnectionSelector {
    fun select(connections: List<ScmConnection>, repositoryKey: String, needed: Set<ScmCapability>, readOnly: Boolean = false): ScmConnection? =
        connections.asSequence()
            .filter { it.repositoryKey == repositoryKey && it.capabilities.containsAll(needed) }
            .filter { it.verification?.status != VerificationStatus.FAILED }
            .filter { !readOnly || it.verifiedReadOnly }
            .sortedWith(
                compareBy<ScmConnection> { it.capabilities.count { c -> c.write } }
                    .thenBy { it.capabilities.size }
                    .thenBy { it.verification?.writePowers?.size ?: 0 }
                    .thenBy { it.name.lowercase() },
            )
            .firstOrNull()
}
