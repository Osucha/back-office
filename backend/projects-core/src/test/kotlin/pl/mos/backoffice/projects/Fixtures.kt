package pl.mos.backoffice.projects

import pl.mos.backoffice.projects.application.port.out.TokenHasher
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Shared fixtures for core unit tests. */
object Fixtures {
    val now: Instant = Instant.parse("2026-09-26T10:00:00Z")

    /** A project with one repository and Spec Kit on (ADR 0036; the seeded issue workflow uses Spec Kit). */
    fun project(key: String = "SHOP", id: ProjectId = ProjectId.new(), specKit: Boolean = true) = Project(
        id = id, key = key, name = "Shop", repositories = listOf(GitRepository.legacy("https://git.example.com/g/shop.git", "main")),
        constitution = "Keep it simple.", defaults = ProjectDefaults(modelProfileId = ModelProfileId.new()), createdAt = now, updatedAt = now,
        specKit = specKit,
    )

    fun issueWorkflow(project: Project): WorkflowDefinition = DefaultWorkflows.issueToReviewedMr(project.id, now)

    fun reviewWorkflow(project: Project): WorkflowDefinition = DefaultWorkflows.ciCodeReview(project.id, now)

    fun runningRun(
        project: Project = project(),
        definition: WorkflowDefinition = issueWorkflow(project),
        input: RunInput = RunInput("SHOP-1"),
        startedBy: Actor? = null,
    ): WorkflowRun = WorkflowRun.create(project, definition, RunTrigger(TriggerSource.MANUAL), input, now, startedBy = startedBy).start(now)

    /** The test user of [FakeAuthorizationGate] as an actor stamp. */
    val alice: Actor = Actor(PrincipalKind.USER, FakeAuthorizationGate.TEST_USER_ID.value, "Alice Test")

    /** Another person, e.g. the one who started a run. */
    val bob: Actor = Actor(PrincipalKind.USER, UUID.fromString("00000000-0000-0000-0000-0000000000b0"), "Bob Other")

    /** A gate for the project's CI token: exactly `ci.execute` and `ci.signal` on [projectId]. */
    fun ciGate(projectId: ProjectId, projectKey: String = "SHOP") = FakeAuthorizationGate(
        CurrentUser(
            kind = PrincipalKind.CI_TOKEN,
            userId = null,
            login = "ci:$projectKey",
            displayName = "CI token gitlab ($projectKey)",
            platformPermissions = emptySet(),
            projectPermissions = mapOf(projectId.value to setOf(ProjectsPermissions.CI_EXECUTE, ProjectsPermissions.CI_SIGNAL)),
        ),
    )

    /** A user holding [permissions] and the project [roles] on [projectId]. */
    fun userWithRoles(projectId: ProjectId, permissions: Set<String>, roles: Set<String>, userId: UserId = FakeAuthorizationGate.TEST_USER_ID) =
        FakeAuthorizationGate.user(projects = mapOf(projectId.value to permissions)).copy(userId = userId, projectRoles = mapOf(projectId.value to roles))

    /** Deterministic stand-in for the adapters' SHA-256 hasher. */
    class TestHasher : TokenHasher {
        private var counter = 0
        override fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        override fun generate(prefix: String, randomLength: Int): String = prefix + (++counter).toString().padStart(randomLength, 'x')
    }
}
