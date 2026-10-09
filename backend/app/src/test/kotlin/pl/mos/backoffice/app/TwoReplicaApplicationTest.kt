package pl.mos.backoffice.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.PostgreSQLContainer
import pl.mos.backoffice.identity.application.port.`in`.ResolveLogin
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.service.TokenCodec
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Two complete application replicas (two Spring contexts, two web servers, two worker loops, two LISTEN
 * bridges) on one PostgreSQL (ADR 0023): an event published on replica A reaches an SSE subscriber of
 * replica B, the login throttle counts attempts of an address across both replicas, and the startup
 * seeding ran under ShedLock.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoReplicaApplicationTest {

    private val db = PostgreSQLContainer("postgres:16-alpine").withDatabaseName("backoffice").withUsername("backoffice").withPassword("backoffice")
    private lateinit var a: ConfigurableApplicationContext
    private lateinit var b: ConfigurableApplicationContext
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun replica(name: String): ConfigurableApplicationContext = SpringApplicationBuilder(BackOfficeApplication::class.java)
        // command-line arguments: they win over application.yml (whose datasource placeholders default to localhost)
        .run(
            "--server.port=0",
            "--spring.datasource.url=${db.jdbcUrl}",
            "--spring.datasource.username=${db.username}",
            "--spring.datasource.password=${db.password}",
            "--spring.flyway.enabled=true",
            "--backoffice.platform.instance-id=$name",
            "--backoffice.runner.mode=DISABLED",
            "--backoffice.auth.bootstrap-admins=boss@example.com",
            "--backoffice.auth.cookie-secure=false",
            "--backoffice.security.runner-token-secret=test-secret",
            "--backoffice.auth.local.secret-key=dGVzdC1vbmx5LWxvY2FsLWF1dGgta2V5LTMyLWJ5dGU=",
            "--backoffice.auth.local.throttle.max-attempts=4",
            "--backoffice.auth.local.throttle.window=PT5M",
        )

    private fun port(ctx: ConfigurableApplicationContext) = (ctx as WebServerApplicationContext).webServer!!.port

    @BeforeAll
    fun start() {
        db.start()
        a = replica("replica-a")
        b = replica("replica-b")
    }

    @AfterAll
    fun stop() {
        runCatching { b.close() }
        runCatching { a.close() }
        db.stop()
    }

    private data class Plain(override val type: String, override val payload: Map<String, Any?>, override val occurredAt: Instant = Instant.now()) : DomainEvent

    private fun bearerOn(ctx: ConfigurableApplicationContext): String {
        val login = ctx.getBean(ResolveLogin::class.java).resolve(ResolveLogin.LoginClaims("https://idp.test", "sub-boss", "boss@example.com", true, "Boss", null))
        val user = (login as ResolveLogin.LoginResult.Success).user
        val generated = TokenCodec.generate()
        ctx.getBean(PersonalTokenRepository::class.java)
            .save(PersonalAccessToken(TokenId.new(), user.id, "two-replicas", generated.hash, generated.prefix, emptySet(), Instant.now().plus(Duration.ofHours(1)), Instant.now()))
        return "Bearer " + generated.plaintext
    }

    @Test
    fun `an event published on replica A reaches an SSE subscriber on replica B through LISTEN NOTIFY`() {
        val bearer = bearerOn(b)
        val marker = UUID.randomUUID().toString()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${port(b)}/api/v1/events"))
            .header("Authorization", bearer).header("Accept", "text/event-stream").GET().build()
        val lines = http.send(request, HttpResponse.BodyHandlers.ofLines())
        assertThat(lines.statusCode()).isEqualTo(200)
        val seen = CompletableFuture.supplyAsync { lines.body().filter { it.contains(marker) }.findFirst().orElse(null) }

        // publish until the subscription is surely registered on B (the first publish may race with it)
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!seen.isDone && System.nanoTime() < deadline) {
            a.getBean(EventPublisher::class.java).publish(Plain("TwoReplicaProbe", mapOf("marker" to marker)))
            runCatching { seen.get(500, TimeUnit.MILLISECONDS) }
        }
        val line = seen.get(1, TimeUnit.SECONDS)
        assertThat(line).describedAs("SSE data line on replica B").contains("TwoReplicaProbe").contains(marker)
        val stored = JdbcClient.create(a.getBean(DataSource::class.java)).sql("SELECT count(*) FROM event_log WHERE payload->>'marker' = :m")
            .param("m", marker).query(Long::class.javaObjectType).single()
        assertThat(stored).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `the login throttle counts the attempts of one address across both replicas`() {
        val statuses = (1..6).map { i ->
            val target = if (i % 2 == 0) b else a
            val request = HttpRequest.newBuilder(URI.create("http://localhost:${port(target)}/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"email":"nobody@example.com","password":"wrong-password-123"}""")).build()
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
        // 4 attempts allowed in total (2 per replica), the 5th and 6th are refused whichever replica they hit
        assertThat(statuses.take(4)).allSatisfy { assertThat(it).isNotEqualTo(429) }
        assertThat(statuses.drop(4)).containsOnly(429)
    }

    @Test
    fun `startup seeding and the registry ran under the cluster lock`() {
        val jdbc = JdbcClient.create(a.getBean(DataSource::class.java))
        val locks = jdbc.sql("SELECT name FROM shedlock").query(String::class.java).list()
        assertThat(locks).contains("identity-seed", "tasks-seed", "evaluation-seed")
        val instances = jdbc.sql("SELECT id FROM platform_instance").query(String::class.java).list()
        assertThat(instances).contains("replica-a", "replica-b")
    }
}
