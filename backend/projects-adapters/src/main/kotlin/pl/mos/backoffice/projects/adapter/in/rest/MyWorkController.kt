package pl.mos.backoffice.projects.adapter.`in`.rest

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.ProposalView
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.RuleCommand
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.RuleView
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.IntakeProposalId
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProposalState
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant
import java.util.UUID

/** `my-work` tag of openapi.yaml (ADR 0038): the caller's personal intake rules and the run proposals they create. */
@RestController
@RequestMapping("/api/v1/me/intake")
class MyWorkController(private val intake: ManagePersonalIntake) {

    @GetMapping("/rules")
    fun rules(): List<IntakeRuleDto> = intake.rules().map(IntakeRuleDto::of)

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: IntakeRuleInputDto): IntakeRuleDto = IntakeRuleDto.of(intake.create(body.toCommand()))

    @PutMapping("/rules/{ruleId}")
    fun update(@PathVariable ruleId: UUID, @RequestBody body: IntakeRuleInputDto): IntakeRuleDto = IntakeRuleDto.of(intake.update(IntakeRuleId(ruleId), body.toCommand()))

    @DeleteMapping("/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable ruleId: UUID) = intake.delete(IntakeRuleId(ruleId))

    @PostMapping("/rules/{ruleId}/poll")
    fun poll(@PathVariable ruleId: UUID): IntakeRuleDto = IntakeRuleDto.of(intake.pollNow(IntakeRuleId(ruleId)))

    @GetMapping("/proposals")
    fun proposals(@RequestParam(required = false) state: String?): List<IntakeProposalDto> {
        val parsed = state?.let { s ->
            ProposalState.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) && it != ProposalState.BASELINE }
                ?: throw ValidationException("must be NEW, STARTED or DISMISSED", "state")
        } ?: ProposalState.NEW
        return intake.proposals(parsed).map(IntakeProposalDto::of)
    }

    @PostMapping("/proposals/{proposalId}/start")
    fun start(@PathVariable proposalId: UUID, @RequestBody(required = false) body: StartProposalDto?): IntakeProposalDto =
        IntakeProposalDto.of(intake.start(IntakeProposalId(proposalId), body?.workflowId?.let(::WorkflowDefinitionId)))

    @PostMapping("/proposals/{proposalId}/dismiss")
    fun dismiss(@PathVariable proposalId: UUID): IntakeProposalDto = IntakeProposalDto.of(intake.dismiss(IntakeProposalId(proposalId)))

    @PostMapping("/proposals/{proposalId}/restore")
    fun restore(@PathVariable proposalId: UUID): IntakeProposalDto = IntakeProposalDto.of(intake.restore(IntakeProposalId(proposalId)))
}

data class IntakeRuleInputDto(
    val name: String? = null,
    val connectionId: UUID? = null,
    val jql: String? = null,
    val pollIntervalMinutes: Int? = null,
    val workflowId: UUID? = null,
    val enabled: Boolean? = null,
    val includeExisting: Boolean? = null,
) {
    fun toCommand() = RuleCommand(
        name = name ?: "",
        connectionId = ConnectionId(connectionId ?: throw ValidationException("is required", "connectionId")),
        jql = jql ?: "",
        pollIntervalMinutes = pollIntervalMinutes ?: PersonalIntakeRule.DEFAULT_INTERVAL,
        workflowId = WorkflowDefinitionId(workflowId ?: throw ValidationException("is required", "workflowId")),
        enabled = enabled ?: true,
        includeExisting = includeExisting ?: true,
    )
}

data class IntakeRuleDto(
    val id: UUID,
    val name: String,
    val connectionId: UUID,
    val connectionName: String?,
    val jql: String,
    val pollIntervalMinutes: Int,
    val workflowId: UUID,
    val workflowName: String?,
    val projectId: UUID?,
    val projectKey: String?,
    val enabled: Boolean,
    val includeExisting: Boolean,
    val initialized: Boolean,
    val lastPollAt: Instant?,
    val lastSuccessAt: Instant?,
    val nextPollAt: Instant?,
    val lastError: String?,
    val consecutiveFailures: Int,
    val newProposals: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(v: RuleView) = v.rule.let { r ->
            IntakeRuleDto(
                r.id.value, r.name, r.connectionId.value, v.connectionName, r.jql, r.pollIntervalMinutes, r.workflowId.value, v.workflowName,
                v.projectId?.value, v.projectKey, r.enabled, r.includeExisting, r.initialized, r.lastPollAt, r.lastSuccessAt, r.nextPollAt,
                r.lastError, r.consecutiveFailures, v.newProposals, r.createdAt, r.updatedAt,
            )
        }
    }
}

data class IntakeProposalDto(
    val id: UUID,
    val ruleId: UUID,
    val ruleName: String?,
    val issueKey: String,
    val summary: String?,
    val issueType: String?,
    val status: String?,
    val priority: String?,
    val issueUrl: String?,
    val state: String,
    val detectedAt: Instant,
    val decidedAt: Instant?,
    val workflowId: UUID?,
    val workflowName: String?,
    val runId: UUID?,
) {
    companion object {
        fun of(v: ProposalView) = v.proposal.let { p ->
            IntakeProposalDto(
                p.id.value, p.ruleId.value, v.ruleName, p.issueKey, p.summary, p.issueType, p.status, p.priority, v.issueUrl, p.state.name,
                p.detectedAt, p.decidedAt, v.workflowId?.value, v.workflowName, p.runId?.value,
            )
        }
    }
}

data class StartProposalDto(val workflowId: UUID? = null)
