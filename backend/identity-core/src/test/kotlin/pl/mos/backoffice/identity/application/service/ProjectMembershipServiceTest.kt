package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryIdentity
import pl.mos.backoffice.identity.application.port.`in`.ManageProjectMembers.AddMember
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

class ProjectMembershipServiceTest {

    private val db = InMemoryIdentity().apply { seedBuiltInRoles() }
    private val audit = RecordingAuditPort()
    private val project = UUID.randomUUID()

    private fun service(gate: FakeAuthorizationGate) =
        ProjectMembershipService(db.memberships, db.users, db.groups, db.roles, db.projectKeys, audit, db.accessVersion, db.clock, gate)

    private fun manager() = FakeAuthorizationGate.permitting("project.members.manage", projects = setOf(project))

    @Test
    fun `a private project has only its owner, adding members is refused (ADR 0038)`() {
        val owner = db.user("owner@example.com")
        db.member(project, "MINE", PrincipalType.USER, owner.id.value, BuiltInRoles.OWNER)
        val dev = db.user("dev@example.com")
        val keys = ProjectKeys(db.memberships, object : pl.mos.backoffice.identity.application.port.out.ProjectKeyLookup {
            override fun keyOf(projectId: UUID): String? = null
            override fun isPersonal(projectId: UUID): Boolean = projectId == project
        })
        val private = ProjectMembershipService(db.memberships, db.users, db.groups, db.roles, keys, audit, db.accessVersion, db.clock, manager())
        assertThatThrownBy { private.add(project, AddMember(PrincipalType.USER, dev.id.value, "developer")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("private project")
        assertThat(db.memberships.findByProject(project)).hasSize(1)
    }

    @Test
    fun `the creator of a project becomes its owner`() {
        val creator = db.user("owner@example.com")
        service(FakeAuthorizationGate.denyAll()).grantCreator(project, "PRJ", Actor(PrincipalKind.USER, creator.id.value, "Owner"))
        val owner = db.memberships.findByProject(project).single()
        assertThat(owner.roleKey).isEqualTo(BuiltInRoles.OWNER)
        assertThat(owner.projectKey).isEqualTo("PRJ")
        assertThat(audit.operations()).containsExactly("MEMBER_ADD")
        // idempotent
        service(FakeAuthorizationGate.denyAll()).grantCreator(project, "PRJ", Actor(PrincipalKind.USER, creator.id.value, "Owner"))
        assertThat(db.memberships.findByProject(project)).hasSize(1)
    }

    @Test
    fun `adding a member needs project members manage on that project and rejects duplicates and unknown roles`() {
        val owner = db.user("owner@example.com")
        db.member(project, "PRJ", PrincipalType.USER, owner.id.value, BuiltInRoles.OWNER)
        val dev = db.user("dev@example.com")

        assertThatThrownBy { service(FakeAuthorizationGate.permitting("project.members.manage", projects = setOf(UUID.randomUUID()))).add(project, AddMember(PrincipalType.USER, dev.id.value, "developer")) }
            .isInstanceOf(InsufficientPermissionException::class.java)

        val gate = manager()
        val added = service(gate).add(project, AddMember(PrincipalType.USER, dev.id.value, "developer"))
        assertThat(added.principalEmail).isEqualTo("dev@example.com")
        assertThat(added.membership.projectKey).isEqualTo("PRJ")
        assertThat(gate.checked).containsExactly("project.members.manage@$project")
        assertThat(audit.entries.single().projectId).isEqualTo(project)

        assertThatThrownBy { service(gate).add(project, AddMember(PrincipalType.USER, dev.id.value, "viewer")) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { service(gate).add(project, AddMember(PrincipalType.USER, dev.id.value, "platform-admin")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the last owner cannot be removed or demoted, another owner makes it possible`() {
        val owner = db.user("owner@example.com")
        val ownership = db.member(project, "PRJ", PrincipalType.USER, owner.id.value, BuiltInRoles.OWNER)
        val gate = manager()
        assertThatThrownBy { service(gate).remove(project, ownership.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("last owner")
        assertThatThrownBy { service(gate).changeRole(project, ownership.id, "developer") }.isInstanceOf(InvalidStateException::class.java)

        val group = db.group("Leads")
        db.member(project, "PRJ", PrincipalType.GROUP, group.id.value, BuiltInRoles.OWNER)
        service(gate).changeRole(project, ownership.id, "developer")
        assertThat(audit.entries.single().operation).isEqualTo("MEMBER_ROLE_CHANGE")
        assertThat(audit.entries.single().details["role"]).isEqualTo(mapOf("old" to "owner", "new" to "developer"))
        service(gate).remove(project, ownership.id)
        assertThat(audit.operations()).containsExactly("MEMBER_ROLE_CHANGE", "MEMBER_REMOVE")
        assertThat(db.version).isEqualTo(2)
    }

    @Test
    fun `listing needs project view and deleting a project removes its memberships`() {
        val owner = db.user("owner@example.com")
        db.member(project, "PRJ", PrincipalType.USER, owner.id.value, BuiltInRoles.OWNER)
        val gate = FakeAuthorizationGate.permitting("project.view", projects = setOf(project))
        assertThat(service(gate).list(project).single().principalName).isEqualTo("owner")
        assertThat(gate.checked).containsExactly("project.view@$project")

        service(FakeAuthorizationGate.denyAll()).projectDeleted(project)
        assertThat(db.memberships.findByProject(project)).isEmpty()
        assertThat(audit.operations()).containsExactly("MEMBER_REMOVE")
    }

    @Test
    fun `directory lookup is allowed to user viewers and to project member managers only`() {
        db.user("alice@example.com")
        val directory = DirectoryService(db.users, db.groups, FakeAuthorizationGate.permitting("admin.users.view"))
        assertThat(directory.users("ali")).hasSize(1)
        val managerGate = manager()
        assertThat(DirectoryService(db.users, db.groups, managerGate).users("ali")).hasSize(1)
        assertThat(managerGate.checked).containsExactly("project.members.manage@$project")
        assertThatThrownBy { DirectoryService(db.users, db.groups, FakeAuthorizationGate.denyAll()).users("ali") }
            .isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { directory.users("a") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the access directory lists active direct and group members with their resolved project access`() {
        val owner = db.user("owner@example.com")
        val approver = db.user("approver@example.com")
        val gone = db.user("gone@example.com", status = pl.mos.backoffice.identity.domain.model.UserStatus.DEACTIVATED)
        val outsider = db.user("outsider@example.com")
        val team = db.group("team", members = setOf(approver.id))
        db.member(project, "PRJ", PrincipalType.USER, owner.id.value, BuiltInRoles.OWNER)
        db.member(project, "PRJ", PrincipalType.USER, gone.id.value, BuiltInRoles.APPROVER)
        db.member(project, "PRJ", PrincipalType.GROUP, team.id.value, BuiltInRoles.APPROVER)
        val directory = ProjectAccessDirectoryService(db.memberships, db.users, db.access)

        val members = directory.members(project)
        assertThat(members.map { it.displayName }).containsExactly("approver", "owner")
        val viaGroup = members.first()
        assertThat(viaGroup.can("run.approve", project)).isTrue()
        assertThat(viaGroup.can("run.assign", project)).isFalse()
        assertThat(viaGroup.hasProjectRole(project, setOf(BuiltInRoles.APPROVER))).isTrue()
        assertThat(members.last().can("run.assign", project)).isTrue()

        assertThat(directory.user(outsider.id.value)!!.can("project.view", project)).isFalse()
        assertThat(directory.user(gone.id.value)).isNull()
        assertThat(directory.user(UUID.randomUUID())).isNull()
        assertThat(directory.displayNames(listOf(owner.id.value, UUID.randomUUID()))).isEqualTo(mapOf(owner.id.value to "owner"))
    }
}
