package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.tasks.application.TasksPermissions
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskInterfaces.InterfaceCommand
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.support.Library
import java.util.UUID

/** ADR 0017 / 0018 for the Task Library: exact permission per use case, denial, project visibility, audit and actor stamps. */
class LibraryAccessTest {

    private val lib = Library()
    private val audit = RecordingAuditPort()
    private val stranger: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun templates(gate: FakeAuthorizationGate) = TaskTemplateService(lib.templates, lib.lookup, lib.clock, gate, audit)
    private fun skills(gate: FakeAuthorizationGate) = SkillService(lib.skills, lib.templates, lib.clock, gate, audit)
    private fun interfaces(gate: FakeAuthorizationGate) = TaskInterfaceService(lib.interfaces, lib.templates, lib.clock, gate, audit)

    private fun content(key: String, scope: Scope = Scope.Global) = TaskTemplate.Content(key = key, name = key, scope = scope, executor = TaskExecutor.LLM)

    @Test
    fun `the permission catalogue declares the three library permissions with their scopes`() {
        assertThat(TasksPermissions.definitions.associate { it.key to it.scope }).containsExactlyInAnyOrderEntriesOf(
            mapOf("library.view" to PermissionScope.PLATFORM, "library.manage" to PermissionScope.PLATFORM, "library.project.manage" to PermissionScope.PROJECT),
        )
        assertThat(TasksPermissions.definitions).allSatisfy { assertThat(it.module).isEqualTo("tasks") }
    }

    @Test
    fun `global templates are read with library view and changed only with library manage`() {
        val reader = FakeAuthorizationGate.permitting("library.view")
        val curator = FakeAuthorizationGate.permitting("library.manage")

        val created = templates(curator).create(content("global-one"))
        assertThat(curator.checked).containsExactly("library.manage")
        assertThat(created.updatedBy!!.displayName).isEqualTo("Alice Test")
        assertThat(created.updatedBy!!.kind).isEqualTo(PrincipalKind.USER)

        assertThat(templates(reader).list().map { it.key }).containsExactly("global-one")
        assertThat(templates(reader).get(created.id).key).isEqualTo("global-one")
        assertThat(templates(reader).resolve("global-one").key).isEqualTo("global-one")
        assertThat(reader.checked).containsOnly("library.view")

        assertThatThrownBy { templates(reader).create(content("nope")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(reader).update(created.id, created.content.copy(name = "x")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(reader).delete(created.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(curator).list() }.describedAs("manage does not imply view").isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(FakeAuthorizationGate.denyAll()).get(created.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(lib.templates.findByKeyAndScope("nope", Scope.Global)).isNull()
    }

    @Test
    fun `project templates need library project manage on that project and are visible to its members only`() {
        val editor = FakeAuthorizationGate.permitting("library.project.manage", projects = setOf(lib.projectId))
        val member = FakeAuthorizationGate(FakeAuthorizationGate.user(projects = mapOf(lib.projectId to setOf("project.view"))))
        val outsider = FakeAuthorizationGate.permitting("library.view", "library.manage", projects = setOf(stranger))

        val mine = templates(editor).create(content("project-one", Scope.Project(lib.projectId)))
        assertThat(editor.checked).containsExactly("library.project.manage@${lib.projectId}")
        templates(FakeAuthorizationGate.permitting("library.manage")).create(content("global-one"))

        assertThat(templates(member).list(lib.projectId).map { it.key }).describedAs("no library.view: project entries only").containsExactly("project-one")
        assertThat(templates(member).get(mine.id).key).isEqualTo("project-one")
        assertThat(templates(outsider).list(lib.projectId).map { it.key }).describedAs("library.view without membership: global only").containsExactly("global-one")
        assertThatThrownBy { templates(outsider).get(mine.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(member).list() }.isInstanceOf(InsufficientPermissionException::class.java)

        assertThatThrownBy { templates(outsider).create(content("x", Scope.Project(lib.projectId))) }
            .describedAs("library.manage does not cover project scope").isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(editor).create(content("x")) }
            .describedAs("project editors cannot touch the global library").isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(editor).update(mine.id, mine.content.copy(scope = Scope.Global)) }
            .describedAs("moving to global needs library.manage").isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { templates(member).delete(mine.id) }.isInstanceOf(InsufficientPermissionException::class.java)

        templates(editor).delete(mine.id)
        assertThat(audit.entries.last().operation).isEqualTo("TEMPLATE_DELETE")
        assertThat(audit.entries.last().projectId).isEqualTo(lib.projectId)
    }

    @Test
    fun `template changes are audited with a field diff and no entry when nothing changed`() {
        val gate = FakeAuthorizationGate.permitting("library.manage")
        lib.skill("s1")
        val created = templates(gate).create(content("audited").copy(prompt = Prompt(system = "12345")))
        val updated = templates(gate).update(created.id, created.content.copy(prompt = Prompt(system = "1234567"), skills = listOf("s1"), extendsKey = null, implements = emptyList()))
        templates(gate).update(updated.id, updated.content)

        assertThat(audit.operations()).containsExactly("TEMPLATE_CREATE", "TEMPLATE_UPDATE")
        val create = audit.entries[0]
        assertThat(create.entityType).isEqualTo("TaskTemplate")
        assertThat(create.entityId).isEqualTo(created.id.toString())
        assertThat(create.details).containsEntry("key", "audited").containsEntry("systemPromptLength", 5)
        val diff = audit.entries[1].details
        assertThat(diff["systemPromptLength"]).isEqualTo(mapOf("old" to 5, "new" to 7))
        assertThat(diff["skills"]).isEqualTo(mapOf("old" to emptyList<String>(), "new" to listOf("s1")))
        assertThat(diff).doesNotContainKeys("name", "extendsKey", "implements").containsEntry("version", 2)
    }

    @Test
    fun `skills follow the scope rules and are audited`() {
        val curator = FakeAuthorizationGate.permitting("library.manage", "library.view")
        val editor = FakeAuthorizationGate.permitting("library.project.manage", projects = setOf(lib.projectId))

        val global = skills(curator).create(Skill.Content(key = "g-skill", name = "G", content = "# g"))
        assertThat(curator.checked).containsExactly("library.manage")
        val local = skills(editor).create(Skill.Content(key = "p-skill", name = "P", content = "# p", scope = Scope.Project(lib.projectId)))
        assertThat(local.updatedBy!!.displayName).isEqualTo("Alice Test")
        skills(curator).update(global.id, Skill.Content(key = "g-skill", name = "G2", content = "# g2"))

        assertThatThrownBy { skills(editor).create(Skill.Content(key = "x", name = "X", content = "# x")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { skills(editor).delete(global.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { skills(FakeAuthorizationGate.denyAll()).list() }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(skills(editor).list(lib.projectId).map { it.key }).containsExactly("p-skill")

        skills(editor).delete(local.id)
        assertThat(audit.operations()).containsExactly("SKILL_CREATE", "SKILL_CREATE", "SKILL_UPDATE", "SKILL_DELETE")
        assertThat(audit.entries[2].details["name"]).isEqualTo(mapOf("old" to "G", "new" to "G2"))
        assertThat(audit.entries[3].projectId).isEqualTo(lib.projectId)
    }

    @Test
    fun `interfaces are global and need library manage to change`() {
        val curator = FakeAuthorizationGate.permitting("library.manage")
        val reader = FakeAuthorizationGate.permitting("library.view")

        val created = interfaces(curator).create(InterfaceCommand("writer", "Writer", outputs = listOf(Library.artifact("doc"))))
        assertThat(curator.checked).containsExactly("library.manage")
        assertThat(created.definition.updatedBy!!.displayName).isEqualTo("Alice Test")
        assertThat(interfaces(reader).list().map { it.definition.key }).containsExactly("writer")
        assertThat(interfaces(reader).get("writer").definition.name).isEqualTo("Writer")
        assertThat(reader.checked).containsOnly("library.view")

        assertThatThrownBy { interfaces(reader).create(InterfaceCommand("x", "X")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { interfaces(reader).update("writer", InterfaceCommand("writer", "W")) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { interfaces(reader).delete("writer") }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { interfaces(FakeAuthorizationGate.permitting("library.project.manage", projects = setOf(lib.projectId))).create(InterfaceCommand("y", "Y")) }
            .isInstanceOf(InsufficientPermissionException::class.java)

        interfaces(curator).update("writer", InterfaceCommand("writer", "Writer 2", outputs = listOf(Library.artifact("doc"))))
        interfaces(curator).delete("writer")
        assertThat(audit.operations()).containsExactly("INTERFACE_CREATE", "INTERFACE_UPDATE", "INTERFACE_DELETE")
        assertThat(audit.entries[1].details["name"]).isEqualTo(mapOf("old" to "Writer", "new" to "Writer 2"))
        assertThat(audit.entries).allSatisfy { assertThat(it.details["key"]).isEqualTo("writer") }
    }

    @Test
    fun `the catalog port used by the workflow engine needs no user permission`() {
        templates(FakeAuthorizationGate.permitting("library.manage")).create(content("engine-task"))
        val catalog = TaskCatalogService(lib.templates, lib.lookup)
        assertThat(catalog.resolve("engine-task", null)!!.key).isEqualTo("engine-task")
        assertThat(catalog.palette(null).map { it.key }).containsExactly("engine-task")
    }
}
