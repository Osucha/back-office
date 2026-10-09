package pl.mos.backoffice.runner.adapter.`in`.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import pl.mos.backoffice.runner.application.port.`in`.RunnerFleetProtocol
import pl.mos.backoffice.runner.domain.FleetTokens
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.platform.JobTokenScope
import pl.mos.backoffice.shared.platform.JobTokenVerifier

/**
 * Authenticates the machine callers of the execution plane (ADR 0024). Spring Security's chain permits these
 * paths (`IdentitySecurityConfiguration`); this filter is the authentication:
 *
 * | path | credential |
 * |---|---|
 * | `POST /api/v1/runner/v2/register` | none here: the single-use `bo_rrt_` token is in the body |
 * | `/api/v1/runner/v2/jobs/{id}/uploads` | job token `bo_job_` (sandbox) |
 * | other `/api/v1/runner/v2/` | runner token `bo_rt_` (runner-controller) |
 * | `/api/v1/internal/runner/` | job token `bo_job_` (sandbox) |
 *
 * A token of the wrong kind is refused with 401: a sandbox cannot lease jobs, a controller cannot post an
 * agent result or call the LLM proxy, and the gateway-only agent token of the coding agent (ADR 0029) reaches
 * none of these paths.
 */
class RunnerTokenFilter(
    private val protocol: RunnerFleetProtocol,
    private val verifier: JobTokenVerifier,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = path(request)
        return !(path.startsWith(FLEET_PREFIX) || path.startsWith(CALLBACK_PREFIX))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = path(request)
        if (path == REGISTER_PATH) return chain.doFilter(request, response)
        val token = FleetTokens.bearer(request.getHeader("Authorization"))
            ?: return unauthorized(response, "Missing bearer token")
        if (path.startsWith(CALLBACK_PREFIX) || UPLOADS.matches(path)) {
            if (!token.startsWith(FleetTokens.JOB_PREFIX)) return unauthorized(response, "This endpoint needs the job token (bo_job_) of the sandbox")
            val credential = verifier.verify(token) ?: return unauthorized(response, "The job token is invalid, expired or the job is no longer active")
            if (credential.scope != JobTokenScope.FULL) {
                return unauthorized(response, "The agent token only reaches the LLM gateway; callbacks need the job token of the runner")
            }
            request.setAttribute(JOB_CREDENTIAL, credential)
        } else {
            if (!token.startsWith(FleetTokens.RUNNER_PREFIX)) return unauthorized(response, "This endpoint needs a runner token (bo_rt_)")
            val runner = protocol.authenticate(token) ?: return unauthorized(response, "The runner token is invalid or revoked")
            request.setAttribute(RUNNER, runner)
        }
        chain.doFilter(request, response)
    }

    private fun path(request: HttpServletRequest): String = request.requestURI.removePrefix(request.contextPath ?: "")

    companion object {
        const val FLEET_PREFIX = "/api/v1/runner/v2/"
        const val CALLBACK_PREFIX = "/api/v1/internal/runner/"
        const val REGISTER_PATH = "/api/v1/runner/v2/register"
        val UPLOADS = Regex("^/api/v1/runner/v2/jobs/[^/]+/uploads/?$")

        const val JOB_CREDENTIAL = "pl.mos.backoffice.runner.jobCredential"
        const val RUNNER = "pl.mos.backoffice.runner.runner"

        fun unauthorized(response: HttpServletResponse, detail: String) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/problem+json"
            response.characterEncoding = "UTF-8"
            response.writer.write(
                """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"${detail.replace("\"", "'")}","code":"invalid-token"}""",
            )
        }

        fun credential(request: HttpServletRequest): JobCredential =
            request.getAttribute(JOB_CREDENTIAL) as? JobCredential ?: throw IllegalStateException("no job credential on the request")

        fun runner(request: HttpServletRequest): RunnerNode =
            request.getAttribute(RUNNER) as? RunnerNode ?: throw IllegalStateException("no runner on the request")
    }
}
