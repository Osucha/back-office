package pl.mos.backoffice.projects.adapter.out.mcp

import org.slf4j.LoggerFactory
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.application.port.out.McpProbe
import pl.mos.backoffice.projects.application.port.out.McpProbePort
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.shared.runner.ConnectorAuth
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR 0064: tests an `MCP_HTTP` connector from Back Office over the MCP streamable HTTP transport: POST of the JSON-RPC
 * `initialize` request (`Accept: application/json, text/event-stream`, the connector's auth header), the
 * `notifications/initialized` notification, then `tools/list`; the answer may be JSON or a server-sent event stream.
 * Outbound HTTP follows the platform's conventions (JDK HTTP client: the JVM's proxy selector and trust store, no
 * redirects, short timeouts). The secret is opened only here and never logged or put into a message.
 */
class McpHttpProbe(
    private val cipher: AesGcmSecretCipher,
    private val json: JsonMapper,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    private val totalTimeout: Duration = TOTAL_TIMEOUT,
) : McpProbePort {

    private val log = LoggerFactory.getLogger(McpHttpProbe::class.java)

    /** Thrown with a message fit for the user (never the secret). */
    private class ProbeFailure(message: String) : RuntimeException(message)

    private class Session(val url: URI, val authHeader: Pair<String, String>?) {
        var sessionId: String? = null
        var protocolVersion: String? = null
        val open = AtomicReference<InputStream?>()
    }

    override fun probe(connector: Connector): McpProbe {
        val url = connector.url?.let { runCatching { URI(it) }.getOrNull() } ?: return McpProbe(false, "the connector has no valid URL")
        val secret = connector.secret?.let { sealed ->
            runCatching { cipher.open(sealed, Connector.secretAssociatedData(connector.id)) }
                .getOrElse { return McpProbe(false, "the stored secret cannot be opened (secret key changed?); set the secret again") }
        }
        val header = when (connector.auth) {
            ConnectorAuth.NONE -> null
            ConnectorAuth.BEARER -> secret?.let { "Authorization" to "Bearer $it" }
            ConnectorAuth.HEADER -> secret?.let { s -> connector.headerName?.let { it to s } }
        }
        val session = Session(url, header)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            CompletableFuture.supplyAsync({ handshake(session) }, executor).get(totalTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            McpProbe(false, "${url.host} did not complete the MCP handshake within ${totalTimeout.seconds} s")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ProbeFailure) {
                McpProbe(false, cause.message ?: "the MCP handshake failed")
            } else if (cause is java.net.http.HttpTimeoutException) {
                McpProbe(false, "${url.host} did not answer within ${requestTimeout.seconds} s")
            } else {
                log.info("MCP probe of {} failed: {}", url.host, cause?.javaClass?.simpleName)
                McpProbe(false, "${url.host} is not reachable: ${cause?.javaClass?.simpleName ?: "error"}")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            McpProbe(false, "the test was interrupted")
        } finally {
            runCatching { session.open.getAndSet(null)?.close() }
            executor.shutdownNow()
        }
    }

    private fun handshake(session: Session): McpProbe {
        val init = request(
            session, 1, "initialize",
            mapOf(
                "protocolVersion" to PROTOCOL_VERSION,
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "back-office-connector-test", "version" to "1"),
            ),
        )
        val result = init.path("result")
        val server = result.path("serverInfo").path("name").asStringOrNull()
        session.protocolVersion = result.path("protocolVersion").asStringOrNull() ?: PROTOCOL_VERSION
        val serverText = (server?.let { "MCP server $it" } ?: "the MCP server") + " (protocol ${session.protocolVersion})"
        // the notification is part of the handshake; servers answer 202 (or 200/204) without a body
        notify(session, "notifications/initialized")
        if (result.path("capabilities").path("tools").isMissingNode) {
            return McpProbe(true, "$serverText answered the initialize handshake; it announces no tools")
        }
        val listed = runCatching { request(session, 2, "tools/list", emptyMap<String, Any>()) }
            .getOrElse { e -> return McpProbe(true, "$serverText answered the initialize handshake; tools/list failed: ${e.message}") }
        val tools = listed.path("result").path("tools").takeIf { it.isArray }
            ?.let { a -> (0 until a.size()).mapNotNull { a.get(it).path("name").asStringOrNull() } }.orEmpty()
        return McpProbe(true, "$serverText answered; ${tools.size} tool(s)", tools.take(MAX_TOOLS))
    }

    /** One JSON-RPC request; the answer with [id] (JSON body or the first matching server-sent event). */
    private fun request(session: Session, id: Int, method: String, params: Map<String, Any>): JsonNode {
        val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        val response = send(session, body)
        response.headers().firstValue(SESSION_HEADER).ifPresent { session.sessionId = it }
        val stream = response.body()
        session.open.set(stream)
        try {
            val status = response.statusCode()
            when {
                status == 401 || status == 403 -> throw ProbeFailure("${session.url.host} refused the credentials (HTTP $status) to $method")
                status == 404 || status == 405 -> throw ProbeFailure("${session.url.host} has no MCP endpoint at ${session.url.path.ifEmpty { "/" }} (HTTP $status)")
                status !in 200..299 -> throw ProbeFailure("${session.url.host} answered $method with HTTP $status")
            }
            val type = response.headers().firstValue("Content-Type").orElse("").lowercase()
            val answer = if (type.startsWith("text/event-stream")) fromEvents(stream, id) else json.readTree(readLimited(stream))
            answer ?: throw ProbeFailure("${session.url.host} sent no answer to $method")
            answer.path("error").takeIf { it.isObject }?.let { err ->
                throw ProbeFailure("${session.url.host} answered $method with error ${err.path("code").asStringOrNull() ?: ""}: ${err.path("message").asStringOrNull() ?: "unknown"}".trim())
            }
            if (!answer.path("result").isObject) throw ProbeFailure("${session.url.host} did not answer $method as an MCP server (no JSON-RPC result)")
            return answer
        } catch (e: ProbeFailure) {
            throw e
        } catch (e: tools.jackson.core.JacksonException) {
            throw ProbeFailure("${session.url.host} did not answer $method with JSON-RPC (${e.javaClass.simpleName})")
        } finally {
            runCatching { stream.close() }
            session.open.set(null)
        }
    }

    private fun notify(session: Session, method: String) {
        val body = json.writeValueAsString(mapOf("jsonrpc" to "2.0", "method" to method))
        runCatching { send(session, body).body().close() }
    }

    private fun send(session: Session, body: String): HttpResponse<InputStream> {
        val builder = HttpRequest.newBuilder(session.url)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        session.sessionId?.let { builder.header(SESSION_HEADER, it) }
        session.protocolVersion?.let { builder.header(PROTOCOL_HEADER, it) }
        session.authHeader?.let { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    /** The first `message` event whose JSON-RPC id is [id]; null when the stream ends without one. */
    private fun fromEvents(stream: InputStream, id: Int): JsonNode? {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val data = StringBuilder()
        var read = 0
        while (true) {
            val line = reader.readLine() ?: break
            read += line.length + 1
            if (read > MAX_BODY) throw ProbeFailure("the event stream is larger than ${MAX_BODY / 1024} KiB without an answer")
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                line.isEmpty() && data.isNotEmpty() -> {
                    val node = runCatching { json.readTree(data.toString()) }.getOrNull()
                    data.setLength(0)
                    if (node != null && node.path("id").asStringOrNull() == id.toString()) return node
                }
            }
        }
        return data.takeIf { it.isNotEmpty() }?.let { runCatching { json.readTree(it.toString()) }.getOrNull() }
    }

    private fun readLimited(stream: InputStream): String {
        val bytes = stream.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) throw ProbeFailure("the answer is larger than ${MAX_BODY / 1024} KiB")
        return String(bytes, Charsets.UTF_8)
    }

    private fun JsonNode.asStringOrNull(): String? = if (isMissingNode || isNull) null else if (isValueNode) asString() else null

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val PROTOCOL_HEADER = "MCP-Protocol-Version"
        private const val AGENT = "back-office-connector-test"
        private const val MAX_BODY = 1024 * 1024
        private const val MAX_TOOLS = 200
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        val TOTAL_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
