package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.alice
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.LibraryDetailsCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.PublishCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.WorkflowCommand
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowHeader
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowRepository
import pl.mos.backoffice.projects.application.port.out.LinkedWorkflow
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowStateView
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.LibraryWorkflow
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort

class WorkflowLibraryServiceTest {

    private val shop = Fixtures.project("SHOP")
    private val blog = Fixtures.project("BLOG")
    private val hidden = Fixtures.project("HIDDEN")

    private val store = linkedMapOf<WorkflowDefinitionId, WorkflowDefinition>()
    private val workflows = mockk<WorkflowDefinitionRepository>()
    private val projects = mockk<ProjectRepository>()
    private val runs = mockk<WorkflowRunRepository> { every { existsActiveByWorkflow(any()) } returns false }
    private val library = InMemoryLibrary()
    private val audit = RecordingAuditPort()
    private val expressions = object : ExpressionEvaluator {
        override fun validate(expression: String): String? = null
        override fun evaluate(expression: String, view: WorkflowStateView): Boolean = true
    }
    private val catalog = mockk<TaskCatalog> {
        every { resolve(any(), any(), any()) } answers {
            val key = firstArg<String>()
            if (key == "missing") null else ResolvedTask(key, key, TaskExecutor.LLM, null, TaskPrompt(null, null), emptyList(), emptyMap(), null, null, emptyList(), emptyList(), emptyList(), listOf(key), listOf("success", "failure"))
        }
        every { palette(any()) } answers {
            val projectId = firstArg<java.util.UUID?>()
            listOf(TaskSummary("llm-task", "LLM task", "", TaskExecutor.LLM, emptyList(), false, null, listOf("success", "failure"))) +
                if (projectId == shop.id.value) listOf(TaskSummary("shop-only", "Shop only", "", TaskExecutor.LLM, emptyList(), false, projectId, listOf("success", "failure"))) else emptyList()
        }
    }

    /** Library manager who edits workflows in SHOP and BLOG and sees HIDDEN only through the library. */
    private val gate = FakeAuthorizationGate(
        FakeAuthorizationGate.user(
            platform = setOf(ProjectsPermissions.LIBRARY_VIEW, ProjectsPermissions.LIBRARY_MANAGE),
            projects = listOf(shop, blog).associate { it.id.value to setOf(ProjectsPermissions.WORKFLOW_EDIT, ProjectsPermissions.PROJECT_VIEW) },
        ),
    )

    init {
        listOf(shop, blog, hidden).forEach { p -> every { projects.findById(p.id) } returns p }
        every { projects.findAll(any()) } answers { listOf(shop, blog, hidden).filter { firstArg<ProjectFilter>().allows(it.id.value) } }
        every { workflows.save(any()) } answers { firstArg<WorkflowDefinition>().also { store[it.id] = it } }
        every { workflows.findById(any()) } answers { store[WorkflowDefinitionId(uuid(firstArg()))] }
        every { workflows.findByProjectId(any()) } answers { store.values.filter { it.projectId.value == uuid(firstArg()) } }
        every { workflows.findLinkedToLibrary(any()) } answers {
            val id = firstArg<Any?>()?.let(::uuid)
            store.values.filter { it.library != null && (id == null || it.library!!.libraryWorkflowId.value == id) }
                .map { LinkedWorkflow(it.id, it.projectId, it.name, it.enabled, it.library!!) }
        }
    }

    /** mockk may pass inline value classes boxed or unboxed. */
    private fun uuid(arg: Any): java.util.UUID = when (arg) {
        is WorkflowDefinitionId -> arg.value
        is pl.mos.backoffice.projects.domain.model.ProjectId -> arg.value
        is LibraryWorkflowId -> arg.value
        else -> arg as java.util.UUID
    }

    private val workflowService = WorkflowService(workflows, projects, runs, WorkflowValidator(), catalog, expressions, gate, audit, Clock.fixed(now), library = library)
    private val service = WorkflowLibraryService(library, workflows, projects, workflowService, catalog, gate, audit, Clock.fixed(now))

    private fun graph(template: String = "llm-task", prompt: String = "Summarise", config: Map<String, Any?> = emptyMap()) = WorkflowCommand(
        name = "Summarise issue",
        description = "One LLM step",
        nodes = listOf(
            WorkflowNode.Start("start"),
            WorkflowNode.Task("summary", "Summary", task = TaskBinding(template, TaskOverrides(promptReplace = prompt, config = config))),
            WorkflowNode.End("done", "Done", end = EndSpec(RunOutcome.SUCCESS)),
        ),
        edges = listOf(WorkflowEdge("e1", "start", "out", "summary"), WorkflowEdge("e2", "summary", "success", "done")),
    )

    private val shopRepos = mapOf("repositories" to listOf(mapOf("key" to "api", "access" to "READ")))

    private fun publishShop(): Pair<WorkflowDefinition, LibraryWorkflowId> {
        val source = workflowService.create(shop.id, graph(config = shopRepos))
        val published = service.publish(source.id, PublishCommand(key = "summarise-issue", tags = listOf("LLM", " llm ", "Docs"), changeNote = "first"))
        return store.getValue(source.id) to published.workflow.id
    }

    @Test
    fun `publishing strips project-local settings, links the source and is audited`() {
        val (source, id) = publishShop()

        val entry = library.findById(id)!!
        assertThat(entry.key).isEqualTo("summarise-issue")
        assertThat(entry.name).isEqualTo("Summarise issue")
        assertThat(entry.tags).containsExactly("llm", "docs")
        val v1 = entry.latest
        assertThat(v1.version).isEqualTo(1)
        assertThat(v1.changeNote).isEqualTo("first")
        assertThat(v1.publishedBy).isEqualTo(alice)
        assertThat(v1.source.projectKey).isEqualTo("SHOP")
        assertThat((v1.nodes[1] as WorkflowNode.Task).task.overrides.config).doesNotContainKey("repositories")
        assertThat(source.library!!.version).isEqualTo(1)
        assertThat(source.library!!.modified).isFalse()
        assertThat(audit.operations()).endsWith("LIBRARY_WORKFLOW_PUBLISH")
        assertThat(gate.checked).contains("workflow.edit@${shop.id}", "library.manage")
    }

    @Test
    fun `adopt, change locally, take a new version keeping local settings, then detach`() {
        val (source, id) = publishShop()

        val copy = service.adopt(blog.id, id)
        assertThat(copy.projectId).isEqualTo(blog.id)
        assertThat(copy.library).isNotNull
        assertThat(copy.library!!.version).isEqualTo(1)
        assertThat(copy.updatedBy).isEqualTo(alice)
        assertThat(service.get(id).adoptions.map { it.projectKey }).containsExactlyInAnyOrder("SHOP", "BLOG")

        // a project-local setting does not make the copy "modified"; a prompt change does
        val blogRepos = mapOf("repositories" to listOf(mapOf("key" to "web", "access" to "PUSH_MR")))
        val local = workflowService.update(copy.id, graph(config = blogRepos).copy(name = copy.name))
        assertThat(local.library!!.modified).isFalse()
        val edited = workflowService.update(copy.id, graph(prompt = "Summarise briefly", config = blogRepos).copy(name = copy.name))
        assertThat(edited.library!!.modified).isTrue()

        workflowService.update(source.id, graph(prompt = "Summarise with risks", config = shopRepos))
        val v2 = service.publish(source.id, PublishCommand(libraryWorkflowId = id, changeNote = "risks"))
        assertThat(v2.workflow.latestVersion).isEqualTo(2)
        assertThat(v2.workflow.tags).containsExactly("llm", "docs")

        assertThatThrownBy { service.updateFromLibrary(copy.id) }.isInstanceOf(InvalidStateException::class.java)
        val updated = service.updateFromLibrary(copy.id, discardLocalChanges = true)
        assertThat(updated.library!!.version).isEqualTo(2)
        assertThat(updated.library!!.modified).isFalse()
        assertThat(updated.version).isEqualTo(edited.version + 1)
        val task = updated.nodes[1] as WorkflowNode.Task
        assertThat(task.task.overrides.promptReplace).isEqualTo("Summarise with risks")
        assertThat(task.task.overrides.config["repositories"]).isEqualTo(blogRepos["repositories"])

        val detached = service.detach(copy.id)
        assertThat(detached.library).isNull()
        assertThat(audit.operations()).contains("WORKFLOW_ADOPT", "WORKFLOW_LIBRARY_UPDATE", "WORKFLOW_LIBRARY_DETACH")
    }

    @Test
    fun `publishing an unchanged graph as a new version is refused`() {
        val (source, id) = publishShop()
        assertThatThrownBy { service.publish(source.id, PublishCommand(libraryWorkflowId = id)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a duplicate key and project-scoped or unknown templates cannot be published`() {
        publishShop()
        val other = workflowService.create(shop.id, graph().copy(name = "Other"))
        assertThatThrownBy { service.publish(other.id, PublishCommand(key = "summarise-issue")) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.errors.single().field).isEqualTo("key") }

        val scoped = workflowService.create(shop.id, graph(template = "shop-only").copy(name = "Scoped"))
        assertThatThrownBy { service.publish(scoped.id, PublishCommand(key = "scoped")) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.errors.single().field).isEqualTo("summary.task.templateKey") }
    }

    @Test
    fun `curating needs library manage, deprecated entries cannot be adopted, linked entries cannot be deleted`() {
        val (_, id) = publishShop()
        val reader = FakeAuthorizationGate.permitting(ProjectsPermissions.LIBRARY_VIEW, ProjectsPermissions.WORKFLOW_EDIT, projects = setOf(blog.id.value))
        val readOnly = WorkflowLibraryService(library, workflows, projects, workflowService, catalog, reader, audit, Clock.fixed(now))
        assertThat(readOnly.list().map { it.workflow.key }).containsExactly("summarise-issue")
        assertThatThrownBy { readOnly.update(id, LibraryDetailsCommand("x")) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("library.manage") }

        service.update(id, LibraryDetailsCommand("Summarise", "desc", listOf("a"), deprecated = true))
        assertThat(service.list(includeDeprecated = false)).isEmpty()
        assertThatThrownBy { service.adopt(blog.id, id) }.isInstanceOf(InvalidStateException::class.java)

        assertThatThrownBy { service.delete(id) }.isInstanceOf(InvalidStateException::class.java)
        store.values.filter { it.library != null }.forEach { service.detach(it.id) }
        service.delete(id)
        assertThat(library.findById(id)).isNull()
        assertThat(audit.operations()).contains("LIBRARY_WORKFLOW_UPDATE", "LIBRARY_WORKFLOW_DELETE")
    }

    @Test
    fun `adoptions in projects the caller cannot view are not listed`() {
        val (_, id) = publishShop()
        val inHidden = store.values.first().copy(id = WorkflowDefinitionId.new(), projectId = hidden.id)
        store[inHidden.id] = inHidden
        val view = service.get(id)
        assertThat(view.adoptions.map { it.projectKey }).containsExactly("SHOP")
        assertThat(view.projectCount).isEqualTo(1)
        assertThat(service.linkStatus(listOf(id)).getValue(id).latestVersion).isEqualTo(1)
    }

    /** Library store with immutable versions, like the persistence adapter. */
    private class InMemoryLibrary : LibraryWorkflowRepository {
        private val items = linkedMapOf<LibraryWorkflowId, LibraryWorkflow>()
        override fun save(workflow: LibraryWorkflow): LibraryWorkflow = workflow.also { items[it.id] = it }
        override fun findById(id: LibraryWorkflowId): LibraryWorkflow? = items[id]
        override fun findByKey(key: String): LibraryWorkflow? = items.values.firstOrNull { it.key == key }
        override fun findAll(): List<LibraryWorkflow> = items.values.toList()
        override fun delete(id: LibraryWorkflowId) {
            items.remove(id)
        }
        override fun findHeaders(ids: Collection<LibraryWorkflowId>): List<LibraryWorkflowHeader> =
            ids.mapNotNull { items[it] }.map { LibraryWorkflowHeader(it.id, it.key, it.name, it.latestVersion, it.deprecated) }
    }
}
