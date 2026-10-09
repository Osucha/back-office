package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.IntakeProposal
import pl.mos.backoffice.projects.domain.model.IntakeProposalId
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProposalState
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId

/**
 * Use case (ADR 0038): the caller's own personal intake rules and the proposals they create. Everything acts on the
 * caller's rules only (`connections.personal`); starting a proposal is an ordinary run start (`run.start` on the
 * workflow's project) by the caller.
 */
interface ManagePersonalIntake {
    fun rules(): List<RuleView>
    fun create(command: RuleCommand): RuleView
    fun update(id: IntakeRuleId, command: RuleCommand): RuleView
    fun delete(id: IntakeRuleId)

    /** Schedules a poll of the rule right away (a worker performs it). */
    fun pollNow(id: IntakeRuleId): RuleView

    fun proposals(state: ProposalState = ProposalState.NEW): List<ProposalView>

    /** Starts the rule's default workflow, or [workflowId], for the issue as the caller. */
    fun start(id: IntakeProposalId, workflowId: WorkflowDefinitionId? = null): ProposalView
    fun dismiss(id: IntakeProposalId): ProposalView
    fun restore(id: IntakeProposalId): ProposalView

    data class RuleCommand(
        val name: String,
        val connectionId: ConnectionId,
        val jql: String,
        val pollIntervalMinutes: Int = PersonalIntakeRule.DEFAULT_INTERVAL,
        val workflowId: WorkflowDefinitionId,
        val enabled: Boolean = true,
        val includeExisting: Boolean = true,
    )

    data class RuleView(
        val rule: PersonalIntakeRule,
        val connectionName: String?,
        val workflowName: String?,
        val projectId: ProjectId?,
        val projectKey: String?,
        val newProposals: Int,
    )

    data class ProposalView(
        val proposal: IntakeProposal,
        val ruleName: String?,
        val workflowId: WorkflowDefinitionId?,
        val workflowName: String?,
        /** `<site>/browse/<key>` of the rule's connection, when known. */
        val issueUrl: String?,
    )
}

/** System use case (ADR 0038): polling of personal intake rules by the scheduler and the work handler. */
interface PollPersonalIntake {
    fun due(): List<IntakeRuleId>

    /** One poll of the rule; returns how many proposals it created. */
    fun poll(id: IntakeRuleId): Int
}
