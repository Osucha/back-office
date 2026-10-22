package pl.mos.backoffice.projects.adapter.out.scm

import org.slf4j.LoggerFactory
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.application.port.out.ScmProbePort
import pl.mos.backoffice.projects.application.port.out.ScmToken
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmProbe
import pl.mos.backoffice.projects.domain.model.ScmProvider
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.util.Base64

/**
 * Tests a GitLab token against one repository (ADR 0051 point 2) with read-only calls to the repository's own host:
 * the git smart-HTTP advertisements of upload-pack (clone) and receive-pack (push; GitLab answers 200 only to
 * tokens that may push), `GET /api/v4/personal_access_tokens/self` (scopes, expiry; works for project and group
 * access tokens too), the project (role, default branch) and the default branch's protection. Nothing is written.
 */
class GitLabScmProbe(
    private val cipher: AesGcmSecretCipher,
    private val json: JsonMapper,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
) : ScmProbePort {

    private val log = LoggerFactory.getLogger(GitLabScmProbe::class.java)

    override fun probe(provider: ScmProvider, repository: GitRepository, token: ScmToken): ScmProbe {
        val secret = when (token) {
            is ScmToken.Plain -> token.value
            is ScmToken.Stored -> runCatching { cipher.open(token.connection.secret, ScmConnection.secretAssociatedData(token.connection.id)) }
                .getOrElse { return ScmProbe(failure = "the stored token cannot be opened (secret key changed?); replace the token") }
        }
        val location = runCatching { RepoLocation.of(repository.url) }
            .getOrElse { return ScmProbe(failure = "the repository URL ${repository.url} is not an http(s) git URL") }
        return try {
            probe(location, secret)
        } catch (e: java.io.IOException) {
            log.info("GitLab probe of {} failed: {}", location.base, e.toString())
            ScmProbe(failure = "${location.base} is not reachable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun probe(at: RepoLocation, token: String): ScmProbe {
        val gitRead = gitService(at, token, "git-upload-pack")
        val gitPush = gitService(at, token, "git-receive-pack")
        val self = api(at, token, "/personal_access_tokens/self")
        if (self.status == 401 && gitRead == false) return ScmProbe(failure = "GitLab refused the token (401): wrong, revoked or expired")
        val scopes = self.body?.path("scopes")?.takeIf { it.isArray }?.let { a -> (0 until a.size()).map { a.get(it).asString() } }
        val project = api(at, token, "/projects/${encode(at.path)}")
        val level = project.body?.path("permissions")?.let { p ->
            listOfNotNull(p.path("project_access").path("access_level").asIntOrNull(), p.path("group_access").path("access_level").asIntOrNull()).maxOrNull()
        }
        val branch = project.body?.path("default_branch")?.asStringOrNull()
        val protection = if (project.body != null && branch != null) api(at, token, "/projects/${encode(at.path)}/protected_branches/${encode(branch)}") else null
        val mergeLevel = protection?.body?.path("merge_access_levels")?.takeIf { it.isArray }
            ?.let { a -> (0 until a.size()).mapNotNull { a.get(it).path("access_level").asIntOrNull() } }?.minOrNull()
        return ScmProbe(
            gitRead = gitRead,
            gitPush = gitPush,
            scopes = scopes,
            tokenName = self.body?.path("name")?.asStringOrNull(),
            tokenExpiresAt = self.body?.path("expires_at")?.asStringOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            accessLevel = level,
            defaultBranch = branch,
            defaultBranchProtected = when (protection?.status) { 200 -> true; 404 -> false; else -> null },
            mergeAccessLevel = mergeLevel,
        )
    }

    /** true = 200 with the git advertisement, false = refused (401/403/404), null = could not tell. */
    private fun gitService(at: RepoLocation, token: String, service: String): Boolean? {
        val basic = Base64.getEncoder().encodeToString("oauth2:$token".toByteArray(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder(URI.create("${at.gitUrl}/info/refs?service=$service"))
            .timeout(TIMEOUT).header("Authorization", "Basic $basic").header("User-Agent", AGENT).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        return when {
            response.statusCode() == 200 -> response.headers().firstValue("Content-Type").orElse("").contains("x-$service-advertisement")
            response.statusCode() in setOf(401, 403, 404) -> false
            else -> null
        }
    }

    private data class ApiAnswer(val status: Int, val body: JsonNode?)

    private fun api(at: RepoLocation, token: String, path: String): ApiAnswer {
        val request = HttpRequest.newBuilder(URI.create("${at.base}/api/v4$path"))
            .timeout(TIMEOUT).header("PRIVATE-TOKEN", token).header("Accept", "application/json").header("User-Agent", AGENT).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = if (response.statusCode() == 200) runCatching { json.readTree(response.body()) }.getOrNull() else null
        return ApiAnswer(response.statusCode(), body)
    }

    private fun JsonNode.asIntOrNull(): Int? = if (isNumber) asInt() else null
    private fun JsonNode.asStringOrNull(): String? = if (isString) asString() else null

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** `https://host[:port]/group/sub/project(.git)` split into the API base and the project path. */
    internal data class RepoLocation(val base: String, val path: String, val gitUrl: String) {
        companion object {
            fun of(url: String): RepoLocation {
                val uri = URI.create(url.trim())
                require(uri.scheme == "https" || uri.scheme == "http") { "not http(s)" }
                require(!uri.host.isNullOrBlank()) { "no host" }
                val path = uri.path.trim('/').removeSuffix(".git")
                require(path.contains('/')) { "no project path" }
                val base = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
                return RepoLocation(base, path, "$base/$path.git")
            }
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(15)
        const val AGENT = "back-office-scm-probe"
    }
}
