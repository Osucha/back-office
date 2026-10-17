package pl.mos.backoffice.runnercontroller.protocol

import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Back Office answered with a status the caller has to react to (401 = token revoked, 409 = lease lost). */
class BackOfficeHttpException(val status: Int, val path: String, body: String) :
    RuntimeException("Back Office $path answered HTTP $status: ${body.take(500)}")

/**
 * HTTPS client for the runner protocol v2. `baseUrl` is the Back Office origin (`https://bo.example.com`);
 * a trailing `/api/v1` is accepted too. Every call except `register` carries the runner token (`bo_rt_`).
 */
class BackOfficeClient(
    baseUrl: String,
    private val json: JsonMapper,
    private val tokenProvider: () -> String?,
    private val userAgent: String = "back-office-runner-controller",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val apiBase: String = normalizeApiBase(baseUrl)

    fun register(request: RunnerRegistration): RunnerRegistered =
        send("POST", "/runner/v2/register", request, authenticated = false, timeout = Duration.ofSeconds(30))
            .let { json.readValue(it, RunnerRegistered::class.java) }

    fun config(): RunnerConfig =
        send("GET", "/runner/v2/config", null, timeout = Duration.ofSeconds(30))
            .let { json.readValue(it, RunnerConfig::class.java) }

    fun lease(request: LeaseRequest): List<AgentJobAssignment> {
        val body = send("POST", "/runner/v2/jobs/lease", request, timeout = Duration.ofSeconds(request.waitSeconds + 15L))
        if (body.isBlank()) return emptyList()
        return json.readValue(body, object : TypeReference<List<AgentJobAssignment>>() {})
    }

    fun heartbeat(jobId: String, heartbeat: JobHeartbeat): JobHeartbeatResponse =
        send("POST", "/runner/v2/jobs/$jobId/heartbeat", heartbeat, timeout = Duration.ofSeconds(20))
            .let { if (it.isBlank()) JobHeartbeatResponse() else json.readValue(it, JobHeartbeatResponse::class.java) }

    fun complete(jobId: String, completion: JobCompletion) {
        send("POST", "/runner/v2/jobs/$jobId/complete", completion, timeout = Duration.ofSeconds(20))
    }

    /** ADR 0044: reports the held run workspaces; returns the ones to delete. */
    fun syncWorkspaces(held: List<String>): List<String> =
        send("POST", "/runner/v2/workspaces/sync", WorkspaceSync(held), timeout = Duration.ofSeconds(30))
            .let { if (it.isBlank()) emptyList() else json.readValue(it, WorkspaceSyncResult::class.java).release }

    private fun send(method: String, path: String, body: Any?, authenticated: Boolean = true, timeout: Duration): String {
        val builder = HttpRequest.newBuilder(URI.create(apiBase + path))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
        if (authenticated) {
            val token = tokenProvider() ?: throw IllegalStateException("runner is not registered")
            builder.header("Authorization", "Bearer $token")
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw BackOfficeUnavailableException("$method $path failed: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BackOfficeUnavailableException("$method $path interrupted", e)
        }
        if (response.statusCode() !in 200..299) {
            log.debug("{} {} -> {}", method, path, response.statusCode())
            throw BackOfficeHttpException(response.statusCode(), path, response.body() ?: "")
        }
        return response.body() ?: ""
    }

    companion object {
        fun normalizeApiBase(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) { "Back Office URL must be http(s): $url" }
            return if (trimmed.endsWith("/api/v1")) trimmed else "$trimmed/api/v1"
        }
    }
}

class BackOfficeUnavailableException(message: String, cause: Throwable) : RuntimeException(message, cause)
