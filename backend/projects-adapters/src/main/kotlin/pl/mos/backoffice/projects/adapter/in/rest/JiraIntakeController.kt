package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManageJiraIntake
import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Instant
import java.util.UUID

/**
 * `/projects/{projectId}/jira-intake` (ADR 0033): the project's Jira intake watcher. Reading needs `project.view`,
 * changing and "Poll now" `project.manage`; the use case checks.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/jira-intake")
class JiraIntakeController(
    private val intake: ManageJiraIntake,
    private val connections: ConnectionDtos,
    private val gate: AuthorizationGate,
) {
    @GetMapping
    fun get(@PathVariable projectId: UUID): JiraIntakeDto = toDto(intake.get(ProjectId(projectId)))

    @PutMapping
    fun update(@PathVariable projectId: UUID, @RequestBody @Valid body: JiraIntakeInputDto): JiraIntakeDto = toDto(
        intake.update(ProjectId(projectId), ManageJiraIntake.IntakeCommand(body.enabled!!, body.pollIntervalMinutes ?: JiraIntake.DEFAULT_POLL_MINUTES, body.workflowId?.let(::WorkflowDefinitionId))),
    )

    @PostMapping("/poll")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun pollNow(@PathVariable projectId: UUID): JiraIntakeDto = toDto(intake.pollNow(ProjectId(projectId)))

    private fun toDto(view: ManageJiraIntake.JiraIntakeView): JiraIntakeDto {
        val i = view.intake
        return JiraIntakeDto(
            enabled = i.enabled,
            pollIntervalMinutes = i.pollIntervalMinutes,
            workflowId = i.workflowId?.value,
            workflowName = view.workflowName,
            triggerStatus = view.triggerStatus,
            mode = view.mode?.name,
            jql = view.jql,
            connection = connections.toDto(view.connection),
            problems = view.problems,
            status = JiraIntakeStatusDto(
                baselineTaken = i.cursor != null,
                cursor = i.cursor,
                lastPollAt = i.lastPollAt,
                lastSuccessAt = i.lastSuccessAt,
                lastError = i.lastError,
                lastErrorAt = i.lastErrorAt,
                consecutiveFailures = i.consecutiveFailures,
                nextPollAt = i.nextPollAt,
                issuesStarted = i.issuesStarted,
                lastConnection = i.lastConnection,
                lastWarning = i.lastWarning,
            ),
            canManage = gate.currentUser().can(ProjectsPermissions.PROJECT_MANAGE, i.projectId.value),
            updatedAt = i.updatedAt,
            updatedBy = i.updatedBy?.let { ActorRefDto(it.kind.name, it.userId, it.displayName) },
        )
    }
}

data class JiraIntakeInputDto(
    @field:NotNull val enabled: Boolean? = null,
    @field:Min(JiraIntake.MIN_POLL_MINUTES.toLong()) @field:Max(JiraIntake.MAX_POLL_MINUTES.toLong()) val pollIntervalMinutes: Int? = null,
    val workflowId: UUID? = null,
)

data class JiraIntakeDto(
    val enabled: Boolean,
    val pollIntervalMinutes: Int,
    val workflowId: UUID?,
    val workflowName: String?,
    val triggerStatus: String?,
    val mode: String?,
    val jql: String?,
    val connection: ConnectionResolutionDto,
    val problems: List<String>,
    val status: JiraIntakeStatusDto,
    val canManage: Boolean,
    val updatedAt: Instant?,
    val updatedBy: ActorRefDto?,
)

data class JiraIntakeStatusDto(
    val baselineTaken: Boolean,
    val cursor: Instant?,
    val lastPollAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    val lastErrorAt: Instant?,
    val consecutiveFailures: Int,
    val nextPollAt: Instant?,
    val issuesStarted: Long,
    val lastConnection: String?,
    val lastWarning: String?,
)
