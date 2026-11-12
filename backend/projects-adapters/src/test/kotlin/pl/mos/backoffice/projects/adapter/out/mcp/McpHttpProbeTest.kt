package pl.mos.backoffice.projects.adapter.out.mcp

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.adapter.out.secrets.SecretsProperties
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.security.Actor
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/** ADR 0064: the MCP handshake over streamable HTTP (JSON and SSE answers), with the stored secret, never leaking it. */
class McpHttpProbeTest {

    private val cipher = AesGcmSecretCipher(SecretsProperties(key = "dGVzdC1vbmx5LWNvbm5lY3Rpb25zLXNlY3JldC1rZXk=", keyId = "test"))
    private val probe = McpHttpProbe(cipher, JsonMapper.builder().build())
    private val secret = "tkn-mcp-very-secret"

    @BeforeEach
    fun reset() = server.resetAll()

    private fun connector(path: String = "/mcp", auth: ConnectorAuth = ConnectorAuth.BEARER, headerName: String? = null, sealed: Boolean = true): Connector {
        val id = ConnectorId.new()
        return Connector(
            id = id, key = "docs", name = "Docs", description = "", kind = ConnectorKind.MCP_HTTP, projectId = null,
            url = server.baseUrl() + path, auth = auth, headerName = headerName,
            secret = if (auth == ConnectorAuth.NONE) null else if (sealed) cipher.seal(secret, Connector.secretAssociatedData(id)) else SealedSecret("test", "AAAA", "AAAA"),
            createdAt = Instant.EPOCH, createdBy = Actor.SYSTEM, updatedAt = Instant.EPOCH, updatedBy = Actor.SYSTEM,
        )
    }

    private fun stubInitialize(capabilities: String = """{"tools":{}}""", contentType: String = "application/json") {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":$capabilities,"serverInfo":{"name":"docs-server","version":"1.0"}}}"""
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
                .willReturn(
                    aResponse().withHeader("Content-Type", contentType).withHeader("Mcp-Session-Id", "session-1")
                        .withBody(if (contentType.startsWith("text/event-stream")) "event: message\ndata: $body\n\n" else body),
                ),
        )
        server.stubFor(post(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("notifications/initialized"))).willReturn(aResponse().withStatus(202)))
    }

    @Test
    fun `a server that answers initialize and tools list by SSE passes, with the bearer secret, session and protocol headers`() {
        stubInitialize()
        server.stubFor(
            post(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
                .willReturn(
                    aResponse().withHeader("Content-Type", "text/event-stream").withBody(
                        ": keep-alive\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\n" +
                            "data: \"result\":{\"tools\":[{\"name\":\"search\"},{\"name\":\"fetch\"}]}}\n\n",
                    ),
                ),
        )
        val result = probe.probe(connector())
        assertThat(result.ok).isTrue()
        assertThat(result.tools).containsExactly("search", "fetch")
        assertThat(result.message).contains("docs-server").contains("2025-03-26").contains("2 tool(s)").doesNotContain(secret)
        server.verify(
            postRequestedFor(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
                .withHeader("Authorization", equalTo("Bearer $secret")).withHeader("Accept", equalTo("application/json, text/event-stream")),
        )
        server.verify(
            postRequestedFor(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
                .withHeader("Mcp-Session-Id", equalTo("session-1")).withHeader("MCP-Protocol-Version", equalTo("2025-03-26")),
        )
    }

    @Test
    fun `a custom header carries the secret, a server without tools passes without listing them`() {
        stubInitialize(capabilities = "{}", contentType = "text/event-stream")
        val result = probe.probe(connector(auth = ConnectorAuth.HEADER, headerName = "X-Api-Key"))
        assertThat(result.ok).isTrue()
        assertThat(result.message).contains("no tools")
        server.verify(postRequestedFor(urlPathEqualTo("/mcp")).withHeader("X-Api-Key", equalTo(secret)))
        server.verify(0, postRequestedFor(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/list"))))
    }

    @Test
    fun `refused credentials, a missing endpoint, a JSON-RPC error and a non-MCP answer fail with a message`() {
        server.stubFor(post(urlPathEqualTo("/mcp")).willReturn(aResponse().withStatus(401)))
        assertThat(probe.probe(connector()).message).contains("refused the credentials (HTTP 401)")
        assertThat(probe.probe(connector(path = "/other")).message).contains("no MCP endpoint at /other")

        server.resetAll()
        server.stubFor(post(urlPathEqualTo("/mcp")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad version"}}""")))
        val error = probe.probe(connector(auth = ConnectorAuth.NONE))
        assertThat(error.ok).isFalse()
        assertThat(error.message).contains("-32602").contains("bad version")

        server.resetAll()
        server.stubFor(post(urlPathEqualTo("/mcp")).willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html>login</html>")))
        assertThat(probe.probe(connector()).message).contains("JSON-RPC")
        server.resetAll()
        server.stubFor(post(urlPathEqualTo("/mcp")).willReturn(aResponse().withStatus(500)))
        assertThat(probe.probe(connector()).message).contains("HTTP 500")
    }

    @Test
    fun `tools list failing still passes the handshake, slow, unreachable and unopenable secrets fail`() {
        stubInitialize()
        server.stubFor(post(urlPathEqualTo("/mcp")).withRequestBody(matchingJsonPath("$.method", equalTo("tools/list"))).willReturn(aResponse().withStatus(500)))
        val partial = probe.probe(connector())
        assertThat(partial.ok).isTrue()
        assertThat(partial.message).contains("tools/list failed")

        assertThat(probe.probe(connector(sealed = false)).message).contains("cannot be opened")

        server.resetAll()
        server.stubFor(post(urlPathEqualTo("/mcp")).willReturn(aResponse().withFixedDelay(3_000).withBody("{}")))
        val slow = McpHttpProbe(cipher, JsonMapper.builder().build(), requestTimeout = Duration.ofMillis(300), totalTimeout = Duration.ofSeconds(5))
        assertThat(slow.probe(connector()).message).contains("did not answer within")
        val impatient = McpHttpProbe(cipher, JsonMapper.builder().build(), requestTimeout = Duration.ofSeconds(5), totalTimeout = Duration.ofMillis(300))
        assertThat(impatient.probe(connector()).message).contains("did not complete the MCP handshake")

        val closed = connector().copy(url = "http://127.0.0.1:1/mcp")
        val unreachable = probe.probe(closed)
        assertThat(unreachable.ok).isFalse()
        assertThat(unreachable.message).contains("not reachable").doesNotContain(secret)
    }

    companion object {
        val server: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop()
    }
}
