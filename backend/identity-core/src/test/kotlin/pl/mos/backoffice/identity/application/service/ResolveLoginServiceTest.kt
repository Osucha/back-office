package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryIdentity
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin.LoginClaims
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin.LoginResult
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.testing.RecordingAuditPort

class ResolveLoginServiceTest {

    private val db = InMemoryIdentity().apply { seedBuiltInRoles() }
    private val audit = RecordingAuditPort()

    private fun service(
        registration: RegistrationMode = RegistrationMode.INVITE_ONLY,
        domains: List<String> = emptyList(),
        sync: Boolean = false,
        bootstrap: Set<String> = emptySet(),
    ): ResolveLoginService {
        val options = IdentityOptions(bootstrap, PlatformSettings(registration, domains, sync))
        return ResolveLoginService(db.users, db.groups, db.grants, db.settingsProvider(options), options, audit, db.accessVersion, db.clock)
    }

    private fun claims(email: String?, subject: String = "sub-1", groups: List<String> = emptyList(), verified: Boolean? = true) =
        LoginClaims("https://idp", subject, email, verified, "Alice Example", "alice", groups)

    @Test
    fun `invite-only rejects an unknown e-mail with not-invited and audits a LOGIN_FAILURE`() {
        val result = service().resolve(claims("stranger@example.com"))
        assertThat(result).isEqualTo(LoginResult.Rejected(ResolveLogin.NOT_INVITED, (result as LoginResult.Rejected).reason))
        assertThat(db.userMap).isEmpty()
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("LOGIN_FAILURE")
        assertThat(entry.result).isEqualTo(AuditResult.FAILURE)
        assertThat(entry.details["reason"]).isEqualTo("not-invited")
    }

    @Test
    fun `open registration provisions an active user and audits provisioning and login`() {
        val result = service(registration = RegistrationMode.OPEN).resolve(claims("Alice@Example.com"))
        val user = (result as LoginResult.Success).user
        assertThat(result.firstLogin).isTrue()
        assertThat(user.email).isEqualTo("alice@example.com")
        assertThat(user.displayName).isEqualTo("Alice Example")
        assertThat(user.status).isEqualTo(UserStatus.ACTIVE)
        assertThat(user.lastLoginAt).isEqualTo(db.now)
        assertThat(audit.operations()).containsExactly("USER_PROVISION", "LOGIN_SUCCESS")
        assertThat(audit.entries.last().actor?.userId).isEqualTo(user.id.value)
        assertThat(audit.entries.last().actor?.kind).isEqualTo(PrincipalKind.USER)
        assertThat(db.version).isEqualTo(1)
    }

    @Test
    fun `invite-only accepts e-mails of allowed domains`() {
        val result = service(domains = listOf("@Example.com")).resolve(claims("bob@example.com"))
        assertThat(result).isInstanceOf(LoginResult.Success::class.java)
        assertThat(service(domains = listOf("example.com")).resolve(claims("eve@evil.com", subject = "sub-2"))).isInstanceOf(LoginResult.Rejected::class.java)
    }

    @Test
    fun `an invited user is bound to the OIDC identity and activated on first login`() {
        val invited = db.user("carol@example.com", status = UserStatus.INVITED)
        val result = service().resolve(claims("carol@example.com", subject = "kc-carol")) as LoginResult.Success
        assertThat(result.user.id).isEqualTo(invited.id)
        assertThat(result.user.subject).isEqualTo("kc-carol")
        assertThat(result.user.status).isEqualTo(UserStatus.ACTIVE)
        // the next login finds the user by (issuer, subject) even when the e-mail changed at the provider
        val again = service().resolve(claims("carol.new@example.com", subject = "kc-carol")) as LoginResult.Success
        assertThat(again.user.id).isEqualTo(invited.id)
        assertThat(again.user.email).isEqualTo("carol.new@example.com")
        assertThat(again.firstLogin).isFalse()
    }

    @Test
    fun `deactivated users are rejected whether bound or invited`() {
        val bound = db.user("dave@example.com", status = UserStatus.DEACTIVATED)
        val result = service(registration = RegistrationMode.OPEN).resolve(claims("dave@example.com", subject = bound.subject!!))
        assertThat((result as LoginResult.Rejected).code).isEqualTo(ResolveLogin.DEACTIVATED)
        assertThat(audit.entries.single().entityId).isEqualTo(bound.id.toString())
    }

    @Test
    fun `an e-mail bound to another identity is rejected as identity-conflict`() {
        db.user("erin@example.com")
        val result = service(registration = RegistrationMode.OPEN).resolve(claims("erin@example.com", subject = "other-subject"))
        assertThat((result as LoginResult.Rejected).code).isEqualTo(ResolveLogin.IDENTITY_CONFLICT)
    }

    @Test
    fun `unverified or missing e-mails are rejected for new identities`() {
        assertThat((service(registration = RegistrationMode.OPEN).resolve(claims("x@example.com", verified = false)) as LoginResult.Rejected).code)
            .isEqualTo(ResolveLogin.EMAIL_NOT_VERIFIED)
        assertThat((service(registration = RegistrationMode.OPEN).resolve(claims(null)) as LoginResult.Rejected).code)
            .isEqualTo(ResolveLogin.EMAIL_MISSING)
    }

    @Test
    fun `bootstrap admins are provisioned even when invite-only and receive platform-admin once`() {
        val result = service(bootstrap = setOf(" Admin@Example.com ")).resolve(claims("admin@example.com")) as LoginResult.Success
        assertThat(db.grants.find(GrantLevel.USER, result.user.id.value).roles).containsExactly(BuiltInRoles.PLATFORM_ADMIN)
        assertThat(audit.operations()).containsExactly("USER_PROVISION", "ROLE_ASSIGN", "LOGIN_SUCCESS")

        // an administrator later removes the role: the next login does not re-add it
        db.grants.save(db.grants.find(GrantLevel.USER, result.user.id.value).copy(roles = emptySet()))
        service(bootstrap = setOf("admin@example.com")).resolve(claims("admin@example.com"))
        assertThat(db.grants.find(GrantLevel.USER, result.user.id.value).roles).isEmpty()
    }

    @Test
    fun `groups are synchronised from the groups claim when enabled`() {
        val architects = db.group("Architects", external = "architects")
        val ops = db.group("Ops", external = "/ops")
        val manual = db.group("Manual")
        val user = db.user("frank@example.com", groups = setOf(ops.id, manual.id))

        val result = service(sync = true).resolve(claims("frank@example.com", subject = user.subject!!, groups = listOf("/architects", "other"))) as LoginResult.Success
        assertThat(result.user.groupIds).containsExactlyInAnyOrder(architects.id, manual.id)
        assertThat(db.groups.findById(architects.id)!!.memberIds).contains(user.id)

        val unsynced = service(sync = false).resolve(claims("frank@example.com", subject = user.subject!!, groups = emptyList())) as LoginResult.Success
        assertThat(unsynced.user.groupIds).containsExactlyInAnyOrder(architects.id, manual.id)
    }

    @Test
    fun `a LOCAL-only user is refused by the OIDC login and a BOTH user is bound`() {
        val local = db.users.save(pl.mos.backoffice.identity.domain.model.User.invite("local@example.com", null, emptySet(), db.now, pl.mos.backoffice.shared.security.Actor.SYSTEM, AuthMode.LOCAL))
        val refused = service().resolve(claims("local@example.com", subject = "sub-local"))
        assertThat((refused as LoginResult.Rejected).code).isEqualTo(ResolveLogin.METHOD_NOT_ALLOWED)
        assertThat(db.userMap[local.id]!!.isBound).isFalse()
        assertThat(audit.entries.last().details).containsEntry("reason", "method-not-allowed").containsEntry("method", "OIDC")

        val both = db.users.save(pl.mos.backoffice.identity.domain.model.User.invite("both@example.com", null, emptySet(), db.now, pl.mos.backoffice.shared.security.Actor.SYSTEM, AuthMode.BOTH))
        val bound = service().resolve(claims("both@example.com", subject = "sub-both"))
        assertThat((bound as LoginResult.Success).user.id).isEqualTo(both.id)
        assertThat(bound.user.lastLoginMethod).isEqualTo(LoginMethod.OIDC)
    }
}
