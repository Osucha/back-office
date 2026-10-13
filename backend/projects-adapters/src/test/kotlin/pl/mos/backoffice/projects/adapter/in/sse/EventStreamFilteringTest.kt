package pl.mos.backoffice.projects.adapter.`in`.sse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.adapter.TestAuthorizationGate
import pl.mos.backoffice.projects.adapter.`in`.rest.DomainEventEnvelopeDto
import pl.mos.backoffice.projects.domain.event.NodeStarted
import pl.mos.backoffice.projects.domain.event.RunnerLog
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * The SSE stream delivers an event to a subscriber only when the subscriber may view the event's project;
 * events without a project go to everyone, unparseable project ids to nobody.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@AutoConfigureMockMvc
@Import(ProjectsTestBeans::class)
class EventStreamFilteringTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var stream: EventStreamController
    @Autowired lateinit var directory: pl.mos.backoffice.projects.adapter.FakeProjectAccessDirectory

    private data class Plain(override val type: String, override val payload: Map<String, Any?>, override val occurredAt: Instant = Instant.now()) : DomainEvent

    private val ids = java.util.concurrent.atomic.AtomicLong(1_000_000)

    /** What the platform event stream hands over after the outbox round trip (payload values as JSON types). */
    private fun stored(event: DomainEvent) = pl.mos.backoffice.shared.platform.StoredEvent(
        ids.incrementAndGet(), event.type, null, event.occurredAt, event.payload.mapValues { (_, v) -> v?.toString() },
    )

    @Test
    fun `a subscriber only receives events of projects it can view`() {
        val visible = ProjectId.new()
        val hidden = ProjectId.new()
        val seenRun = WorkflowRunId.new()
        val hiddenRun = WorkflowRunId.new()
        val result = mvc.perform(get("/api/v1/events").header("X-Test-Project-Permissions", "$visible=${ProjectsPermissions.PROJECT_VIEW}"))
            .andExpect(request().asyncStarted())
            .andReturn()

        stream.onEvent(stored(NodeStarted(seenRun, visible, "start", "START", 1, Instant.now())))
        stream.onEvent(stored(NodeStarted(hiddenRun, hidden, "start", "START", 1, Instant.now())))
        stream.onEvent(stored(RunnerLog(hiddenRun, hidden, "impl", null, "INFO", "secret log line", Instant.now())))
        stream.onEvent(stored(Plain("BenchmarkProgress", mapOf("benchmarkId" to "b-1"))))

        val body = result.response.contentAsString
        assertThat(body).contains(seenRun.toString()).contains("BenchmarkProgress")
        assertThat(body).doesNotContain(hiddenRun.toString()).doesNotContain("secret log line")
    }

    @Test
    fun `access granted after subscribing (a project created later) is picked up without reconnecting`() {
        val earlier = ProjectId.new()
        val created = ProjectId.new()
        val run = WorkflowRunId.new()
        val result = mvc.perform(get("/api/v1/events").header("X-Test-Project-Permissions", "$earlier=${ProjectsPermissions.PROJECT_VIEW}"))
            .andExpect(request().asyncStarted())
            .andReturn()
        // the same person now also owns a project created after the stream opened
        directory.register(TestAuthorizationGate.member(created.value, setOf(ProjectsPermissions.PROJECT_VIEW)))

        stream.onEvent(stored(NodeStarted(run, created, "start", "START", 1, Instant.now())))

        assertThat(result.response.contentAsString).contains(run.toString())
    }

    @Test
    fun `the delivery rule fails closed on unparseable project ids`() {
        val project = UUID.randomUUID()
        val member = TestAuthorizationGate.member(project, setOf(ProjectsPermissions.PROJECT_VIEW))
        fun envelope(payload: Map<String, Any?>) = DomainEventEnvelopeDto("1", "X", Instant.now(), payload)
        assertThat(EventStreamController.visible(member, envelope(mapOf("projectId" to project.toString())))).isTrue()
        assertThat(EventStreamController.visible(member, envelope(mapOf("projectId" to UUID.randomUUID().toString())))).isFalse()
        assertThat(EventStreamController.visible(member, envelope(mapOf("projectId" to null)))).isTrue()
        assertThat(EventStreamController.visible(member, envelope(emptyMap()))).isTrue()
        assertThat(EventStreamController.visible(member, envelope(mapOf("projectId" to "not-a-uuid")))).isFalse()
        assertThat(EventStreamController.visible(TestAuthorizationGate.OWNER, envelope(mapOf("projectId" to UUID.randomUUID().toString())))).isTrue()
    }

    @Test
    fun `events about a personal connection reach only its owner (ADR 0035)`() {
        fun envelope(payload: Map<String, Any?>) = DomainEventEnvelopeDto("1", "ConnectionExpiring", Instant.now(), payload)
        val personal = envelope(mapOf("projectId" to null, "scope" to "USER", "ownerUserId" to TestAuthorizationGate.OWNER_ID.toString()))
        assertThat(EventStreamController.visible(TestAuthorizationGate.OWNER, personal)).isTrue()
        assertThat(EventStreamController.visible(TestAuthorizationGate.platformUser(setOf(ProjectsPermissions.CONNECTIONS_MANAGE)), personal)).isFalse()
    }
}
