package pl.mos.backoffice.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.platform.application.service.OperationsService
import pl.mos.backoffice.platform.config.PlatformRoles
import pl.mos.backoffice.platform.migrate.MigrateApplication
import pl.mos.backoffice.platform.queue.WorkerLoop
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.EventStream
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.platform"])
class PlatformTestApplication {
    val handled = CopyOnWriteArrayList<WorkItem>()

    @Bean fun gate(): FakeAuthorizationGate = FakeAuthorizationGate.permitting(OperationsService.PERMISSION)

    @Bean fun audit(): RecordingAuditPort = RecordingAuditPort()

    @Bean
    fun echoHandler(): WorkHandler = object : WorkHandler {
        override val kind = "TEST_ECHO"
        override fun handle(item: WorkItem): WorkOutcome {
            handled += item
            return if (item.payload["dead"] == true) WorkOutcome.Dead("asked to die") else WorkOutcome.Done
        }
    }
}

/** The auto-configuration boots with the default roles, runs handler beans, and the operations API is gated. */
@SpringBootTest(
    classes = [PlatformTestApplication::class],
    properties = ["backoffice.platform.worker.poll-interval=100ms"],
)
@AutoConfigureMockMvc
class PlatformContextTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val c = PlatformTestDatabase.container
            registry.add("spring.datasource.url") { c.jdbcUrl }
            registry.add("spring.datasource.username") { c.username }
            registry.add("spring.datasource.password") { c.password }
        }
    }

    @Autowired lateinit var app: PlatformTestApplication
    @Autowired lateinit var queue: WorkQueue
    @Autowired lateinit var worker: WorkerLoop
    @Autowired lateinit var events: EventPublisher
    @Autowired lateinit var stream: EventStream
    @Autowired lateinit var lock: ClusterLock
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var audit: RecordingAuditPort
    @Autowired lateinit var mvc: MockMvc

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline && !condition()) Thread.sleep(20)
        assertThat(condition()).isTrue()
    }

    @Test
    fun `the backbone beans are wired and a handler bean consumes its kind`() {
        assertThat(worker.kinds).contains("TEST_ECHO")
        assertThat(lock).isNotEqualTo(ClusterLock.LOCAL)
        val marker = UUID.randomUUID().toString()
        queue.enqueue(WorkRequest("TEST_ECHO", mapOf("marker" to marker)))
        await { app.handled.any { it.payload["marker"] == marker } }
    }

    @Test
    fun `operations overview, listing and retry of a DEAD item require admin settings permission`() {
        val marker = UUID.randomUUID().toString()
        queue.enqueue(WorkRequest("TEST_ECHO", mapOf("marker" to marker, "dead" to true), dedupeKey = "TEST_ECHO:$marker"))
        await { PlatformTestDatabase.jdbc.sql("SELECT count(*) FROM work_item WHERE dedupe_key = :k AND status = 'DEAD'").param("k", "TEST_ECHO:$marker").query(Long::class.javaObjectType).single() == 1L }
        val id = PlatformTestDatabase.jdbc.sql("SELECT id FROM work_item WHERE dedupe_key = :k").param("k", "TEST_ECHO:$marker").query(UUID::class.java).single()

        mvc.perform(get("/api/v1/admin/operations/overview")).andExpect(status().isOk)
            .andExpect(jsonPath("$.replicas[0].roles[0]").value("api"))
            .andExpect(jsonPath("$.workItems.DEAD").isNumber)
        mvc.perform(get("/api/v1/admin/operations/work-items").param("status", "DEAD").param("kind", "TEST_ECHO")).andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].status").value("DEAD"))
            .andExpect(jsonPath("$.content[0].lastError").value("asked to die"))
        mvc.perform(post("/api/v1/admin/operations/work-items/$id/retry")).andExpect(status().isAccepted)
        assertThat(audit.operations()).contains("WORK_ITEM_RETRY")
        await { app.handled.count { it.payload["marker"] == marker } == 2 }

        val previous = gate.user
        gate.user = FakeAuthorizationGate.user()
        try {
            assertThatThrownBy { mvc.perform(get("/api/v1/admin/operations/overview")) }.hasCauseInstanceOf(InsufficientPermissionException::class.java)
        } finally {
            gate.user = previous
        }
    }

    @Test
    fun `roles parse, default to api and worker, and reject unknown names`() {
        assertThat(PlatformRoles.parse(null)).isEqualTo(PlatformRoles(api = true, worker = true, migrate = false))
        assertThat(PlatformRoles.parse(" Worker ").names).containsExactly("worker")
        assertThat(PlatformRoles.parse("migrate").migrateOnly).isTrue()
        assertThatThrownBy { PlatformRoles.parse("api,scheduler") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the migrate role applies the migrations and closes`() {
        val c = PlatformTestDatabase.container
        val result = MigrateApplication.migrate(
            emptyArray(),
            "spring.datasource.url=${c.jdbcUrl}", "spring.datasource.username=${c.username}", "spring.datasource.password=${c.password}",
        )
        assertThat(result.current).isEqualTo("500")
        assertThat(result.applied).isGreaterThanOrEqualTo(1)
    }
}
