package pl.mos.backoffice.runnercontroller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import pl.mos.backoffice.runnercontroller.egress.EgressRegistry
import pl.mos.backoffice.runnercontroller.egress.EgressToken
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["runner-controller.enabled=false", "runner-controller.egress.token=test-egress-token"],
)
class RunnerControllerApplicationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var registry: EgressRegistry

    @Autowired
    lateinit var token: EgressToken

    private val http = HttpClient.newHttpClient()

    private fun get(path: String, auth: String? = null, etag: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        auth?.let { b.header("Authorization", it) }
        etag?.let { b.header("If-None-Match", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `health, prometheus and the egress pull API`() {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200)
        assertThat(get("/egress/v1/acl").statusCode()).isEqualTo(401)
        registry.register("job-1", listOf("bo.example.com"))
        val acl = get("/egress/v1/acl", "Bearer test-egress-token")
        assertThat(acl.statusCode()).isEqualTo(200)
        assertThat(acl.body()).contains("\"name\":\"job-1\"").contains("\"allowed_domains\":[\"bo.example.com\"]").contains("\"credentials\"")
        val etag = acl.headers().firstValue("ETag").orElseThrow()
        assertThat(get("/egress/v1/acl", "Bearer test-egress-token", etag).statusCode()).isEqualTo(304)

        val post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/egress/v1/decisions"))
            .header("Authorization", "Bearer test-egress-token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""[{"role":"job-1","host":"evil.example.net:443","allow":false,"reason":"denied","at":"t"}]"""))
            .build()
        assertThat(http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202)
        assertThat(registry.summary("job-1").denied).containsExactly("evil.example.net")
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200)
    }
}
