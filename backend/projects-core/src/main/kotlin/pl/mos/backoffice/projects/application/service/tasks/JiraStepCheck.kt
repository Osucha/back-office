package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionMode
import pl.mos.backoffice.projects.domain.model.StepConnectionConfig
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WriteApprovalConfig
import pl.mos.backoffice.projects.domain.model.WriteApprovalMode
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.SelectionStatus
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.TaskCatalog

/**
 * Checks the Jira steps of a workflow (ADR 0030, ADR 0033), for the designer validation and before a run starts:
 * which connection each step would use, and the write-approval setting of steps that write to Jira.
 *
 * - At save time ([designIssues]) a missing connection is a WARNING (a workflow must save on an installation without
 *   Jira, e.g. the seeded ones); an invalid pin or approval setting is an error; `approval: NOT_REQUIRED` on a write
 *   step and an over-privileged connection are warnings.
 * - When a run starts ([runBlockers]) a step without a usable connection is an error with a link to the guide.
 */
class JiraStepCheck(private val catalog: TaskCatalog, private val access: JiraConnectionAccess) {

    data class Issues(val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>)

    fun designIssues(project: Project, nodes: List<WorkflowNode>, triggers: List<Trigger> = emptyList()): Issues {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()
        val watched = triggers.any { it.type == TriggerType.JIRA_TRANSITION }
        jiraSteps(project, nodes).forEach { step ->
            connectionIssue(step, watched, errors, warnings)
            if (step.required.any { it.write }) approvalIssues(step, errors, warnings)
        }
        return Issues(errors, warnings)
    }

    /**
     * Messages of the Jira steps that cannot run: no qualifying connection or an unusable pin. ADR 0035: a step that acts
     * as a person is checked against [startedBy] when the run itself is that person's consent (read steps and writes
     * without approval); writes that wait for approval are decided by the approver later.
     */
    fun runBlockers(project: Project, nodes: List<WorkflowNode>, startedBy: Actor? = null): List<String> = jiraSteps(project, nodes).mapNotNull { step ->
        val choice = when {
            step.pinError != null -> return@mapNotNull "step \"${step.node.name}\" (${step.node.id}): ${step.pinError}"
            step.connection?.mode == ConnectionMode.ACTING_USER -> actingChoice(project, step, startedBy) ?: return@mapNotNull null
            else -> step.choice
        }
        if (choice != null && !choice.usable) "step \"${step.node.name}\" (${step.node.id}) cannot run: ${choice.message}" else null
    }

    /** The choice of an `ACTING_USER` step at run start, or null when it is decided later (a write that waits for approval). */
    private fun actingChoice(project: Project, step: JiraStep, startedBy: Actor?): pl.mos.backoffice.projects.domain.service.ConnectionChoice? {
        val config = step.connection ?: return null
        val waitsForApproval = step.required.any { it.write } &&
            runCatching { WriteApprovalConfig.mode(step.config) }.getOrDefault(WriteApprovalMode.REQUIRED) == WriteApprovalMode.REQUIRED
        if (waitsForApproval) return null
        val acting = startedBy?.let { actor ->
            actor.userId?.takeIf { actor.kind == pl.mos.backoffice.shared.security.PrincipalKind.USER || actor.kind == pl.mos.backoffice.shared.security.PrincipalKind.PERSONAL_TOKEN }
                ?.let { ActingUser(it, actor.displayName, ActingUser.Via.RUN_START) }
        }
        return access.resolve(project, step.required, config, null, acting)
    }

    private data class JiraStep(
        val node: WorkflowNode.Task,
        val required: Set<ConnectionCapability>,
        val config: Map<String, Any?>,
        val choice: pl.mos.backoffice.projects.domain.service.ConnectionChoice?,
        val pinError: String?,
        val connection: StepConnectionConfig? = null,
    )

    private fun jiraSteps(project: Project, nodes: List<WorkflowNode>): List<JiraStep> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.mapNotNull { node ->
            val task = runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull() ?: return@mapNotNull null
            val required = ConnectionCapability.requiredBy(task.executor)
            if (required.isEmpty()) return@mapNotNull null
            try {
                val connection = StepConnectionConfig.of(task.config)
                // ACTING_USER: the shared connection is only the fallback (or none); it is checked when fallbackToSystem is set
                val choice = if (connection.mode == ConnectionMode.ACTING_USER) {
                    if (connection.fallbackToSystem) access.chooseShared(project, required, StepConnectionConfig()) else null
                } else {
                    access.chooseShared(project, required, connection)
                }
                JiraStep(node, required, task.config, choice, null, connection)
            } catch (e: ValidationException) {
                JiraStep(node, required, task.config, null, e.errors.firstOrNull()?.message ?: e.message ?: "invalid connection setting")
            }
        }

    private fun connectionIssue(step: JiraStep, watched: Boolean, errors: MutableList<ValidationIssue>, warnings: MutableList<ValidationIssue>) {
        if (step.pinError != null) {
            errors += ValidationIssue(nodeId = step.node.id, field = FIELD_JIRA_CONNECTION, message = step.pinError)
            return
        }
        val cfg = step.connection
        if (cfg != null && cfg.mode == ConnectionMode.ACTING_USER) {
            if (!cfg.fallbackToSystem && watched && step.required.none { it.write }) {
                warnings += ValidationIssue(
                    nodeId = step.node.id, field = FIELD_JIRA_CONNECTION,
                    message = "this step acts as the person who starts the run, but runs started by the Jira intake watcher have no such person; " +
                        "they fail here unless fallbackToSystem is set (manual: ${pl.mos.backoffice.projects.domain.service.ConnectionSelector.PERSONAL_GUIDE})",
                )
            }
            if (step.choice == null) return
        } else if (cfg?.allowActingUser == true && step.required.none { it.write }) {
            warnings += ValidationIssue(nodeId = step.node.id, field = FIELD_ALLOW_ACTING_USER, message = "allowActingUser only applies to steps that write to Jira")
        }
        val choice = step.choice ?: return
        when (choice.status) {
            SelectionStatus.OK -> Unit
            SelectionStatus.OVER_PRIVILEGED -> warnings += ValidationIssue(nodeId = step.node.id, field = FIELD_JIRA_CONNECTION, message = choice.message)
            // ADR 0030 amendment: saving never depends on credentials; the run start and the step itself refuse
            SelectionStatus.NONE, SelectionStatus.PINNED_NOT_FOUND, SelectionStatus.NO_ACTING_USER, SelectionStatus.NO_PERSONAL_CONNECTION -> warnings += ValidationIssue(
                nodeId = step.node.id, field = FIELD_JIRA_CONNECTION,
                message = "${choice.message}; the workflow can be saved, but runs refuse to start until a connection exists",
            )
            SelectionStatus.PINNED_INSUFFICIENT -> errors += ValidationIssue(nodeId = step.node.id, field = FIELD_JIRA_CONNECTION, message = choice.message)
        }
    }

    private fun approvalIssues(step: JiraStep, errors: MutableList<ValidationIssue>, warnings: MutableList<ValidationIssue>) {
        val mode = try {
            WriteApprovalConfig.mode(step.config)
        } catch (e: ValidationException) {
            errors += ValidationIssue(nodeId = step.node.id, field = FIELD_APPROVAL, message = e.errors.first().message)
            return
        }
        if (mode == WriteApprovalMode.NOT_REQUIRED) {
            warnings += ValidationIssue(
                nodeId = step.node.id, field = FIELD_APPROVAL,
                message = "this step writes to Jira (${step.required.filter { it.write }.sorted().joinToString()}) without anybody confirming the exact " +
                    "write first; text from issues or model output can reach it (prompt injection) - keep approval REQUIRED unless the content " +
                    "is fixed (manual: $GUIDE)",
            )
        }
        val approvers = try {
            WriteApprovalConfig.approvers(step.config)
        } catch (e: ValidationException) {
            errors += ValidationIssue(nodeId = step.node.id, field = FIELD_APPROVERS, message = e.errors.first().message)
            return
        }
        approvers.projectRoles.filterNot { GateApprovers.ROLE_PATTERN.matches(it) }.forEach {
            errors += ValidationIssue(nodeId = step.node.id, field = FIELD_APPROVERS, message = "role key $it must match ${GateApprovers.ROLE_PATTERN.pattern}")
        }
        val accountable = approvers.accountable
        if (accountable != null && approvers.projectRoles.isNotEmpty() && accountable !in approvers.projectRoles) {
            errors += ValidationIssue(nodeId = step.node.id, field = FIELD_APPROVERS, message = "accountable role $accountable must be one of the approver roles ${approvers.projectRoles}")
        }
    }

    companion object {
        const val FIELD_JIRA_CONNECTION = "task.config.jiraConnection"
        const val FIELD_APPROVAL = "task.config.approval"
        const val FIELD_APPROVERS = "task.config.approvers"
        const val FIELD_ALLOW_ACTING_USER = "task.config.allowActingUser"
        const val GUIDE = "integrations-jira#jira-write-approval"
        const val CONNECTIONS_GUIDE = ConnectionSelector.GUIDE
    }
}
