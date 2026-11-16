package pl.mos.backoffice.identity.adapter.config

import com.osucha.portpermissions.AccessGuard
import com.osucha.portpermissions.PermissionCatalog
import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import pl.mos.backoffice.identity.adapter.`in`.web.CorrelationIdFilter
import pl.mos.backoffice.identity.adapter.out.persistence.AuditDetailsEncoder
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcAccessVersion
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcAuditLog
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcAuditLogReader
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcGrantRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcGroupRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcLocalCredentialRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcMfaRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcPasswordResetTokenRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcPersonalTokenRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcPlatformSettingsRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcProjectKeyLookup
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcProjectMembershipRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcRoleRepository
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcUsedTokenStore
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcUserRepository
import pl.mos.backoffice.identity.adapter.out.security.AesGcmSecretCipher
import pl.mos.backoffice.identity.adapter.out.security.CurrentUserCache
import pl.mos.backoffice.identity.adapter.out.security.HmacCodeHasher
import pl.mos.backoffice.identity.adapter.out.security.LocalKeyMaterial
import pl.mos.backoffice.identity.adapter.out.security.ResourceCommonPasswords
import pl.mos.backoffice.identity.adapter.out.security.SecurityContextAccessGuard
import pl.mos.backoffice.identity.adapter.out.security.SignedMfaChallenges
import pl.mos.backoffice.identity.adapter.out.security.SpringPasswordHasher
import pl.mos.backoffice.identity.adapter.out.security.SpringSessionTerminator
import pl.mos.backoffice.identity.application.port.`in`.AdminLocalAccounts
import pl.mos.backoffice.identity.application.port.`in`.ChangePassword
import pl.mos.backoffice.identity.application.port.`in`.CompleteMfaLogin
import pl.mos.backoffice.identity.application.port.`in`.EffectivePermissionsQuery
import pl.mos.backoffice.identity.application.port.`in`.GetLoginOptions
import pl.mos.backoffice.identity.application.port.`in`.GetMe
import pl.mos.backoffice.identity.application.port.`in`.GetMySecurity
import pl.mos.backoffice.identity.application.port.`in`.GetRegistrationMode
import pl.mos.backoffice.identity.application.port.`in`.LocalLogin
import pl.mos.backoffice.identity.application.port.`in`.LookupDirectory
import pl.mos.backoffice.identity.application.port.`in`.ManageGrants
import pl.mos.backoffice.identity.application.port.`in`.ManageGroups
import pl.mos.backoffice.identity.application.port.`in`.ManageMyMfa
import pl.mos.backoffice.identity.application.port.`in`.ManagePersonalTokens
import pl.mos.backoffice.identity.application.port.`in`.ManagePlatformSettings
import pl.mos.backoffice.identity.application.port.`in`.ManageProjectMembers
import pl.mos.backoffice.identity.application.port.`in`.ManageRoles
import pl.mos.backoffice.identity.application.port.`in`.ManageUserSessions
import pl.mos.backoffice.identity.application.port.`in`.ManageUsers
import pl.mos.backoffice.identity.application.port.`in`.PasswordResetLinks
import pl.mos.backoffice.identity.application.port.`in`.PermissionCatalogQuery
import pl.mos.backoffice.identity.application.port.`in`.RecordLogout
import pl.mos.backoffice.identity.application.port.`in`.SearchAuditLog
import pl.mos.backoffice.identity.application.port.`in`.UpdateMyPreferences
import pl.mos.backoffice.identity.application.port.`in`.system.BootstrapLocalAdmin
import pl.mos.backoffice.identity.application.port.`in`.system.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.`in`.system.RecordAuthEvent
import pl.mos.backoffice.identity.application.port.`in`.system.ResolveLogin
import pl.mos.backoffice.identity.application.port.`in`.system.SeedIdentity
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.AuditLogReader
import pl.mos.backoffice.identity.application.port.out.CodeHasher
import pl.mos.backoffice.identity.application.port.out.CommonPasswords
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.GroupRepository
import pl.mos.backoffice.identity.application.port.out.LocalCredentialRepository
import pl.mos.backoffice.identity.application.port.out.MfaChallenges
import pl.mos.backoffice.identity.application.port.out.MfaRepository
import pl.mos.backoffice.identity.application.port.out.PasswordHasher
import pl.mos.backoffice.identity.application.port.out.PasswordResetTokenRepository
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.PlatformSettingsRepository
import pl.mos.backoffice.identity.application.port.out.ProjectKeyLookup
import pl.mos.backoffice.identity.application.port.out.ProjectMembershipRepository
import pl.mos.backoffice.identity.application.port.out.RoleRepository
import pl.mos.backoffice.identity.application.port.out.SecretCipher
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.out.SessionTerminator
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.application.service.AccessResolver
import pl.mos.backoffice.identity.application.service.AuditSearchService
import pl.mos.backoffice.identity.application.service.BootstrapLocalAdminService
import pl.mos.backoffice.identity.application.service.CatalogSupport
import pl.mos.backoffice.identity.application.service.ChangePasswordService
import pl.mos.backoffice.identity.application.service.DirectoryService
import pl.mos.backoffice.identity.application.service.GrantAdminService
import pl.mos.backoffice.identity.application.service.GroupAdminService
import pl.mos.backoffice.identity.application.service.IdentityOptions
import pl.mos.backoffice.identity.application.service.LoadCurrentUserService
import pl.mos.backoffice.identity.application.service.LocalAccountAdminService
import pl.mos.backoffice.identity.application.service.LocalAccountSupport
import pl.mos.backoffice.identity.application.service.LocalAuthOptions
import pl.mos.backoffice.identity.application.service.LocalLoginService
import pl.mos.backoffice.identity.application.service.MeService
import pl.mos.backoffice.identity.application.service.MyMfaService
import pl.mos.backoffice.identity.application.service.MySecurityService
import pl.mos.backoffice.identity.application.service.PasswordResetService
import pl.mos.backoffice.identity.application.service.PermissionCatalogService
import pl.mos.backoffice.identity.application.service.PersonalTokenService
import pl.mos.backoffice.identity.application.service.PlatformAdmins
import pl.mos.backoffice.identity.application.service.PlatformSettingsService
import pl.mos.backoffice.identity.application.service.ProjectAccessDirectoryService
import pl.mos.backoffice.identity.application.service.ProjectKeys
import pl.mos.backoffice.identity.application.service.ProjectMembershipService
import pl.mos.backoffice.identity.application.service.RecordAuthEventService
import pl.mos.backoffice.identity.application.service.RecordLogoutService
import pl.mos.backoffice.identity.application.service.ResolveLoginService
import pl.mos.backoffice.identity.application.service.RoleAdminService
import pl.mos.backoffice.identity.application.service.SeedIdentityService
import pl.mos.backoffice.identity.application.service.SettingsProvider
import pl.mos.backoffice.identity.application.service.UserAdminService
import pl.mos.backoffice.identity.application.service.UserDetailsAssembler
import pl.mos.backoffice.identity.domain.model.IdentityPermissions
import pl.mos.backoffice.identity.domain.model.LockoutSettings
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.shared.security.CurrentPrincipal
import pl.mos.backoffice.shared.security.ModulePermissions
import pl.mos.backoffice.shared.security.PermissionModules
import pl.mos.backoffice.shared.security.ProjectAccessDirectory
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.security.UserId
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** The ports every identity use case depends on, bundled so the bean methods stay readable. */
class IdentityPorts(
    val users: UserRepository,
    val groups: GroupRepository,
    val roles: RoleRepository,
    val grants: GrantRepository,
    val memberships: ProjectMembershipRepository,
    val tokens: PersonalTokenRepository,
    val settingsRepository: PlatformSettingsRepository,
    val sessions: SessionTerminator,
    val accessVersion: AccessVersion,
    val auditReader: AuditLogReader,
    val catalog: PermissionModules,
    val projectKeyLookup: ProjectKeyLookup,
    val audit: AuditPort,
    val guard: AccessGuard,
    val principal: CurrentPrincipal,
    val clock: Clock,
    val options: IdentityOptions,
    val local: LocalAccountSupport,
) {
    val catalogSupport = CatalogSupport(catalog)
    val settings = SettingsProvider(settingsRepository, options)
    val projectKeys = ProjectKeys(memberships, projectKeyLookup)
    val admins = PlatformAdmins(users, groups, grants)
    val access = AccessResolver(groups, roles, grants, memberships, catalogSupport)

    fun userAdmin() = UserAdminService(users, groups, roles, grants, memberships, tokens, sessions, projectKeys, admins, audit, accessVersion, clock, guard, principal, local)
    fun grantAdmin() = GrantAdminService(grants, roles, users, groups, memberships, access, projectKeys, catalogSupport, admins, audit, accessVersion, guard)
    fun me() = MeService(users, groups, memberships, projectKeys, audit, principal)
    fun platformSettings() = PlatformSettingsService(settingsRepository, settings, audit, accessVersion, guard, options)
    fun localLogin() = LocalLoginService(local)
    fun passwordResets() = PasswordResetService(local, sessions, accessVersion, guard, principal)
    fun assembler() = UserDetailsAssembler(groups, grants, memberships, local)
    fun memberships() = ProjectMembershipService(memberships, users, groups, roles, projectKeys, audit, accessVersion, clock, guard, principal)
}

/**
 * Wires the identity context. Beans offered to every other context: `AccessGuard` and `CurrentPrincipal`
 * ([SecurityContextAccessGuard], ADR 0066), `AuditPort` ([JdbcAuditLog]), `ProjectAccessProvisioner`
 * ([ProjectMembershipService]) and the permission catalogue ([PermissionModules] over all
 * [ModulePermissions] beans, with the library `PermissionCatalog`). Every use case runs in a transaction (an interface proxy with a
 * `TransactionInterceptor`), so audit rows and access-version bumps commit or roll back with the change.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties::class)
class IdentityConfiguration {

    // ---------------------------------------------------------------- cross-context beans

    /** The shared `AccessGuard` and `CurrentPrincipal`; also used by the audit log and the 403 handler. */
    @Bean
    fun accessGuard(catalog: PermissionCatalog): SecurityContextAccessGuard = SecurityContextAccessGuard(catalog)

    @Bean
    fun auditDetailsEncoder(json: JsonMapper) = AuditDetailsEncoder(json)

    /** The shared `AuditPort`. */
    @Bean
    fun auditPort(jdbc: JdbcClient, encoder: AuditDetailsEncoder, clock: Clock, dataSource: javax.sql.DataSource, gate: SecurityContextAccessGuard): JdbcAuditLog =
        JdbcAuditLog(jdbc, encoder, clock, dataSource) { gate.actorOrNull() }

    @Bean
    fun identityPermissions(): ModulePermissions = IdentityPermissions.contribution

    /** Every module's [ModulePermissions]; a key declared twice fails startup. */
    @Bean
    fun permissionModules(contributions: ObjectProvider<ModulePermissions>): PermissionModules =
        PermissionModules(contributions.orderedStream().toList())

    /** The library catalogue (port-permissions) of all modules. */
    @Bean
    fun permissionCatalog(modules: PermissionModules): PermissionCatalog = modules.catalog

    @Bean
    fun projectAccessProvisioner(ports: IdentityPorts, tm: PlatformTransactionManager): ProjectAccessProvisioner =
        transactional(ports.memberships(), tm, ProjectAccessProvisioner::class.java)

    /** Who can act on a project, for the Projects context (gate assignees, ADR 0028). */
    @Bean
    fun projectAccessDirectory(ports: IdentityPorts, tm: PlatformTransactionManager): ProjectAccessDirectory =
        transactional(ProjectAccessDirectoryService(ports.memberships, ports.users, ports.access), tm, ProjectAccessDirectory::class.java)

    // ---------------------------------------------------------------- persistence

    @Bean fun identityUserRepository(jdbc: JdbcClient, json: JsonMapper): UserRepository = JdbcUserRepository(jdbc, json)
    @Bean fun identityGroupRepository(jdbc: JdbcClient): GroupRepository = JdbcGroupRepository(jdbc)
    @Bean fun identityRoleRepository(jdbc: JdbcClient): RoleRepository = JdbcRoleRepository(jdbc)
    @Bean fun identityGrantRepository(jdbc: JdbcClient): GrantRepository = JdbcGrantRepository(jdbc)
    @Bean fun identityMembershipRepository(jdbc: JdbcClient): ProjectMembershipRepository = JdbcProjectMembershipRepository(jdbc)
    @Bean fun identityTokenRepository(jdbc: JdbcClient): PersonalTokenRepository = JdbcPersonalTokenRepository(jdbc)
    @Bean fun identitySettingsRepository(jdbc: JdbcClient, clock: Clock, json: JsonMapper): PlatformSettingsRepository = JdbcPlatformSettingsRepository(jdbc, clock, json)
    @Bean fun identityLocalCredentialRepository(jdbc: JdbcClient): LocalCredentialRepository = JdbcLocalCredentialRepository(jdbc)
    @Bean fun identityMfaRepository(jdbc: JdbcClient): MfaRepository = JdbcMfaRepository(jdbc)
    @Bean fun identityPasswordResetTokenRepository(jdbc: JdbcClient): PasswordResetTokenRepository = JdbcPasswordResetTokenRepository(jdbc)
    @Bean
    fun identityAccessVersion(jdbc: JdbcClient, signals: ObjectProvider<ClusterSignals>): AccessVersion =
        JdbcAccessVersion(jdbc, signals = signals.getIfAvailable { ClusterSignals.local() })
    @Bean fun identityProjectKeyLookup(jdbc: JdbcClient): ProjectKeyLookup = JdbcProjectKeyLookup(jdbc)
    @Bean fun identityAuditLogReader(jdbc: JdbcClient, json: JsonMapper): AuditLogReader = JdbcAuditLogReader(jdbc, json)

    @Bean
    fun identitySessionTerminator(sessions: ObjectProvider<FindByIndexNameSessionRepository<out Session>>): SessionTerminator {
        val repository = sessions.getIfAvailable()
        return if (repository != null) SpringSessionTerminator(repository) else NoSessions
    }

    // ---------------------------------------------------------------- local accounts (ADR 0021)

    /** Fails startup with a clear message when local login is enabled and no secret key is configured. */
    @Bean
    fun identityLocalKeyMaterial(properties: IdentityProperties): LocalKeyMaterial = LocalKeyMaterial.from(properties.local.secretKey, required = properties.local.enabled)

    /** Argon2id behind a DelegatingPasswordEncoder (`{argon2}`); a bean so tests can spy on it. */
    @Bean
    fun identityPasswordEncoder(): PasswordEncoder = SpringPasswordHasher.encoder()

    @Bean fun identityPasswordHasher(encoder: PasswordEncoder): PasswordHasher = SpringPasswordHasher(encoder)
    @Bean fun identitySecretCipher(keys: LocalKeyMaterial): SecretCipher = AesGcmSecretCipher(keys)
    @Bean fun identityCodeHasher(keys: LocalKeyMaterial): CodeHasher = HmacCodeHasher(keys)
    @Bean fun identityCommonPasswords(): CommonPasswords = ResourceCommonPasswords()

    @Bean
    fun identityMfaChallenges(keys: LocalKeyMaterial, clock: Clock, properties: IdentityProperties, jdbc: JdbcClient): MfaChallenges =
        SignedMfaChallenges(keys, clock, properties.local.mfaTokenValidity, JdbcUsedTokenStore(jdbc))

    @Bean
    fun localAccountSupport(
        users: UserRepository, credentials: LocalCredentialRepository, mfa: MfaRepository, resets: PasswordResetTokenRepository,
        hasher: PasswordHasher, cipher: SecretCipher, codes: CodeHasher, challenges: MfaChallenges, common: CommonPasswords,
        settings: PlatformSettingsRepository, options: IdentityOptions, audit: AuditPort, clock: Clock,
    ) = LocalAccountSupport(users, credentials, mfa, resets, hasher, cipher, codes, challenges, common, SettingsProvider(settings, options), options, audit, clock)

    // ---------------------------------------------------------------- support

    @Bean
    fun identityOptions(properties: IdentityProperties) = IdentityOptions(
        bootstrapAdmins = properties.bootstrapAdmins.toSet(),
        defaultSettings = PlatformSettings(
            registration = properties.registration,
            allowedEmailDomains = properties.allowedEmailDomains,
            syncGroupsFromIdentityProvider = properties.syncGroups,
            personalTokenMaxDays = properties.personalTokenMaxDays,
            localLoginEnabled = properties.local.enabled,
            lockout = LockoutSettings(properties.local.lockout.maxFailures, properties.local.lockout.lockDuration.toMinutes().toInt().coerceAtLeast(1)),
        ).normalised(),
        local = LocalAuthOptions(
            enabled = properties.local.enabled,
            oidcConfigured = properties.oidc.enabled,
            resetLinkValidity = properties.local.resetLinkValidity,
            bootstrapAdminEmail = properties.local.bootstrapAdminEmail.ifBlank { null },
            bootstrapAdminPassword = properties.local.bootstrapAdminPassword.ifEmpty { null },
            totpIssuer = properties.local.totpIssuer,
        ),
    )

    @Bean
    fun identityPorts(
        users: UserRepository, groups: GroupRepository, roles: RoleRepository, grants: GrantRepository, memberships: ProjectMembershipRepository,
        tokens: PersonalTokenRepository, settings: PlatformSettingsRepository, sessions: SessionTerminator, version: AccessVersion,
        reader: AuditLogReader, catalog: PermissionModules, keys: ProjectKeyLookup, audit: AuditPort, guard: AccessGuard, principal: CurrentPrincipal, clock: Clock,
        options: IdentityOptions, local: LocalAccountSupport,
    ) = IdentityPorts(users, groups, roles, grants, memberships, tokens, settings, sessions, version, reader, catalog, keys, audit, guard, principal, clock, options, local)

    @Bean
    fun currentUserCache(loader: LoadCurrentUser, version: AccessVersion) = CurrentUserCache(loader, version)

    @Bean
    fun correlationIdFilter(): FilterRegistrationBean<CorrelationIdFilter> = FilterRegistrationBean(CorrelationIdFilter()).apply {
        order = Ordered.HIGHEST_PRECEDENCE
        setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC)
    }

    // ---------------------------------------------------------------- use cases (one transactional proxy per port)

    @Bean
    fun resolveLogin(p: IdentityPorts, tm: PlatformTransactionManager): ResolveLogin =
        transactional(ResolveLoginService(p.users, p.groups, p.grants, p.settings, p.options, p.audit, p.accessVersion, p.clock, p.guard), tm, ResolveLogin::class.java)

    @Bean
    fun loadCurrentUser(p: IdentityPorts, tm: PlatformTransactionManager): LoadCurrentUser =
        transactional(LoadCurrentUserService(p.users, p.tokens, p.access, p.clock, p.guard), tm, LoadCurrentUser::class.java)

    @Bean
    fun recordAuthEvent(p: IdentityPorts, tm: PlatformTransactionManager): RecordAuthEvent =
        transactional(RecordAuthEventService(p.audit, p.guard), tm, RecordAuthEvent::class.java)

    @Bean
    fun recordLogout(p: IdentityPorts, tm: PlatformTransactionManager): RecordLogout =
        transactional(RecordLogoutService(p.audit, p.principal), tm, RecordLogout::class.java)

    @Bean fun getMe(p: IdentityPorts, tm: PlatformTransactionManager): GetMe = transactional(p.me(), tm, GetMe::class.java)
    @Bean fun updateMyPreferences(p: IdentityPorts, tm: PlatformTransactionManager): UpdateMyPreferences = transactional(p.me(), tm, UpdateMyPreferences::class.java)
    @Bean fun manageUsers(p: IdentityPorts, tm: PlatformTransactionManager): ManageUsers = transactional(p.userAdmin(), tm, ManageUsers::class.java)
    @Bean fun manageUserSessions(p: IdentityPorts, tm: PlatformTransactionManager): ManageUserSessions = transactional(p.userAdmin(), tm, ManageUserSessions::class.java)

    @Bean
    fun manageGroups(p: IdentityPorts, tm: PlatformTransactionManager): ManageGroups = transactional(
        GroupAdminService(p.groups, p.users, p.roles, p.grants, p.memberships, p.admins, p.audit, p.accessVersion, p.clock, p.guard), tm, ManageGroups::class.java,
    )

    @Bean
    fun manageRoles(p: IdentityPorts, tm: PlatformTransactionManager): ManageRoles = transactional(
        RoleAdminService(p.roles, p.grants, p.memberships, p.catalogSupport, p.audit, p.accessVersion, p.guard), tm, ManageRoles::class.java,
    )

    @Bean fun manageGrants(p: IdentityPorts, tm: PlatformTransactionManager): ManageGrants = transactional(p.grantAdmin(), tm, ManageGrants::class.java)

    @Bean
    fun effectivePermissionsQuery(p: IdentityPorts, tm: PlatformTransactionManager): EffectivePermissionsQuery =
        transactional(p.grantAdmin(), tm, EffectivePermissionsQuery::class.java)

    @Bean
    fun managePlatformSettings(p: IdentityPorts, tm: PlatformTransactionManager): ManagePlatformSettings =
        transactional(p.platformSettings(), tm, ManagePlatformSettings::class.java)

    @Bean
    fun getRegistrationMode(p: IdentityPorts, tm: PlatformTransactionManager): GetRegistrationMode =
        transactional(p.platformSettings(), tm, GetRegistrationMode::class.java)

    @Bean
    fun manageProjectMembers(p: IdentityPorts, tm: PlatformTransactionManager): ManageProjectMembers =
        transactional(p.memberships(), tm, ManageProjectMembers::class.java)

    @Bean
    fun lookupDirectory(p: IdentityPorts, tm: PlatformTransactionManager): LookupDirectory =
        transactional(DirectoryService(p.users, p.groups, p.guard, p.principal), tm, LookupDirectory::class.java)

    @Bean
    fun managePersonalTokens(p: IdentityPorts, tm: PlatformTransactionManager): ManagePersonalTokens = transactional(
        PersonalTokenService(p.tokens, p.settings, p.catalogSupport, p.audit, p.accessVersion, p.clock, p.guard, p.principal), tm, ManagePersonalTokens::class.java,
    )

    @Bean fun permissionCatalogQuery(p: IdentityPorts): PermissionCatalogQuery = PermissionCatalogService(p.catalog)

    @Bean
    fun searchAuditLog(p: IdentityPorts, tm: PlatformTransactionManager): SearchAuditLog =
        transactional(AuditSearchService(p.auditReader, p.audit, p.guard), tm, SearchAuditLog::class.java)

    @Bean
    fun seedIdentity(p: IdentityPorts, tm: PlatformTransactionManager): SeedIdentity =
        transactional(SeedIdentityService(p.roles, p.grants, p.catalogSupport, p.audit, p.accessVersion, p.guard), tm, SeedIdentity::class.java)

    @Bean
    fun getLoginOptions(p: IdentityPorts, tm: PlatformTransactionManager): GetLoginOptions =
        transactional(p.platformSettings(), tm, GetLoginOptions::class.java)

    @Bean fun localLogin(p: IdentityPorts, tm: PlatformTransactionManager): LocalLogin = transactional(p.localLogin(), tm, LocalLogin::class.java)
    @Bean fun completeMfaLogin(p: IdentityPorts, tm: PlatformTransactionManager): CompleteMfaLogin = transactional(p.localLogin(), tm, CompleteMfaLogin::class.java)

    @Bean
    fun changePassword(p: IdentityPorts, tm: PlatformTransactionManager): ChangePassword =
        transactional(ChangePasswordService(p.local, p.sessions, p.principal), tm, ChangePassword::class.java)

    @Bean fun passwordResetLinks(p: IdentityPorts, tm: PlatformTransactionManager): PasswordResetLinks = transactional(p.passwordResets(), tm, PasswordResetLinks::class.java)
    @Bean fun manageMyMfa(p: IdentityPorts, tm: PlatformTransactionManager): ManageMyMfa = transactional(MyMfaService(p.local, p.principal), tm, ManageMyMfa::class.java)

    @Bean
    fun adminLocalAccounts(p: IdentityPorts, tm: PlatformTransactionManager): AdminLocalAccounts = transactional(
        LocalAccountAdminService(p.local, p.assembler(), p.admins, p.sessions, p.accessVersion, p.guard, p.principal), tm, AdminLocalAccounts::class.java,
    )

    @Bean
    fun getMySecurity(p: IdentityPorts, tm: PlatformTransactionManager): GetMySecurity =
        transactional(MySecurityService(p.local, p.auditReader, p.principal), tm, GetMySecurity::class.java)

    @Bean
    fun bootstrapLocalAdmin(p: IdentityPorts, tm: PlatformTransactionManager): BootstrapLocalAdmin =
        transactional(BootstrapLocalAdminService(p.local, p.grants, p.accessVersion, p.guard), tm, BootstrapLocalAdmin::class.java)

    @Bean
    fun identityStartup(seed: SeedIdentity, bootstrap: BootstrapLocalAdmin, guard: AccessGuard, lock: ObjectProvider<ClusterLock>) =
        IdentityStartup(seed, bootstrap, guard, lock.getIfAvailable { ClusterLock.LOCAL })

    private object NoSessions : SessionTerminator {
        override fun sessionsOf(userId: UserId) = emptyList<SessionSummary>()
        override fun terminateAll(userId: UserId) = 0
    }

    companion object {
        /**
         * A JDK proxy implementing only [port], whose every method runs in a REQUIRED transaction. Core services
         * carry no annotations, so the transaction boundary is declared here; one proxy per port keeps bean
         * lookups by type unambiguous when a service implements several ports.
         */
        fun <T : Any> transactional(target: T, tm: PlatformTransactionManager, port: Class<T>): T {
            val factory = ProxyFactory()
            factory.setTarget(target)
            factory.setInterfaces(port)
            factory.addAdvice(TransactionInterceptor(tm, MatchAlwaysTransactionAttributeSource()))
            return port.cast(factory.proxy)
        }
    }
}

/**
 * Seeds built-in roles and the SYSTEM default when the application is ready (idempotent), on one replica at a
 * time (ShedLock `identity-seed`): replicas starting together do not race on the same rows.
 */
class IdentityStartup(
    private val seed: SeedIdentity,
    private val bootstrap: BootstrapLocalAdmin,
    private val guard: AccessGuard,
    private val lock: ClusterLock = ClusterLock.LOCAL,
) {
    private val log = LoggerFactory.getLogger(IdentityStartup::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun seedOnStartup() {
        val ran = lock.runExclusively("identity-seed", Duration.ofMinutes(5), Duration.ofSeconds(10)) {
            guard.asSystem("seed built-in roles") { seed.seed() }
            guard.asSystem("bootstrap local admin") { bootstrap.bootstrap() }?.let {
                log.warn("created the bootstrap local administrator {} (password must be changed at the first login)", it.email)
            }
        }
        log.info(if (ran) "identity seeding done" else "identity seeding skipped: another replica is seeding")
    }
}
