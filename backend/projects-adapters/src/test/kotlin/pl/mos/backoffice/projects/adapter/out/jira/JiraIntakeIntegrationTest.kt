package pl.mos.backoffice.projects.adapter.out.jira

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.projects.adapter.ConcurrentRecordingAuditPort
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.adapter.TestAuthorizationGate
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageJiraIntake
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.PollJiraIntake
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.out.JiraIntakeRepository
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.IssueTrackerBinding
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import java.time.Duration
import java.util.UUID

/**
 * ADR 0033 end to end with WireMock as Jira (`/rest/api/3/search/jql`) and PostgreSQL: the watcher polls with the
 * reader connection only, takes a baseline, starts one run per transition (dedupe persisted across polls), keeps its
 * cursor, and starts nothing with no reader.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class JiraIntakeIntegrationTest {

    @Autowired lateinit var projects: ManageProjects
    @Autowired lateinit var workflows: ManageWorkflows
    @Autowired lateinit var connections: ManageConnections
    @Autowired lateinit var intake: ManageJiraIntake
    @Autowired lateinit var poll: PollJiraIntake
    @Autowired lateinit var intakes: JiraIntakeRepository
    @Autowired lateinit var runs: RunQueries
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var gate: TestAuthorizationGate
    @Autowired lateinit var audit: ConcurrentRecordingAuditPort

    private val jira = WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true)).also { it.start() }

    @AfterEach
    fun stop() = jira.stop()

    private fun key() = "INT" + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

    private fun search(vararg issues: Pair<String, String>) {
        val body = issues.joinToString(",", prefix = """{"issues":[""", postfix = """],"isLast":true}""") { (key, historyId) ->
            """{"key":"$key","fields":{"status":{"name":"Ready for AI"},"updated":"2026-10-08T12:00:00.000+0000"},
               "changelog":{"histories":[{"id":"$historyId","created":"2026-10-08T11:59:00.000+0000","items":[{"field":"status","toString":"Ready for AI"}]}]}}"""
        }
        jira.stubFor(get(urlPathEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)))
    }

    private fun backdateCursor(projectId: pl.mos.backoffice.projects.domain.model.ProjectId) {
        val current = intakes.find(projectId)!!
        intakes.save(current.copy(cursor = current.cursor!!.minus(Duration.ofMinutes(5)), nextPollAt = java.time.Instant.now().plus(Duration.ofHours(1))))
    }

    @Test
    fun `polls with the reader connection, starts one run per transition and remembers what it started`() {
        jira.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(404)))
        val projectKey = key()
        val project = projects.create(
            ManageProjects.ProjectCommand(
                key = projectKey, name = "Intake", repositories = listOf(GitRepository.legacy("https://git.example.com/g/i.git", "main")),
                issueTracker = IssueTrackerBinding(projectKey, "labels = ai"), defaults = ProjectDefaults(modelProfileId = catalog.add()),
            ),
        )
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "from jira",
                nodes = listOf(WorkflowNode.Start("start"), WorkflowNode.HumanGate("gate", "Go?", gate = GateSpec("Go {{input.issueKey}}?")), WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS))),
                edges = listOf(WorkflowEdge("e1", "start", "out", "gate"), WorkflowEdge("e2", "gate", "approve", "ok")),
                triggers = listOf(Trigger(TriggerType.JIRA_TRANSITION, "Ready for AI")),
            ),
        )
        intake.update(project.id, ManageJiraIntake.IntakeCommand(enabled = true, pollIntervalMinutes = 1, workflowId = workflow.id))

        // no reader connection: the watcher refuses and calls nothing
        assertThat(gate.asSystem("test") { poll.poll(project.id) }).isZero()
        assertThat(intakes.find(project.id)!!.lastError).startsWith("refused:")
        assertThat(jira.findAll(getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql")))).isEmpty()

        connections.create(project.id, ManageConnections.ConnectionCommand(ConnectionType.JIRA, "reader", jira.baseUrl(), "reader@acme.com", "reader-token", setOf(ConnectionCapability.ISSUE_READ), acknowledgeClassicToken = true))

        // baseline
        assertThat(gate.asSystem("test") { poll.poll(project.id) }).isZero()
        val baseline = intakes.find(project.id)!!
        assertThat(baseline.cursor).isNotNull()
        assertThat(baseline.lastError).isNull()

        // two transitions -> two runs, started by the watcher with trigger JIRA
        backdateCursor(project.id)
        search("$projectKey-1" to "101", "$projectKey-2" to "102")
        assertThat(gate.asSystem("test") { poll.poll(project.id) }).isEqualTo(2)
        jira.verify(
            getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("jql", matching("project = \"$projectKey\" AND \\(labels = ai\\) AND status CHANGED TO \"Ready for AI\" AFTER -\\d+m ORDER BY updated ASC"))
                .withQueryParam("fields", matching("status,updated,created")),
        )

        // the same transitions again (overlapping window) start nothing; a new one starts one more run
        backdateCursor(project.id)
        search("$projectKey-1" to "101", "$projectKey-2" to "102", "$projectKey-1" to "150")
        assertThat(gate.asSystem("test") { poll.poll(project.id) }).isEqualTo(1)

        val started = runs.list(RunFilter(projectId = project.id), 0, 50).content
        assertThat(started).hasSize(3)
        assertThat(started.map { it.trigger.type }).containsOnly(TriggerSource.JIRA)
        assertThat(started.map { it.issueKey }).containsExactlyInAnyOrder("$projectKey-1", "$projectKey-2", "$projectKey-1")
        assertThat(started.map { it.startedBy?.displayName }).containsOnly("Jira intake watcher")
        assertThat(audit.entries.filter { it.operation == "RUN_START" && it.projectId == project.id.value }.map { it.details["trigger"] }).containsOnly("JIRA")

        val view = intake.get(project.id)
        assertThat(view.intake.issuesStarted).isEqualTo(3)
        assertThat(view.intake.lastConnection).isEqualTo("reader")
        assertThat(view.connection.connection!!.name).isEqualTo("reader")
        assertThat(view.problems).isEmpty()
    }
}
