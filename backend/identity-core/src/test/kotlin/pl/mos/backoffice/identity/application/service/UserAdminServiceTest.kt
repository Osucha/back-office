package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryIdentity
import pl.mos.backoffice.identity.application.port.`in`.ManageUsers
import pl.mos.backoffice.identity.application.port.out.UserQuery
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

class UserAdminServiceTest {

    private val db = InMemoryIdentity().apply { seedBuiltInRoles() }
    private val audit = RecordingAuditPort()

    private fun service(gate: FakeAuthorizationGate) = UserAdminService(
        db.users, db.groups, db.roles, db.grants, db.memberships, db.tokens, db.sessions, db.projectKeys, db.admins,
        audit, db.accessVersion, db.clock, gate,
    )

    @Test
    fun `listing needs admin users view`() {
        db.user("a@example.com")
        assertThatThrownBy { service(FakeAuthorizationGate.permitting("admin.users.manage")).list(UserQuery()) }
            .isInstanceOf(InsufficientPermissionException::class.java)
        val gate = FakeAuthorizationGate.permitting("admin.users.view")
        assertThat(service(gate).list(UserQuery()).totalElements).isEqualTo(1)
        assertThat(gate.checked).containsExactly("admin.users.view")
    }

    @Test
    fun `invite creates an INVITED user, audits USER_INVITE and rejects an existing e-mail with 409`() {
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val group = db.group("Devs")
        val details = service(gate).invite(ManageUsers.InviteUser("New.User@Example.com", groupIds = setOf(group.id)))
        assertThat(details.user.status).isEqualTo(UserStatus.INVITED)
        assertThat(details.user.email).isEqualTo("new.user@example.com")
        assertThat(details.groups.map { it.name }).containsExactly("Devs")
        assertThat(gate.checked).containsExactly("admin.users.manage")
        assertThat(audit.operations()).containsExactly("USER_INVITE")
        assertThat(db.version).isEqualTo(1)

        assertThatThrownBy { service(gate).invite(ManageUsers.InviteUser("new.user@example.com")) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { service(gate).invite(ManageUsers.InviteUser("not-an-email")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `inviting with platform roles also needs admin roles manage and with memberships project members manage`() {
        assertThatThrownBy { service(FakeAuthorizationGate.permitting("admin.users.manage")).invite(ManageUsers.InviteUser("x@example.com", platformRoles = setOf("auditor"))) }
            .isInstanceOf(InsufficientPermissionException::class.java)
            .hasFieldOrPropertyWithValue("permission", "admin.roles.manage")

        val project = UUID.randomUUID()
        db.projectKeyMap[project] = "PRJ"
        val gate = FakeAuthorizationGate.permitting("admin.users.manage", "admin.roles.manage", "project.members.manage", projects = setOf(project))
        val details = service(gate).invite(
            ManageUsers.InviteUser("y@example.com", platformRoles = setOf("auditor"), projectMemberships = listOf(ManageUsers.InitialMembership(project, "developer"))),
        )
        assertThat(details.platformRoles).containsExactly("auditor")
        assertThat(details.memberships.single().membership.projectKey).isEqualTo("PRJ")
        assertThat(gate.checked).containsExactly("admin.users.manage", "admin.roles.manage", "project.members.manage@$project")
        assertThat(audit.operations()).containsExactly("USER_INVITE", "MEMBER_ADD")
    }

    @Test
    fun `update records only the changed fields and skips the entry when nothing changed`() {
        val user = db.user("u@example.com")
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        service(gate).update(user.id, ManageUsers.UpdateUser(displayName = "Renamed"))
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("USER_UPDATE")
        assertThat(entry.details).containsOnlyKeys("displayName")
        assertThat(entry.details["displayName"]).isEqualTo(mapOf("old" to "u", "new" to "Renamed"))

        service(gate).update(user.id, ManageUsers.UpdateUser(displayName = "Renamed"))
        assertThat(audit.entries).hasSize(1)
    }

    @Test
    fun `changing platform roles needs admin roles manage`() {
        val user = db.user("u@example.com")
        assertThatThrownBy { service(FakeAuthorizationGate.permitting("admin.users.manage")).update(user.id, ManageUsers.UpdateUser(platformRoles = setOf("auditor"))) }
            .isInstanceOf(InsufficientPermissionException::class.java)
        service(FakeAuthorizationGate.permitting("admin.users.manage", "admin.roles.manage")).update(user.id, ManageUsers.UpdateUser(platformRoles = setOf("auditor")))
        assertThat(db.grants.find(GrantLevel.USER, user.id.value).roles).containsExactly("auditor")
    }

    @Test
    fun `an admin cannot deactivate themselves`() {
        db.user("alice@example.com", id = FakeAuthorizationGate.TEST_USER_ID)
        assertThatThrownBy { service(FakeAuthorizationGate.permitting("admin.users.manage")).deactivate(FakeAuthorizationGate.TEST_USER_ID) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("yourself")
    }

    @Test
    fun `the last platform admin cannot be deactivated or lose the role, directly or through a group`() {
        val admin = db.user("root@example.com")
        db.assign(GrantLevel.USER, admin.id.value, BuiltInRoles.PLATFORM_ADMIN)
        val gate = FakeAuthorizationGate.permitting("admin.users.manage", "admin.roles.manage")
        assertThatThrownBy { service(gate).deactivate(admin.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("last platform administrator")
        assertThatThrownBy { service(gate).update(admin.id, ManageUsers.UpdateUser(platformRoles = emptySet())) }.isInstanceOf(InvalidStateException::class.java)

        // a second admin through a group makes it possible
        val admins = db.group("Admins")
        db.assign(GrantLevel.GROUP, admins.id.value, BuiltInRoles.PLATFORM_ADMIN)
        val second = db.user("second@example.com", groups = setOf(admins.id))
        service(gate).deactivate(admin.id)
        assertThat(db.userMap.getValue(admin.id).status).isEqualTo(UserStatus.DEACTIVATED)
        // and now the group member is the last one: removing them from the group is refused
        assertThatThrownBy { service(gate).update(second.id, ManageUsers.UpdateUser(groupIds = emptySet())) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `deactivation revokes tokens, terminates sessions, audits and bumps the access version`() {
        val user = db.user("t@example.com")
        db.tokens.save(PersonalAccessToken(TokenId.new(), user.id, "ci", "h", "bo_pat_abcde", emptySet(), db.now.plusSeconds(3600), db.now))
        val details = service(FakeAuthorizationGate.permitting("admin.users.manage")).deactivate(user.id)
        assertThat(details.user.status).isEqualTo(UserStatus.DEACTIVATED)
        assertThat(db.tokenMap.values.single().revokedAt).isEqualTo(db.now)
        assertThat(db.terminatedSessions).containsExactly(user.id)
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("USER_DEACTIVATE")
        assertThat(entry.details).containsEntry("revokedTokens", 1).containsEntry("terminatedSessions", 2)
        assertThat(db.version).isEqualTo(1)

        val activated = service(FakeAuthorizationGate.permitting("admin.users.manage")).activate(user.id)
        assertThat(activated.user.status).isEqualTo(UserStatus.ACTIVE)
        assertThat(audit.operations()).containsExactly("USER_DEACTIVATE", "USER_ACTIVATE")
    }

    @Test
    fun `reactivating a never-logged-in user returns them to INVITED`() {
        val user = db.user("i@example.com", status = UserStatus.INVITED)
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        service(gate).deactivate(user.id)
        assertThat(service(gate).activate(user.id).user.status).isEqualTo(UserStatus.INVITED)
    }

    @Test
    fun `terminating sessions is audited`() {
        val user = db.user("s@example.com")
        assertThat(service(FakeAuthorizationGate.permitting("admin.users.view")).list(user.id)).hasSize(1)
        assertThat(service(FakeAuthorizationGate.permitting("admin.users.manage")).terminateAll(user.id)).isEqualTo(2)
        assertThat(audit.operations()).containsExactly("SESSIONS_TERMINATE")
    }
}
