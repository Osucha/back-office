package pl.mos.backoffice.projects.adapter.out.scm

import org.slf4j.LoggerFactory
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.application.port.out.FailedJob
import pl.mos.backoffice.projects.application.port.out.PipelineReport
import pl.mos.backoffice.projects.application.port.out.PipelineState
import pl.mos.backoffice.projects.application.port.out.PipelineStatusPort
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.ScmConnection
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * ADR 0052: the latest GitLab pipeline of a branch, or of the open merge request from it when the CI runs only for merge
 * requests, read-only (`GET /projects/:path/pipelines?ref=&sha=`, its failed
 * jobs and their traces). Failed jobs that may fail (`allow_failure`) are left out; each log is reduced to its last
 * [LOG_TAIL_LINES] lines without ANSI colours, so the report fits a prompt.
 */
class GitLabPipelineStatus(
    private val cipher: AesGcmSecretCipher,
    private val json: JsonMapper,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
) : PipelineStatusPort {

    private val log = LoggerFactory.getLogger(GitLabPipelineStatus::class.java)

    override fun latest(repository: GitRepository, connection: ScmConnection, ref: String, sha: String?): PipelineReport = try {
        val token = cipher.open(connection.secret, ScmConnection.secretAssociatedData(connection.id))
        val at = GitLabScmProbe.RepoLocation.of(repository.url)
        val project = "/projects/${encode(at.path)}"
        val pipeline = newest(at, token, project, ref, sha)
            // a CI that runs only for merge requests: the pipeline of the open merge request from this branch
            ?: openMergeRequest(at, token, project, ref)?.let { iid -> newest(at, token, project, "refs/merge-requests/$iid/head", sha) }
        if (pipeline == null) {
            PipelineReport(PipelineState.NONE)
        } else {
            val id = pipeline.path("id").asLong()
            val status = pipeline.path("status").asString()
            val state = stateOf(status)
            val failed = if (state == PipelineState.FAILED) failedJobs(at, token, project, id) else emptyList()
            PipelineReport(state, id, pipeline.path("web_url").takeIf { it.isString }?.asString(), status, failed)
        }
    } catch (e: Exception) {
        log.info("pipeline of {} on {} not readable: {}", ref, repository.url, e.toString())
        PipelineReport(PipelineState.NONE)
    }

    private fun newest(at: GitLabScmProbe.RepoLocation, token: String, project: String, ref: String, sha: String?): JsonNode? {
        val query = "ref=${encode(ref)}" + (sha?.let { "&sha=${encode(it)}" } ?: "") + "&per_page=1&order_by=id&sort=desc"
        return get(at, token, "$project/pipelines?$query")?.takeIf { it.isArray && it.size() > 0 }?.get(0)
    }

    private fun openMergeRequest(at: GitLabScmProbe.RepoLocation, token: String, project: String, branch: String): Long? =
        get(at, token, "$project/merge_requests?source_branch=${encode(branch)}&state=opened&per_page=1&order_by=updated_at&sort=desc")
            ?.takeIf { it.isArray && it.size() > 0 }?.get(0)?.path("iid")?.asLong()

    private fun failedJobs(at: GitLabScmProbe.RepoLocation, token: String, project: String, pipelineId: Long): List<FailedJob> {
        val jobs = get(at, token, "$project/pipelines/$pipelineId/jobs?scope[]=failed&per_page=100") ?: return emptyList()
        return (0 until jobs.size()).map { jobs.get(it) }
            .filter { !it.path("allow_failure").asBoolean(false) }
            .take(MAX_JOBS)
            .map { job ->
                val trace = text(at, token, "$project/jobs/${job.path("id").asLong()}/trace").orEmpty()
                FailedJob(job.path("name").asString(), job.path("stage").asString(), job.path("web_url").takeIf { it.isString }?.asString(), tail(trace))
            }
    }

    private fun get(at: GitLabScmProbe.RepoLocation, token: String, path: String): JsonNode? = text(at, token, path)?.let { json.readTree(it) }

    private fun text(at: GitLabScmProbe.RepoLocation, token: String, path: String): String? {
        val request = HttpRequest.newBuilder(URI.create("${at.base}/api/v4$path"))
            .timeout(Duration.ofSeconds(30)).header("PRIVATE-TOKEN", token).header("User-Agent", "back-office-pipeline-watch").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) response.body() else null
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val MAX_JOBS = 5
        const val LOG_TAIL_LINES = 60
        const val LOG_TAIL_CHARS = 4000
        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        // GitLab section markers and per-line timestamps of the trace
        private val SECTION = Regex("section_(start|end):\\d+:[^\\r\\n]*")
        private val STAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z \\d{2}[OE]\\+? ?")

        fun stateOf(status: String): PipelineState = when (status) {
            "success", "manual", "skipped" -> PipelineState.SUCCESS
            "failed", "canceled", "canceling" -> PipelineState.FAILED
            else -> PipelineState.RUNNING
        }

        /** The last [LOG_TAIL_LINES] meaningful lines of a job trace. */
        fun tail(trace: String): String = trace.replace(ANSI, "").replace(SECTION, "")
            .lines().map { it.replace(STAMP, "").trimEnd() }.filter { it.isNotBlank() }
            .takeLast(LOG_TAIL_LINES).joinToString("\n").takeLast(LOG_TAIL_CHARS)
    }
}
