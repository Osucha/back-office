package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryIdentity
import pl.mos.backoffice.identity.application.port.`in`.ManagePersonalTokens.CreateToken
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.TokenCapabilities
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration
import java.util.UUID

class PersonalTokenServiceTest {

    private val db = InMemoryIdentity().apply { seedBuiltInRoles() }
    private val audit = RecordingAuditPort()
    private val project = UUID.randomUUID()

    private fun service(gate: FakeAuthorizationGate, maxDays: Int = 90) = PersonalTokenService(
        db.tokens, db.settingsProvider(IdentityOptions(defaultSettings = PlatformSettings(personalTokenMaxDays = maxDays))), db.catalogSupport,
        audit, db.accessVersion, db.clock, gate,
    )

    private val loader get() = LoadCurrentUserService(db.users, db.tokens, db.access, db.clock)

    @Test
    fun `creating a token needs tokens personal, stores only the hash and audits PAT_CREATE`() {
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).create(CreateToken("x", 30)) }.isInstanceOf(InsufficientPermissionException::class.java)
        val gate = FakeAuthorizationGate.permitting("tokens.personal")
        val created = service(gate).create(CreateToken("  laptop  ", 30))
        assertThat(created.plaintext).startsWith("bo_pat_")
        assertThat(created.token.hash).isNotEqualTo(created.plaintext)
        assertThat(created.token.name).isEqualTo("laptop")
        assertThat(created.token.expiresAt).isEqualTo(db.now.plus(Duration.ofDays(30)))
        assertThat(gate.checked).containsExactly("tokens.personal")
        assertThat(audit.entries.single().operation).isEqualTo("PAT_CREATE")
        assertThat(audit.entries.single().details.values.map { it.toString() }).noneMatch { it.contains(created.plaintext) }
        assertThat(service(gate).list()).hasSize(1)
    }

    @Test
    fun `expiry is bounded by the platform setting`() {
        val gate = FakeAuthorizationGate.permitting("tokens.personal")
        assertThatThrownBy { service(gate, maxDays = 30).create(CreateToken("x", 31)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service(gate).create(CreateToken("x", 0)) }.isInstanceOf(ValidationException::class.java)
        service(gate, maxDays = 365).create(CreateToken("x", 365))
    }

    @Test
    fun `the permission subset must be held by the owner`() {
        val gate = FakeAuthorizationGate.permitting("tokens.personal", "library.view")
        service(gate).create(CreateToken("x", 10, setOf("library.view")))
        assertThatThrownBy { service(gate).create(CreateToken("x", 10, setOf("library.manage"))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("library.manage")
        assertThatThrownBy { service(gate).create(CreateToken("x", 10, setOf("no.such-permission"))) }.isInstanceOf(ValidationException::class.java)
        // project permissions held on some project are allowed
        val onProject = FakeAuthorizationGate(FakeAuthorizationGate.user(platform = setOf("tokens.personal"), projects = mapOf(project to setOf("run.start"))))
        service(onProject).create(CreateToken("x", 10, setOf("run.start")))
    }

    @Test
    fun `a personal token cannot mint another token`() {
        val gate = FakeAuthorizationGate(FakeAuthorizationGate.user(platform = setOf("tokens.personal")).copy(kind = PrincipalKind.PERSONAL_TOKEN))
        assertThatThrownBy { service(gate).create(CreateToken("x", 10)) }.isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `revoking is always allowed for own tokens and not found for others`() {
        val created = service(FakeAuthorizationGate.permitting("tokens.personal")).create(CreateToken("x", 10))
        assertThatThrownBy { service(FakeAuthorizationGate(FakeAuthorizationGate.user().copy(userId = pl.mos.backoffice.shared.security.UserId.new()))).revoke(created.token.id) }
            .isInstanceOf(NotFoundException::class.java)
        service(FakeAuthorizationGate.denyAll()).revoke(created.token.id)
        assertThat(db.tokenMap.values.single().revokedAt).isNotNull()
        assertThat(audit.operations()).containsExactly("PAT_CREATE", "PAT_REVOKE")
    }

    @Test
    fun `only tokens created with an MCP option carry its capability, each option independently (ADR 0029, ADR 0049)`() {
        val owner = db.user("alice@example.com", id = FakeAuthorizationGate.TEST_USER_ID)
        db.assign(GrantLevel.SYSTEM, null, BuiltInRoles.MEMBER)
        val gate = FakeAuthorizationGate(loader.forUser(owner.id)!!)
        val plain = service(gate).create(CreateToken("ide", 10))
        val opted = service(gate).create(CreateToken("ide-gates", 10, setOf("library.view"), allowMcpGateDecisions = true))
        assertThat(audit.entries.last().details)
            .containsEntry("allowMcpGateDecisions", true).containsEntry("allowMcpWorkflowEdits", false)
        val workflows = service(gate).create(CreateToken("ide-workflows", 10, allowMcpWorkflowEdits = true))
        assertThat(audit.entries.last().details)
            .containsEntry("allowMcpGateDecisions", false).containsEntry("allowMcpWorkflowEdits", true)
        val both = service(gate).create(CreateToken("ide-both", 10, allowMcpGateDecisions = true, allowMcpWorkflowEdits = true))

        assertThat(plain.token.allowMcpGateDecisions).isFalse()
        assertThat(plain.token.allowMcpWorkflowEdits).isFalse()
        fun capabilitiesOf(plaintext: String) =
            loader.forPersonalToken(loader.forUser(owner.id)!!, loader.verifyPersonalToken(plaintext)!!).tokenCapabilities
        assertThat(capabilitiesOf(plain.plaintext)).isEmpty()
        assertThat(capabilitiesOf(opted.plaintext)).containsExactly(TokenCapabilities.MCP_GATE_DECISIONS)
        assertThat(capabilitiesOf(workflows.plaintext)).containsExactly(TokenCapabilities.MCP_WORKFLOW_EDITS)
        assertThat(capabilitiesOf(both.plaintext))
            .containsExactlyInAnyOrder(TokenCapabilities.MCP_GATE_DECISIONS, TokenCapabilities.MCP_WORKFLOW_EDITS)
        // a browser session never has token capabilities
        assertThat(loader.forUser(owner.id)!!.tokenCapabilities).isEmpty()
    }

    @Test
    fun `a token resolves to the owner's permissions narrowed to its subset and stops working when expired or revoked`() {
        val owner = db.user("alice@example.com", id = FakeAuthorizationGate.TEST_USER_ID)
        db.assign(GrantLevel.SYSTEM, null, BuiltInRoles.MEMBER)
        db.member(project, "PRJ", PrincipalType.USER, owner.id.value, "developer")
        val gate = FakeAuthorizationGate(loader.forUser(owner.id)!!)
        val narrow = service(gate).create(CreateToken("narrow", 10, setOf("library.view", "run.start")))
        val full = service(gate).create(CreateToken("full", 10))

        val verified = loader.verifyPersonalToken(narrow.plaintext)!!
        assertThat(db.touched).containsExactly(verified.id)
        val principal = loader.forPersonalToken(loader.forUser(owner.id)!!, verified)
        assertThat(principal.kind).isEqualTo(PrincipalKind.PERSONAL_TOKEN)
        assertThat(principal.platformPermissions).containsExactly("library.view")
        assertThat(principal.projectPermissions).isEqualTo(mapOf(project to setOf("run.start")))

        val fullPrincipal = loader.forPersonalToken(loader.forUser(owner.id)!!, loader.verifyPersonalToken(full.plaintext)!!)
        assertThat(fullPrincipal.platformPermissions).contains("library.view", "tokens.personal").doesNotContain("projects.create")

        assertThat(loader.verifyPersonalToken("bo_pat_" + "x".repeat(40))).isNull()
        service(gate).revoke(narrow.token.id)
        assertThat(loader.verifyPersonalToken(narrow.plaintext)).isNull()
        val expiredLoader = LoadCurrentUserService(db.users, db.tokens, db.access, pl.mos.backoffice.shared.Clock.fixed(db.now.plus(Duration.ofDays(11))))
        assertThat(expiredLoader.verifyPersonalToken(full.plaintext)).isNull()

        db.users.save(owner.deactivate())
        assertThat(loader.forUser(owner.id)).isNull()
        assertThat(owner.status).isEqualTo(UserStatus.ACTIVE)
    }

    @Test
    fun `a CI token principal holds ci permissions on its project only`() {
        val ci = loader.forCiToken(CiTokenIdentity(UUID.randomUUID(), project, "PRJ", "gitlab"))
        assertThat(ci.kind).isEqualTo(PrincipalKind.CI_TOKEN)
        assertThat(ci.login).isEqualTo("ci:PRJ")
        assertThat(ci.can("ci.execute", project)).isTrue()
        assertThat(ci.can("ci.signal", project)).isTrue()
        assertThat(ci.can("ci.execute", UUID.randomUUID())).isFalse()
        assertThat(ci.platformPermissions).isEmpty()
    }
}
