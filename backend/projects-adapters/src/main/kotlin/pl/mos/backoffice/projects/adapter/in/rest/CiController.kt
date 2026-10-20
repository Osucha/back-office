package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.CiExecutionQueries
import pl.mos.backoffice.projects.application.port.`in`.SignalRun
import pl.mos.backoffice.projects.application.port.`in`.StartCiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.usage.SortSpec
import java.util.UUID

/**
 * `ci` endpoints called by `backoffice-ci.sh` (docs/architecture/05-integrations.md). Authentication is done
 * before this controller: the identity context's bearer-token filter verifies `Authorization: Bearer bo_ci_...`
 * through the `CiTokenVerifier` of this context and installs a CI principal holding `ci.execute` and
 * `ci.signal` on its project (401 for missing, unknown or revoked tokens). The project of a CI call is the
 * token's project; the use cases check the permissions through the gate. `GET /ci/executions/{id}` also
 * serves people with `project.view`; the listing is filtered to the caller's projects. The client script is
 * served from the classpath resource `ci/backoffice-ci.sh` (bundled from the repo `ci/` dir).
 */
@RestController
class CiController(
    private val startExecution: StartCiExecution,
    private val executions: CiExecutionQueries,
    private val signals: SignalRun,
    private val gate: AuthorizationGate,
    private val mapper: RestMapper,
) {
    @PostMapping("/api/v1/ci/executions")
    fun start(@RequestBody @Valid body: CiExecutionRequestDto): ResponseEntity<Any> {
        val projectId = tokenProject(ProjectsPermissions.CI_EXECUTE)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toDto(startExecution.start(projectId, mapper.toCommand(body))))
    }

    @GetMapping("/api/v1/ci/executions")
    fun list(
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) sort: String?,
    ): PageDto<CiExecutionSummaryDto> {
        val order = SortSpec.parse(sort, CiExecutionQueries.SORT_FIELDS)
        val result = executions.list(projectId?.let(::ProjectId), page.coerceAtLeast(0), size.coerceIn(1, 200), order)
        return PageDto(result.content.map(mapper::toSummary), result.page, result.size, result.totalElements)
    }

    @GetMapping("/api/v1/ci/executions/{executionId}")
    fun get(@PathVariable executionId: UUID): CiExecutionDto = mapper.toDto(executions.get(CiExecutionId(executionId)))

    @PostMapping("/api/v1/ci/signals")
    fun signal(@RequestBody @Valid body: CiSignalRequestDto): CiSignalResultDto {
        val projectId = tokenProject(ProjectsPermissions.CI_SIGNAL)
        return mapper.toDto(signals.signal(mapper.toCommand(projectId, body)))
    }

    @GetMapping("/api/v1/ci/script", "/ci/backoffice-ci.sh")
    fun script(): ResponseEntity<Any> {
        val resource = ClassPathResource(SCRIPT_RESOURCE)
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The CI client script is not bundled in this build ($SCRIPT_RESOURCE missing)").apply { title = "Not found" })
        }
        val body = resource.inputStream.use { it.readBytes() }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(SCRIPT_MEDIA_TYPE))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"backoffice-ci.sh\"")
            .body(body)
    }

    /** The single project a CI token principal holds [permission] on; anyone else is refused (403). */
    private fun tokenProject(permission: String): ProjectId {
        val caller = gate.currentUser()
        if (caller.kind != PrincipalKind.CI_TOKEN) throw InsufficientPermissionException(permission)
        val project = caller.projectPermissions.filterValues { permission in it }.keys.singleOrNull() ?: throw InsufficientPermissionException(permission)
        return ProjectId(project)
    }

    companion object {
        const val SCRIPT_RESOURCE = "ci/backoffice-ci.sh"
        const val SCRIPT_MEDIA_TYPE = "text/x-shellscript"
    }
}
