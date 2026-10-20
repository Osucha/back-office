package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.CancelRun
import pl.mos.backoffice.projects.application.port.`in`.RetryRun
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.SignalRun
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.usage.SortSpec
import java.time.Instant
import java.util.UUID

/** `runs` endpoints: list/get, artefacts, logs, cancel, retry, manual signals. */
@RestController
@RequestMapping("/api/v1/runs")
class RunController(
    private val queries: RunQueries,
    private val cancelRun: CancelRun,
    private val retryRun: RetryRun,
    private val signalRun: SignalRun,
    private val mapper: RestMapper,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) workflowId: UUID?,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) sort: String?,
    ): PageDto<WorkflowRunSummaryDto> {
        val statuses = (status ?: emptyList()).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
            .map { s -> RunStatus.entries.firstOrNull { it.name.equals(s, true) } ?: throw ValidationException("unknown status $s", "status") }
            .toSet()
        val filter = RunFilter(projectId?.let(::ProjectId), workflowId?.let(::WorkflowDefinitionId), statuses, SortSpec.parse(sort, RunFilter.SORT_FIELDS))
        val result = queries.list(filter, page.coerceAtLeast(0), size.coerceIn(1, 200))
        return PageDto(result.content.map(mapper::toSummary), result.page, result.size, result.totalElements)
    }

    @GetMapping("/{runId}")
    fun get(@PathVariable runId: UUID): WorkflowRunDto = mapper.toDto(queries.get(WorkflowRunId(runId)))

    @GetMapping("/{runId}/artifacts")
    fun artifacts(@PathVariable runId: UUID): List<ArtifactSummaryDto> = queries.artifacts(WorkflowRunId(runId)).map(mapper::toSummary)

    @GetMapping("/{runId}/artifacts/{artifactId}")
    fun artifact(@PathVariable runId: UUID, @PathVariable artifactId: UUID): ArtifactDto =
        mapper.toDto(queries.artifact(WorkflowRunId(runId), ArtifactId(artifactId)))

    @GetMapping("/{runId}/logs")
    fun logs(
        @PathVariable runId: UUID,
        @RequestParam(required = false) nodeId: String?,
        @RequestParam(required = false) after: Instant?,
    ): List<LogLineDto> = queries.logs(WorkflowRunId(runId), nodeId?.takeIf { it.isNotBlank() }, after).map(mapper::toDto)

    @PostMapping("/{runId}/cancel")
    fun cancel(@PathVariable runId: UUID): WorkflowRunDto = mapper.toDto(cancelRun.cancel(WorkflowRunId(runId)))

    @PostMapping("/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(@PathVariable runId: UUID): WorkflowRunDto = mapper.toDto(retryRun.retry(WorkflowRunId(runId)))

    @PostMapping("/{runId}/signals")
    fun signal(@PathVariable runId: UUID, @RequestBody @Valid body: ManualSignalDto): WorkflowRunDto =
        mapper.toDto(signalRun.signalRun(WorkflowRunId(runId), body.signal.trim(), mapper.signalStatus(body.status), body.message?.takeIf { it.isNotBlank() }))
}
