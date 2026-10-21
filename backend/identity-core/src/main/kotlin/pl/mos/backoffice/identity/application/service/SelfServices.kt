package pl.mos.backoffice.identity.application.service

import pl.mos.backoffice.identity.application.port.`in`.CreatedToken
import pl.mos.backoffice.identity.application.port.`in`.GetMe
import pl.mos.backoffice.identity.application.port.`in`.GetLoginOptions
import pl.mos.backoffice.identity.application.port.`in`.GetRegistrationMode
import pl.mos.backoffice.identity.application.port.`in`.LoginOptions
import pl.mos.backoffice.identity.application.port.`in`.ManagePersonalTokens
import pl.mos.backoffice.identity.application.port.`in`.ManagePlatformSettings
import pl.mos.backoffice.identity.application.port.`in`.MeView
import pl.mos.backoffice.identity.application.port.`in`.PermissionCatalogQuery
import pl.mos.backoffice.identity.application.port.`in`.SearchAuditLog
import pl.mos.backoffice.identity.application.port.`in`.UpdateMyPreferences
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.AuditLogReader
import pl.mos.backoffice.identity.application.port.out.AuditQuery
import pl.mos.backoffice.identity.application.port.out.AuditRecordPage
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.PermissionCatalog
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.PlatformSettingsRepository
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.IdentityAudit
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserPreferences
import pl.mos.backoffice.identity.domain.service.TokenCodec
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.PublicOperation
import java.time.Duration

class MeService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val memberships: ProjectMembershipRepository,
    private val projectKeys: ProjectKeys,
    private val audit: AuditPort,
    private val gate: AuthorizationGate,
) : GetMe, UpdateMyPreferences {

    @PublicOperation("every authenticated caller may read who they are")
    override fun me(): MeView {
        val current = gate.currentUser()
        val user = profileOf(current)
        val known = memberships.findForUser(user.id, user.groupIds).associate { it.projectId to it.projectKey }
        return MeView(
            user = user,
            current = current,
            groups = groups.findByIds(user.groupIds).sortedBy { it.name.lowercase() },
            projects = projectKeys.describe(current.projectPermissions, current.projectRoles, known),
        )
    }

    @PublicOperation("every user may change their own UI preferences")
    override fun update(preferences: UserPreferences): UserPreferences {
        val user = profileOf(gate.currentUser())
        if (user.preferences == preferences) return preferences
        users.save(user.copy(preferences = preferences))
        audit.record(
            AuditEntry(
                IdentityAudit.USER_PREFERENCES_UPDATE, IdentityAudit.ENTITY_USER, user.id.toString(),
                details = AuditableChanges.changed(
                    "theme" to (user.preferences.theme to preferences.theme),
                    "defaultProjectId" to (user.preferences.defaultProjectId to preferences.defaultProjectId),
                    "pageSize" to (user.preferences.pageSize to preferences.pageSize),
                ),
            ),
        )
        return preferences
    }

    private fun profileOf(current: CurrentUser): User {
        val id = current.userId ?: throw InvalidStateException("${current.kind} principals have no user profile")
        return users.findById(id) ?: throw NotFoundException("user", id)
    }
}

class PlatformSettingsService(
    private val repository: PlatformSettingsRepository,
    private val settings: SettingsProvider,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val gate: AuthorizationGate,
    private val options: IdentityOptions = IdentityOptions(),
) : ManagePlatformSettings, GetRegistrationMode, GetLoginOptions {

    override fun get(): PlatformSettings {
        gate.require(IdentityPermissions.SETTINGS_MANAGE)
        return settings.current()
    }

    override fun update(settings: PlatformSettings): PlatformSettings {
        gate.require(IdentityPermissions.SETTINGS_MANAGE)
        val before = this.settings.current()
        val after = settings.normalised()
        val changes = AuditableChanges.changed(
            "registration" to (before.registration to after.registration),
            "allowedEmailDomains" to (before.allowedEmailDomains to after.allowedEmailDomains),
            "syncGroupsFromIdentityProvider" to (before.syncGroupsFromIdentityProvider to after.syncGroupsFromIdentityProvider),
            "personalTokenMaxDays" to (before.personalTokenMaxDays to after.personalTokenMaxDays),
            "localLoginEnabled" to (before.localLoginEnabled to after.localLoginEnabled),
            "mfaRequiredForLocal" to (before.mfaRequiredForLocal to after.mfaRequiredForLocal),
            "specKitAllowed" to (before.specKitAllowed to after.specKitAllowed),
            "passwordPolicy" to (policyMap(before) to policyMap(after)),
            "lockout" to (mapOf("maxFailures" to before.lockout.maxFailures, "lockMinutes" to before.lockout.lockMinutes) to
                mapOf("maxFailures" to after.lockout.maxFailures, "lockMinutes" to after.lockout.lockMinutes)),
        )
        if (!after.localLoginEnabled && before.localLoginEnabled && !options.local.oidcConfigured) {
            throw InvalidStateException("local login is the only sign-in method of this installation (no identity provider is configured)")
        }
        repository.save(after)
        if (changes.isNotEmpty()) {
            audit.record(AuditEntry(IdentityAudit.SETTINGS_UPDATE, IdentityAudit.ENTITY_SETTINGS, "platform", details = changes))
            accessVersion.increment()
        }
        return after
    }

    @PublicOperation("the login page shows whether self-registration is open")
    override fun registrationMode(): RegistrationMode = settings.current().registration

    @PublicOperation("the login page shows which sign-in methods exist and the password policy")
    override fun loginOptions(): LoginOptions {
        val current = settings.current()
        return LoginOptions(options.local.enabled && current.localLoginEnabled, current.passwordPolicy)
    }

    private fun policyMap(s: PlatformSettings) = mapOf(
        "minLength" to s.passwordPolicy.minLength, "maxLength" to s.passwordPolicy.maxLength,
        "rejectCommon" to s.passwordPolicy.rejectCommon, "rejectContainingEmail" to s.passwordPolicy.rejectContainingEmail,
    )
}

class PersonalTokenService(
    private val tokens: PersonalTokenRepository,
    private val settings: SettingsProvider,
    private val catalog: CatalogSupport,
    private val audit: AuditPort,
    private val accessVersion: AccessVersion,
    private val clock: Clock,
    private val gate: AuthorizationGate,
) : ManagePersonalTokens {

    override fun list(): List<PersonalAccessToken> {
        val me = gate.require(IdentityPermissions.TOKENS_PERSONAL)
        val userId = me.userId ?: return emptyList()
        return tokens.findByUser(userId).sortedByDescending { it.createdAt }
    }

    override fun create(command: ManagePersonalTokens.CreateToken): CreatedToken {
        val me = gate.require(IdentityPermissions.TOKENS_PERSONAL)
        // a token must not mint tokens (it would outlive its own expiry); CI tokens have no owner
        if (me.kind != PrincipalKind.USER || me.userId == null) throw InsufficientPermissionException(IdentityPermissions.TOKENS_PERSONAL)
        val name = command.name.trim()
        if (name.isEmpty() || name.length > 100) throw ValidationException("name must have 1 to 100 characters", "name")
        val maxDays = minOf(settings.current().personalTokenMaxDays, PlatformSettings.MAX_TOKEN_DAYS)
        if (command.expiresInDays !in 1..maxDays) throw ValidationException("expiresInDays must be between 1 and $maxDays", "expiresInDays")
        catalog.validator().validateKeys(command.permissions, "permissions")
        val held = me.platformPermissions + me.projectPermissions.values.flatten() + (if (me.allProjects) catalog.projectKeys() else emptyList())
        val notHeld = command.permissions - held
        if (notHeld.isNotEmpty()) throw ValidationException("a token cannot carry permissions you do not hold: ${notHeld.sorted()}", "permissions")

        val now = clock.now()
        val generated = TokenCodec.generate()
        val token = tokens.save(
            PersonalAccessToken(
                id = TokenId.new(),
                userId = me.userId!!,
                name = name,
                hash = generated.hash,
                prefix = generated.prefix,
                permissions = command.permissions,
                expiresAt = now.plus(Duration.ofDays(command.expiresInDays.toLong())),
                createdAt = now,
                allowMcpGateDecisions = command.allowMcpGateDecisions,
                allowMcpWorkflowEdits = command.allowMcpWorkflowEdits,
            ),
        )
        audit.record(
            AuditEntry(
                IdentityAudit.PAT_CREATE, IdentityAudit.ENTITY_TOKEN, token.id.toString(),
                details = mapOf(
                    "name" to name, "prefix" to token.prefix, "expiresAt" to token.expiresAt.toString(), "permissions" to command.permissions.sorted(),
                    "allowMcpGateDecisions" to token.allowMcpGateDecisions,
                    "allowMcpWorkflowEdits" to token.allowMcpWorkflowEdits,
                ),
            ),
        )
        accessVersion.increment()
        return CreatedToken(token, generated.plaintext)
    }

    @PublicOperation("users can always revoke their own tokens, even after losing tokens.personal")
    override fun revoke(id: TokenId) {
        val me = gate.currentUser()
        val token = tokens.findById(id)?.takeIf { it.userId == me.userId } ?: throw NotFoundException("personal token", id)
        if (token.revokedAt != null) return
        tokens.save(token.revoke(clock.now()))
        audit.record(AuditEntry(IdentityAudit.PAT_REVOKE, IdentityAudit.ENTITY_TOKEN, token.id.toString(), details = mapOf("name" to token.name, "prefix" to token.prefix)))
        accessVersion.increment()
    }
}

class PermissionCatalogService(private val catalog: PermissionCatalog) : PermissionCatalogQuery {
    @PublicOperation("the catalogue is code, not data; token and role pickers need it")
    override fun catalog(): List<PermissionDefinition> = catalog.definitions().sortedWith(compareBy({ it.module }, { it.key }))
}

class AuditSearchService(
    private val reader: AuditLogReader,
    private val audit: AuditPort,
    private val gate: AuthorizationGate,
) : SearchAuditLog {

    override fun search(query: AuditQuery): AuditRecordPage {
        gate.require(IdentityPermissions.AUDIT_VIEW)
        val bounded = query.copy(size = query.size.coerceIn(1, 200))
        val page = reader.search(bounded)
        val filter = mapOf(
            "from" to bounded.from?.toString(), "to" to bounded.to?.toString(), "actorId" to bounded.actorId?.toString(),
            "operation" to bounded.operation, "entityType" to bounded.entityType, "entityId" to bounded.entityId,
            "projectId" to bounded.projectId?.toString(), "result" to bounded.result?.name, "correlationId" to bounded.correlationId,
            "cursor" to bounded.cursor, "sort" to bounded.sort?.let { "${it.field}.${if (it.descending) "desc" else "asc"}" },
        ).filterValues { it != null }
        audit.record(AuditEntry(IdentityAudit.AUDIT_VIEW, IdentityAudit.ENTITY_AUDIT, null, details = mapOf("filter" to filter, "returned" to page.content.size)))
        return page
    }
}
