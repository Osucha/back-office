package pl.mos.backoffice.identity.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.InMemoryIdentity
import pl.mos.backoffice.identity.application.port.`in`.ManageGroups.GroupCommand
import pl.mos.backoffice.identity.application.port.`in`.ManageRoles.RoleCommand
import pl.mos.backoffice.identity.application.port.out.AuditQuery
import pl.mos.backoffice.identity.application.port.out.AuditRecordPage
import pl.mos.backoffice.identity.domain.model.BuiltInRoles
import pl.mos.backoffice.identity.domain.model.Grant
import pl.mos.backoffice.identity.domain.model.GrantEffect
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.PlatformSettings
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.UserPreferences
import pl.mos.backoffice.identity.domain.model.Theme
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

class AccessAdminServicesTest {

    private val db = InMemoryIdentity()
    private val audit = RecordingAuditPort()

    private fun grants(gate: FakeAuthorizationGate) = GrantAdminService(
        db.grants, db.roles, db.users, db.groups, db.memberships, db.access, db.projectKeys, db.catalogSupport, db.admins, audit, db.accessVersion, gate,
    )

    private fun roles(gate: FakeAuthorizationGate) = RoleAdminService(db.roles, db.grants, db.memberships, db.catalogSupport, audit, db.accessVersion, gate)

    private fun groups(gate: FakeAuthorizationGate) =
        GroupAdminService(db.groups, db.users, db.roles, db.grants, db.memberships, db.admins, audit, db.accessVersion, db.clock, gate)

    private fun seed() = SeedIdentityService(db.roles, db.grants, db.catalogSupport, audit, db.accessVersion)

    @Test
    fun `seeding creates built-in roles and the SYSTEM member default once`() {
        seed().seed()
        assertThat(db.roleMap.keys).containsExactlyInAnyOrderElementsOf(BuiltInRoles.keys)
        assertThat(db.grants.find(GrantLevel.SYSTEM, null).roles).containsExactly("member")
        assertThat(db.roleMap.getValue("owner").permissions).doesNotContain("ci.execute", "ci.signal").contains("project.members.manage")
        assertThat(db.roleMap.getValue("maintainer").permissions).doesNotContain("project.members.manage").contains("project.manage")

        // an administrator removes the SYSTEM default: a restart does not bring it back
        db.grants.save(GrantSet.system())
        audit.entries.clear()
        seed().seed()
        assertThat(db.grants.find(GrantLevel.SYSTEM, null).roles).isEmpty()
        assertThat(audit.entries).isEmpty()
    }

    @Test
    fun `projects are created by project managers, not by every member (ADR 0028)`() {
        seed().seed()
        val member = db.roleMap.getValue(BuiltInRoles.MEMBER).permissions
        val manager = db.roleMap.getValue(BuiltInRoles.PROJECT_MANAGER)
        assertThat(member).doesNotContain("projects.create").contains("library.view", "tokens.personal")
        assertThat(db.roleMap.getValue(BuiltInRoles.EVALUATOR).permissions).doesNotContain("projects.create")
        assertThat(db.roleMap.getValue(BuiltInRoles.LIBRARY_CURATOR).permissions).doesNotContain("projects.create")
        assertThat(manager.scope).isEqualTo(PermissionScope.PLATFORM)
        assertThat(manager.permissions).containsAll(member).contains("projects.create", "usage.view")
        assertThat(db.roleMap.getValue(BuiltInRoles.PLATFORM_ADMIN).permissions).contains("projects.create")
        assertThat(db.roleMap.getValue(BuiltInRoles.OWNER).permissions).contains("run.assign")
        assertThat(db.roleMap.getValue(BuiltInRoles.MAINTAINER).permissions).contains("run.assign")
        assertThat(db.roleMap.getValue(BuiltInRoles.DEVELOPER).permissions).doesNotContain("run.assign")
        assertThat(db.roleMap.getValue(BuiltInRoles.APPROVER).permissions).doesNotContain("run.assign")
        // every key of every built-in role is in the catalogue
        val known = db.catalogDefinitions.map { it.key }.toSet()
        BuiltInRoles.all.forEach { role -> assertThat(known).describedAs(role.key).containsAll(role.permissions) }
    }

    @Test
    fun `an upgrade creates the missing project-manager role, audited, and leaves stored roles alone`() {
        seed().seed()
        db.roleMap.remove(BuiltInRoles.PROJECT_MANAGER) // as stored by a release before ADR 0028
        val storedMember = db.roleMap.getValue(BuiltInRoles.MEMBER)
        db.roles.save(storedMember.copy(permissions = storedMember.permissions + "projects.create"))
        audit.entries.clear()

        seed().seed()

        assertThat(db.roleMap.getValue(BuiltInRoles.PROJECT_MANAGER).permissions).contains("projects.create")
        assertThat(audit.entries.map { it.operation to it.entityId }).containsExactly("ROLE_CREATE" to BuiltInRoles.PROJECT_MANAGER)
        // the stored member role is changed by the V406 migration, never by seeding
        assertThat(db.roleMap.getValue(BuiltInRoles.MEMBER).permissions).contains("projects.create")
    }

    @Test
    fun `an upgrade grants platform-admin the platform permissions added since it was created`() {
        seed().seed()
        val admin = db.roleMap.getValue(BuiltInRoles.PLATFORM_ADMIN)
        val dropped = db.catalogSupport.platformKeys().first()
        db.roles.save(admin.copy(permissions = admin.permissions - dropped)) // as stored by an older release
        audit.entries.clear()

        seed().seed()

        assertThat(db.roleMap.getValue(BuiltInRoles.PLATFORM_ADMIN).permissions).contains(dropped)
        assertThat(audit.entries.map { it.operation }).containsExactly("ROLE_UPDATE")
        audit.entries.clear()
        seed().seed()
        assertThat(audit.entries).isEmpty()
    }

    @Test
    fun `replacing grants validates the catalogue and records one GRANT_CHANGE per changed permission`() {
        seed().seed()
        audit.entries.clear()
        val gate = FakeAuthorizationGate.permitting("admin.roles.manage")
        grants(gate).replace(GrantSet.system(roles = setOf("member"), grants = listOf(Grant("library.manage", GrantEffect.ALLOW), Grant("usage.view", GrantEffect.DENY))))
        assertThat(gate.checked).containsExactly("admin.roles.manage")
        assertThat(audit.entries.filter { it.operation == "GRANT_CHANGE" }.map { it.details["permission"] }).containsExactly("library.manage", "usage.view")

        audit.entries.clear()
        grants(gate).replace(GrantSet.system(roles = setOf("member", "auditor"), grants = listOf(Grant("usage.view", GrantEffect.DENY))))
        assertThat(audit.operations()).containsExactly("GRANT_CHANGE", "ROLE_ASSIGN")
        assertThat(audit.entries.first().details).containsEntry("old", "ALLOW").containsEntry("new", "NONE")

        assertThatThrownBy { grants(gate).replace(GrantSet.system(grants = listOf(Grant("run.start", GrantEffect.ALLOW)))) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { grants(gate).replace(GrantSet.system(roles = setOf("owner"))) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { grants(FakeAuthorizationGate.permitting("admin.users.manage")).replace(GrantSet.system()) }
            .isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `effective permissions show the System, Group, User breakdown and project roles`() {
        seed().seed()
        val group = db.group("Curators")
        db.assign(GrantLevel.GROUP, group.id.value, BuiltInRoles.LIBRARY_CURATOR)
        val user = db.user("u@example.com", groups = setOf(group.id))
        db.grants.save(GrantSet(GrantLevel.USER, user.id.value, grants = listOf(Grant("library.view", GrantEffect.DENY))))
        val project = UUID.randomUUID()
        db.member(project, "PRJ", PrincipalType.GROUP, group.id.value, "viewer")

        val view = grants(FakeAuthorizationGate.permitting("admin.users.view")).of(user.id)
        val manage = view.rows.first { it.permission == "library.manage" }
        assertThat(manage.system).isNull()
        assertThat(manage.groups.single().effect).isEqualTo(GrantEffect.ALLOW)
        assertThat(manage.effective).isTrue()
        assertThat(manage.source).isEqualTo("GROUP:Curators")
        val libraryView = view.rows.first { it.permission == "library.view" }
        assertThat(libraryView.system).isEqualTo(GrantEffect.ALLOW)
        assertThat(libraryView.user).isEqualTo(GrantEffect.DENY)
        assertThat(libraryView.effective).isFalse()
        assertThat(view.projects.single().projectKey).isEqualTo("PRJ")
        assertThat(view.projects.single().roles).containsExactly("viewer")
    }

    @Test
    fun `built-in roles are editable but not deletable, custom roles validate scope`() {
        seed().seed()
        audit.entries.clear()
        val gate = FakeAuthorizationGate.permitting("admin.roles.manage")
        val updated = roles(gate).update("developer", RoleCommand("developer", "Developer", null, PermissionScope.PROJECT, setOf("project.view", "run.start")))
        assertThat(updated.builtIn).isTrue()
        assertThat(audit.operations()).containsExactly("ROLE_UPDATE")
        assertThatThrownBy { roles(gate).delete("developer") }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { roles(gate).create(RoleCommand("mixed", "Mixed", null, PermissionScope.PLATFORM, setOf("run.start"))) }
            .isInstanceOf(ValidationException::class.java)
        roles(gate).create(RoleCommand("release-manager", "Release manager", null, PermissionScope.PROJECT, setOf("project.view", "run.approve")))
        assertThatThrownBy { roles(gate).create(RoleCommand("release-manager", "Again", null, PermissionScope.PROJECT, emptySet())) }.isInstanceOf(InvalidStateException::class.java)
        roles(gate).delete("release-manager")
        assertThat(audit.operations()).containsExactly("ROLE_UPDATE", "ROLE_CREATE", "ROLE_DELETE")
        assertThat(roles(FakeAuthorizationGate.denyAll()).list(PermissionScope.PROJECT).map { it.key }).contains("owner").doesNotContain("member")
    }

    @Test
    fun `groups need admin users manage, names are unique and platform roles need admin roles manage`() {
        seed().seed()
        audit.entries.clear()
        val user = db.user("m@example.com")
        val gate = FakeAuthorizationGate.permitting("admin.users.manage")
        val created = groups(gate).create(GroupCommand("Architects", memberIds = setOf(user.id), externalGroup = "architects"))
        assertThat(created.group.memberIds).containsExactly(user.id)
        assertThatThrownBy { groups(gate).create(GroupCommand("architects")) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { groups(gate).update(created.group.id, GroupCommand("Architects", memberIds = setOf(user.id), platformRoles = setOf("evaluator"))) }
            .isInstanceOf(InsufficientPermissionException::class.java)
        groups(FakeAuthorizationGate.permitting("admin.users.manage", "admin.roles.manage"))
            .update(created.group.id, GroupCommand("Architects", memberIds = setOf(user.id), platformRoles = setOf("evaluator"), externalGroup = "architects"))
        assertThat(db.grants.find(GrantLevel.GROUP, created.group.id.value).roles).containsExactly("evaluator")

        db.member(UUID.randomUUID(), "PRJ", PrincipalType.GROUP, created.group.id.value, "viewer")
        assertThatThrownBy { groups(gate).delete(created.group.id) }.isInstanceOf(InvalidStateException::class.java)
        assertThat(audit.operations()).containsExactly("GROUP_CREATE", "GROUP_UPDATE")
    }

    @Test
    fun `platform settings need admin settings manage and changes are audited`() {
        val service = PlatformSettingsService(db.settingsRepository, db.settingsProvider(), audit, db.accessVersion, FakeAuthorizationGate.permitting("admin.settings.manage"))
        assertThat(service.get().registration).isEqualTo(RegistrationMode.INVITE_ONLY)
        service.update(PlatformSettings(RegistrationMode.OPEN, listOf("@Example.COM")))
        assertThat(db.storedSettings!!.allowedEmailDomains).containsExactly("example.com")
        assertThat(audit.entries.single().details).containsOnlyKeys("registration", "allowedEmailDomains")
        assertThat(service.registrationMode()).isEqualTo(RegistrationMode.OPEN)
        assertThatThrownBy { PlatformSettingsService(db.settingsRepository, db.settingsProvider(), audit, db.accessVersion, FakeAuthorizationGate.denyAll()).get() }
            .isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { PlatformSettings(personalTokenMaxDays = 400) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `searching the audit log needs admin audit view and is itself audited`() {
        var seen: AuditQuery? = null
        val service = AuditSearchService({ query -> seen = query; AuditRecordPage(emptyList(), null) }, audit, FakeAuthorizationGate.permitting("admin.audit.view"))
        service.search(AuditQuery(operation = "USER_UPDATE", size = 1000))
        assertThat(seen!!.size).isEqualTo(200)
        assertThat(audit.entries.single().operation).isEqualTo("AUDIT_VIEW")
        assertThat(audit.entries.single().details["filter"]).isEqualTo(mapOf("operation" to "USER_UPDATE"))
        assertThatThrownBy { AuditSearchService({ AuditRecordPage(emptyList(), null) }, audit, FakeAuthorizationGate.permitting("admin.users.view")).search(AuditQuery()) }
            .isInstanceOf(InsufficientPermissionException::class.java)
    }

    @Test
    fun `me returns the profile with groups and projects and preferences are saved`() {
        seed().seed()
        val group = db.group("Team")
        val me = db.user("alice@example.com", id = FakeAuthorizationGate.TEST_USER_ID, groups = setOf(group.id))
        val project = UUID.randomUUID()
        db.member(project, "PRJ", PrincipalType.USER, me.id.value, "developer")
        val gate = FakeAuthorizationGate(db.access.resolve(db.users.findById(me.id)!!).toCurrentUser())
        val service = MeService(db.users, db.groups, db.memberships, db.projectKeys, audit, gate)
        val view = service.me()
        assertThat(view.groups.map { it.name }).containsExactly("Team")
        assertThat(view.projects.single().projectKey).isEqualTo("PRJ")
        assertThat(view.projects.single().permissions).contains("workflow.edit")
        service.update(UserPreferences(theme = Theme.DARK))
        assertThat(db.userMap.getValue(me.id).preferences.theme).isEqualTo(Theme.DARK)
        assertThat(audit.entries.last().operation).isEqualTo("USER_PREFERENCES_UPDATE")
        assertThatThrownBy { UserPreferences(pageSize = 5) }.isInstanceOf(ValidationException::class.java)
    }
}
