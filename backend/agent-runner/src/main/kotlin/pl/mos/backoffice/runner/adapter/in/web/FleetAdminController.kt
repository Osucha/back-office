package pl.mos.backoffice.runner.adapter.`in`.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.mos.backoffice.runner.SandboxPolicyJson
import pl.mos.backoffice.runner.application.port.`in`.AgentJobFilter
import pl.mos.backoffice.runner.application.port.`in`.ManageAgentJobs
import pl.mos.backoffice.runner.application.port.`in`.ManageProjectSandboxPolicy
import pl.mos.backoffice.runner.application.port.`in`.ManageRunnerPools
import pl.mos.backoffice.runner.application.port.`in`.ManageRunners
import pl.mos.backoffice.runner.application.service.RunnerAuthenticationException
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.usage.SortSpec
import java.util.UUID

/** `/api/v1/admin/runner-pools`, `/admin/runners`, `/admin/agent-jobs` (openapi tag `fleet`); permissions are checked in the use cases. */
@RestController
@RequestMapping("/api/v1/admin")
class FleetAdminController(
    private val pools: ManageRunnerPools,
    private val runners: ManageRunners,
    private val jobs: ManageAgentJobs,
) {
    @GetMapping("/runner-pools")
    fun listPools(): List<RunnerPoolJson> = pools.list().map(RunnerPoolJson::of)

    @PostMapping("/runner-pools")
    fun createPool(@RequestBody body: RunnerPoolInputJson): ResponseEntity<RunnerPoolJson> =
        ResponseEntity.status(HttpStatus.CREATED).body(RunnerPoolJson.of(pools.create(body.toInput())))

    @GetMapping("/runner-pools/{poolId}")
    fun getPool(@PathVariable poolId: String): RunnerPoolJson = RunnerPoolJson.of(pools.get(poolId))

    @PutMapping("/runner-pools/{poolId}")
    fun updatePool(@PathVariable poolId: String, @RequestBody body: RunnerPoolInputJson): RunnerPoolJson =
        RunnerPoolJson.of(pools.update(poolId, body.copy(id = body.id.ifBlank { poolId }).toInput()))

    @DeleteMapping("/runner-pools/{poolId}")
    fun deletePool(@PathVariable poolId: String): ResponseEntity<Void> {
        pools.delete(poolId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/runner-pools/{poolId}/registration-tokens")
    fun registrationToken(@PathVariable poolId: String): ResponseEntity<RegistrationTokenJson> {
        val issued = pools.issueRegistrationToken(poolId)
        return ResponseEntity.status(HttpStatus.CREATED).body(RegistrationTokenJson(issued.token, issued.expiresAt, issued.helmValues))
    }

    @GetMapping("/runners")
    fun listRunners(@RequestParam(required = false) poolId: String?): List<RunnerJson> = runners.list(poolId).map(RunnerJson::of)

    @DeleteMapping("/runners/{runnerId}")
    fun revokeRunner(@PathVariable runnerId: UUID): ResponseEntity<Void> {
        runners.revoke(runnerId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/agent-jobs")
    fun listJobs(
        @RequestParam(required = false) poolId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) sort: String?,
    ): AgentJobPageJson {
        val parsed = status?.let { s -> AgentJobStatus.entries.firstOrNull { it.name == s.uppercase() } ?: throw ValidationException("unknown status $s", "status") }
        val order = SortSpec.parse(sort, AgentJobFilter.SORT_FIELDS)
        val result = jobs.list(AgentJobFilter(poolId, parsed, projectId, order), page, size)
        return AgentJobPageJson(result.content.map(AgentJobJson::of), result.page, result.size, result.totalElements)
    }

    @GetMapping("/agent-jobs/{jobId}")
    fun getJob(@PathVariable jobId: UUID): AgentJobJson = AgentJobJson.of(jobs.get(RunnerJobId(jobId)))

    @PostMapping("/agent-jobs/{jobId}/cancel")
    fun cancelJob(@PathVariable jobId: UUID): ResponseEntity<Void> {
        jobs.cancel(RunnerJobId(jobId))
        return ResponseEntity.accepted().build()
    }
}

/** `/api/v1/projects/{projectId}/sandbox-policy` (read: `project.view`, write: `project.manage`). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/sandbox-policy")
class ProjectSandboxPolicyController(private val policies: ManageProjectSandboxPolicy) {

    @GetMapping
    fun get(@PathVariable projectId: UUID): SandboxPolicyViewJson = SandboxPolicyViewJson.of(policies.get(projectId))

    @PutMapping
    fun put(@PathVariable projectId: UUID, @RequestBody body: SandboxPolicyJson): SandboxPolicyViewJson =
        SandboxPolicyViewJson.of(policies.update(projectId, body.toPolicy()))
}

/** 401 for unknown / used registration tokens and job tokens of another job (the generic mapping lives in `app`). */
@RestControllerAdvice(assignableTypes = [RunnerProtocolController::class, SandboxCallbackController::class])
class RunnerProtocolExceptionHandler {
    @ExceptionHandler(RunnerAuthenticationException::class)
    fun unauthorized(e: RunnerAuthenticationException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.message ?: "unauthorized").apply {
                title = "Unauthorized"
                setProperty("code", "invalid-token")
            },
        )
}
