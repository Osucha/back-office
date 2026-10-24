package pl.mos.backoffice.tasks.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
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
import pl.mos.backoffice.tasks.support.Library.Companion.artifact
import java.util.UUID

class LibraryServicesTest {

    private val lib = Library()
    private val other: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /** A library curator who also edits the library of two projects; access rules are covered in LibraryAccessTest. */
    private val gate = FakeAuthorizationGate.permitting(
        TasksPermissions.LIBRARY_VIEW, TasksPermissions.LIBRARY_MANAGE, TasksPermissions.LIBRARY_PROJECT_MANAGE,
        projects = setOf(lib.projectId, other),
    )
    private val audit = RecordingAuditPort()
    private val interfaces = TaskInterfaceService(lib.interfaces, lib.templates, lib.clock, gate, audit)
    private val templates = TaskTemplateService(lib.templates, lib.lookup, lib.clock, gate, audit)
    private val skills = SkillService(lib.skills, lib.templates, lib.clock, gate, audit)
    private val catalog = TaskCatalogService(lib.templates, lib.lookup)

    private fun content(key: String, scope: Scope = Scope.Global, extendsKey: String? = null, executor: TaskExecutor? = TaskExecutor.LLM, abstract: Boolean = false) =
        TaskTemplate.Content(key = key, name = key, scope = scope, extendsKey = extendsKey, executor = executor, abstract = abstract)

    @Test
    fun `interfaces are unique by key, list their implementers and refuse deletion while implemented`() {
        val created = interfaces.create(InterfaceCommand("reviewer", "Reviewer", inputs = listOf(artifact("diff")), outputs = listOf(artifact("review"))))
        assertThat(created.implementedBy).isEmpty()
        assertThatThrownBy { interfaces.create(InterfaceCommand("reviewer", "Again")) }.isInstanceOf(InvalidStateException::class.java)

        templates.create(content("review").copy(implements = listOf("reviewer"), consumes = listOf("diff"), produces = listOf("review")))
        assertThat(interfaces.get("reviewer").implementedBy).containsExactly("review")
        assertThat(interfaces.list().single().implementedBy).containsExactly("review")

        assertThatThrownBy { interfaces.delete("reviewer") }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("review")
        assertThatThrownBy { interfaces.update("reviewer", InterfaceCommand("renamed", "Reviewer")) }.isInstanceOf(InvalidStateException::class.java)

        val updated = interfaces.update("reviewer", InterfaceCommand("reviewer", "Strict reviewer", ports = listOf("success", "failure", "changes")))
        assertThat(updated.definition.name).isEqualTo("Strict reviewer")
        assertThat(updated.definition.ports).containsExactly("success", "failure", "changes")

        templates.delete(templates.list().single().id)
        interfaces.delete("reviewer")
        assertThatThrownBy { interfaces.get("reviewer") }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `templates validate on save, bump version on update and refuse built-in or extended deletion`() {
        assertThatThrownBy { templates.create(content("child", extendsKey = "ghost")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { templates.create(content("bare", executor = null)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("executor")

        val base = templates.create(content("base", executor = TaskExecutor.LLM, abstract = true).copy(prompt = Prompt(system = "base")))
        assertThat(base.version).isEqualTo(1)
        assertThatThrownBy { templates.create(content("base")) }.isInstanceOf(InvalidStateException::class.java)

        val child = templates.create(content("child", extendsKey = "base", executor = null))
        val updated = templates.update(child.id, child.content.copy(name = "Child v2"))
        assertThat(updated.version).isEqualTo(2)
        assertThat(updated.name).isEqualTo("Child v2")

        assertThatThrownBy { templates.update(base.id, base.content.copy(extendsKey = "child")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("cycle")
        assertThatThrownBy { templates.delete(base.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("extended")

        lib.templates.save(child.copy(builtIn = true))
        assertThatThrownBy { templates.delete(child.id) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("built in")
        assertThatThrownBy { templates.get(pl.mos.backoffice.tasks.domain.model.TaskTemplateId.new()) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `run inputs are saved, returned, audited on change and inherited in the resolved view`() {
        val input = RunInputField("input", "Input material", RunInputType.MULTILINE)
        val issue = RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true, help = "e.g. ABC-1")
        val base = templates.create(content("base", abstract = true).copy(runInputs = listOf(input)))
        val child = templates.create(content("child", extendsKey = "base", executor = null).copy(runInputs = listOf(issue)))
        assertThat(templates.get(base.id).runInputs).containsExactly(input)
        assertThat(audit.entries.last().details["runInputs"]).isEqualTo(listOf("issueKey"))

        val updated = templates.update(child.id, child.content.copy(runInputs = listOf(issue.copy(required = false))))

        assertThat(templates.get(child.id).runInputs).containsExactly(issue.copy(required = false))
        assertThat(updated.version).isEqualTo(2)
        val change = audit.entries.last()
        assertThat(change.operation).isEqualTo("TEMPLATE_UPDATE")
        assertThat(change.details).containsEntry("runInputsChanged", mapOf("old" to false, "new" to true))
        assertThat(templates.resolve("child").runInputs).containsExactly(input, issue.copy(required = false))
        assertThat(catalog.resolve("child", null)!!.runInputs).containsExactly(input, issue.copy(required = false))
    }

    @Test
    fun `list returns global plus the project templates and can hide abstract ones`() {
        templates.create(content("base", abstract = true))
        templates.create(content("review"))
        templates.create(content("review", scope = Scope.Project(lib.projectId)))
        templates.create(content("secret", scope = Scope.Project(other)))

        assertThat(templates.list().map { it.key }).containsExactly("base", "review")
        assertThat(templates.list(lib.projectId).map { it.key to it.scope.projectId }).containsExactly("base" to null, "review" to null, "review" to lib.projectId)
        assertThat(templates.list(lib.projectId, includeAbstract = false).map { it.key }).containsExactly("review", "review")
    }

    @Test
    fun `resolve previews abstract and broken templates and is 404 for unknown keys`() {
        lib.iface("reviewer", outputs = listOf(artifact("review")))
        templates.create(content("base", abstract = true).copy(prompt = Prompt(system = "hello")))
        templates.create(content("half", extendsKey = "base", executor = null).copy(implements = listOf("reviewer")))

        assertThat(templates.resolve("base").abstract).isTrue()
        assertThat(templates.resolve("base").prompt.system).isEqualTo("hello")
        val half = templates.resolve("half")
        assertThat(half.interfaceCheck.ok).isFalse()
        assertThat(half.interfaceCheck.problems).containsExactly("interface reviewer: output review is not in produces")
        assertThatThrownBy { templates.resolve("nope") }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `skills are unique per scope and cannot be deleted or renamed while used`() {
        val skill = skills.create(Skill.Content(key = "ss", name = "S", content = "# s"))
        assertThatThrownBy { skills.create(Skill.Content(key = "ss", name = "S", content = "# s")) }.isInstanceOf(InvalidStateException::class.java)
        val mine = skills.create(Skill.Content(key = "ss", name = "Mine", content = "# mine", scope = Scope.Project(lib.projectId)))

        assertThat(skills.list().map { it.id }).containsExactly(skill.id)
        assertThat(skills.list(lib.projectId).map { it.id }).containsExactly(skill.id, mine.id)

        templates.create(content("tt").copy(skills = listOf("ss")))
        assertThatThrownBy { skills.delete(skill.id) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { skills.update(skill.id, Skill.Content(key = "renamed", name = "S", content = "# s")) }.isInstanceOf(InvalidStateException::class.java)
        assertThat(skills.update(skill.id, Skill.Content(key = "ss", name = "S2", content = "# s2")).name).isEqualTo("S2")

        assertThatThrownBy { skills.delete(mine.id) }.describedAs("the reference check is by key, whatever the scope").isInstanceOf(InvalidStateException::class.java)

        templates.delete(templates.list().single().id)
        skills.delete(mine.id)
        skills.delete(skill.id)
        assertThatThrownBy { skills.get(mine.id) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `the catalog palette lists concrete templates once per key with the project entry winning`() {
        lib.iface("reviewer", ports = listOf("success", "failure", "changes"))
        templates.create(content("base", abstract = true))
        templates.create(content("review", extendsKey = "base", executor = TaskExecutor.LLM).copy(implements = listOf("reviewer"), name = "Global review"))
        templates.create(content("review", scope = Scope.Project(lib.projectId)).copy(name = "Project review"))
        templates.create(content("broken").copy(extendsKey = null))
        lib.templates.save(lib.templates.findByKeyAndScope("broken", Scope.Global)!!.copy(extendsKey = "ghost"))

        val global = catalog.palette(null)
        assertThat(global.map { it.key }).containsExactly("review")
        assertThat(global.single().name).isEqualTo("Global review")
        assertThat(global.single().ports).containsExactly("success", "failure", "changes")
        assertThat(global.single().interfaces).containsExactly("reviewer")

        val mine = catalog.palette(lib.projectId).single()
        assertThat(mine.name).isEqualTo("Project review")
        assertThat(mine.projectId).isEqualTo(lib.projectId)

        assertThat(catalog.resolve("review", lib.projectId)!!.name).isEqualTo("Project review")
        assertThat(catalog.resolve("nope", null)).isNull()
        assertThatThrownBy { catalog.resolve("base", null) }.isInstanceOf(ValidationException::class.java)
    }
}
