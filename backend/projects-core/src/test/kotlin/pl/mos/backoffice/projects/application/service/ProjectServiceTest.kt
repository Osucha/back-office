package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.alice
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects.ProjectCommand
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.service.CiSnippetRenderer
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

class ProjectServiceTest {

    private val saved = mutableMapOf<pl.mos.backoffice.projects.domain.model.ProjectId, Project>()
    private val projects = mockk<ProjectRepository>()
    private val workflows = mockk<WorkflowDefinitionRepository>()
    private val runs = mockk<WorkflowRunRepository>(relaxed = true)
    private val approvals = mockk<ApprovalRequestRepository>(relaxed = true)
    private val executions = mockk<CiExecutionRepository>(relaxed = true)
    private val tokens = mockk<ProjectCiTokenRepository>(relaxed = true)
    private val audit = RecordingAuditPort()
    private val provisioned = mutableListOf<String>()
    private val provisioner = object : ProjectAccessProvisioner {
        override fun grantCreator(projectId: UUID, projectKey: String, creator: Actor) { provisioned += "owner:$projectKey:${creator.displayName}" }
        override fun projectDeleted(projectId: UUID) { provisioned += "deleted:$projectId" }
        override fun projectRenamed(projectId: UUID, projectKey: String) { provisioned += "renamed:$projectKey" }
    }
    private val seeded = mutableListOf<WorkflowDefinition>()

    init {
        every { projects.save(any()) } answers { firstArg<Project>().also { saved[it.id] = it } }
        every { projects.findById(any()) } answers { saved[firstArg<Any>().let { it as? pl.mos.backoffice.projects.domain.model.ProjectId ?: pl.mos.backoffice.projects.domain.model.ProjectId(it as UUID) }] }
        every { projects.findByKey(any()) } returns null
        every { workflows.save(any()) } answers { firstArg<WorkflowDefinition>().also { seeded += it } }
        every { workflows.findByProjectId(any()) } answers { seeded.toList() }
        every { workflows.deleteByProjectId(any()) } returns Unit
        every { runs.countByProjectAndStatus(any(), any()) } returns 0
    }

    private fun service(gate: FakeAuthorizationGate) = ProjectService(
        projects, workflows, runs, approvals, executions, tokens, mockk(relaxed = true), CiSnippetRenderer(), "http://bo", gate, provisioner, audit, Clock.fixed(now),
    )

    private val command = ProjectCommand(key = "SHOP", name = "Shop", repositories = listOf(GitRepository.legacy("https://git.example.com/g/shop.git", null)), specKit = true)

    @Test
    fun `creating a project needs projects create, makes the creator owner, seeds three workflows and is audited`() {
        val gate = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECTS_CREATE)
        val project = service(gate).create(command)

        assertThat(gate.checked).startsWith("projects.create")
        assertThat(project.createdBy).isEqualTo(alice)
        assertThat(provisioned).containsExactly("owner:SHOP:Alice Test")
        assertThat(seeded.map { it.name }).containsExactly(DefaultWorkflows.ISSUE_TO_REVIEWED_MR, DefaultWorkflows.CI_CODE_REVIEW, DefaultWorkflows.CHANGE_REQUEST)
        assertThat(seeded).allMatch { it.updatedBy == alice }
        assertThat(project.ciEntrypoints.single().key).isEqualTo(CiEntrypoint.CODE_REVIEW)
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("PROJECT_CREATE")
        assertThat(entry.projectId).isEqualTo(project.id.value)
        assertThat(entry.details["key"]).isEqualTo("SHOP")

        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).create(command.copy(key = "OTHER")) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("projects.create") }
    }

    @Test
    fun `the key is generated from the name when omitted and can be changed later, also in the memberships`() {
        every { projects.findByKey("MP") } returns pl.mos.backoffice.projects.Fixtures.project("MP")
        val project = service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECTS_CREATE)).create(command.copy(key = null, name = "Mój projekt"))
        assertThat(project.key).isEqualTo("MP2")
        // nothing implicit about Jira: the project key is not a Jira key
        assertThat(project.jiraProjectKey).isNull()
        audit.entries.clear()

        val manager = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, projects = setOf(project.id.value))
        val renamed = service(manager).update(project.id, command.copy(key = "lfv", name = "Mój projekt"))
        assertThat(renamed.key).isEqualTo("LFV")
        assertThat(provisioned).contains("renamed:LFV")
        assertThat(audit.entries.single().details["key"]).isEqualTo(mapOf("old" to "MP2", "new" to "LFV"))
        // null = unchanged
        assertThat(service(manager).update(project.id, command.copy(key = null, name = "Mój projekt")).key).isEqualTo("LFV")
        every { projects.findByKey("TAKEN") } returns pl.mos.backoffice.projects.Fixtures.project("TAKEN")
        assertThatThrownBy { service(manager).update(project.id, command.copy(key = "TAKEN")) }.isInstanceOf(pl.mos.backoffice.shared.ValidationException::class.java)
    }

    @Test
    fun `suggested keys use initials or the first letters, without diacritics`() {
        val none: (String) -> Boolean = { false }
        assertThat(Project.suggestKey("Łódź", none)).isEqualTo("LODZ")
        assertThat(Project.suggestKey("Zarządzanie zgłoszeniami klienta", none)).isEqualTo("ZZK")
        assertThat(Project.suggestKey("x", none)).isEqualTo("XPR")
        assertThat(Project.suggestKey("123", none)).isEqualTo("PRJ")
    }

    @Test
    fun `a project without repositories gets the LLM starter and the SDLC workflows once it gets its first repository`() {
        val project = service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECTS_CREATE)).create(command.copy(repositories = emptyList()))

        assertThat(project.repositories).isEmpty()
        assertThat(seeded.map { it.name }).containsExactly(DefaultWorkflows.LLM_TASK_WITH_REVIEW)
        assertThat(seeded.single().nodes.filterIsInstance<pl.mos.backoffice.projects.domain.model.WorkflowNode.Task>().single().task.templateKey)
            .isEqualTo(DefaultWorkflows.TEMPLATE_LLM_TASK)
        assertThat(project.ciEntrypoints).isEmpty()
        assertThat(audit.entries.single().details["seededWorkflows"]).isEqualTo(listOf(DefaultWorkflows.LLM_TASK_WITH_REVIEW))
        audit.entries.clear()

        val manager = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, projects = setOf(project.id.value))
        val docs = GitRepository("docs", "https://git.example.com/g/docs.git", purpose = pl.mos.backoffice.projects.domain.model.RepositoryPurpose.DOCS, primary = true)
        val updated = service(manager).update(project.id, command.copy(repositories = listOf(docs)))

        assertThat(updated.repositories.single().key).isEqualTo("docs")
        assertThat(seeded.map { it.name }).containsExactly(
            DefaultWorkflows.LLM_TASK_WITH_REVIEW, DefaultWorkflows.ISSUE_TO_REVIEWED_MR, DefaultWorkflows.CI_CODE_REVIEW, DefaultWorkflows.CHANGE_REQUEST,
        )
        assertThat(updated.ciEntrypoints.single().key).isEqualTo(CiEntrypoint.CODE_REVIEW)
        assertThat(audit.entries.map { it.operation }).containsExactly("PROJECT_UPDATE", "PROJECT_SDLC_SEEDED")
        assertThat(audit.entries.first().details["repositories"]).isEqualTo(
            mapOf("old" to emptyList<String>(), "new" to listOf("docs=https://git.example.com/g/docs.git@main (DOCS, primary)")),
        )

        // a second repository does not seed again
        service(manager).update(project.id, command.copy(repositories = listOf(docs, GitRepository("app", "https://git.example.com/g/app.git"))))
        assertThat(seeded).hasSize(4)
    }

    @Test
    fun `updates need project manage and audit only the changed fields`() {
        val project = service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECTS_CREATE)).create(command)
        audit.entries.clear()
        val manager = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, projects = setOf(project.id.value))

        service(manager).update(project.id, command.copy(name = "Shop 2", constitution = "new rules"))
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("PROJECT_UPDATE")
        assertThat(entry.details).containsOnlyKeys("name", "constitution")
        assertThat(entry.details["name"]).isEqualTo(mapOf("old" to "Shop", "new" to "Shop 2"))

        service(manager).update(project.id, command.copy(name = "Shop 2", constitution = "new rules"))
        assertThat(audit.entries).hasSize(1)

        val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        assertThatThrownBy { service(viewer).update(project.id, command) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("project.manage") }
        assertThatThrownBy { service(viewer).replaceEntrypoints(project.id, emptyList()) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(viewer).delete(project.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(service(viewer).get(project.id).name).isEqualTo("Shop 2")
    }

    @Test
    fun `entrypoint changes and deletion are audited and deletion removes memberships`() {
        val project = service(FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECTS_CREATE)).create(command)
        audit.entries.clear()
        val manager = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, projects = setOf(project.id.value))
        val review = seeded.first { it.name == DefaultWorkflows.CI_CODE_REVIEW }

        service(manager).replaceEntrypoints(project.id, listOf(CiEntrypoint("code-review", review.id, CiMode.ADVISORY)))
        assertThat(audit.operations()).containsExactly("CI_ENTRYPOINTS_UPDATE")

        every { projects.delete(project.id) } returns Unit
        // a maintainer (project.manage without project.members.manage) cannot delete; an owner can
        assertThatThrownBy { service(manager).delete(project.id) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("project.members.manage") }
        val owner = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_MANAGE, ProjectsPermissions.PROJECT_MEMBERS_MANAGE, projects = setOf(project.id.value))
        service(owner).delete(project.id)
        verify { projects.delete(project.id) }
        assertThat(provisioned.last()).isEqualTo("deleted:${project.id}")
        assertThat(audit.operations()).containsExactly("CI_ENTRYPOINTS_UPDATE", "PROJECT_DELETE")
        assertThat(audit.entries.last()).isEqualTo(AuditEntry("PROJECT_DELETE", "Project", project.id.toString(), projectId = project.id.value, details = mapOf("key" to "SHOP", "name" to "Shop")))
    }

    @Test
    fun `the project list only contains projects the caller can view`() {
        val visible = Fixtures.project("SEEN")
        val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(visible.id.value))
        every { projects.findAll(ProjectFilter.Only(setOf(visible.id.value))) } returns listOf(visible)
        assertThat(service(viewer).list()).containsExactly(visible)
        every { projects.findAll(ProjectFilter.Only(emptySet())) } returns emptyList()
        assertThat(service(FakeAuthorizationGate.denyAll()).list()).isEmpty()
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).get(visible.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).stats(visible.id) }.isInstanceOf(InsufficientPermissionException::class.java)
    }
}
