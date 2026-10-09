package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ApprovalQueries
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval.DecisionCommand
import pl.mos.backoffice.projects.application.port.`in`.WriteIdentityQuery
import pl.mos.backoffice.projects.application.port.`in`.WriteIdentityQuery.WriteIdentityOptions
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.event.ApprovalDecided
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.service.GateApprovalPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PublicOperation

/**
 * Implements [DecideApproval] and [ApprovalQueries]. Deciding needs `run.approve` on the project and must
 * satisfy the gate's approver rule ([GateApprovalPolicy]); `approve` needs every required checklist item.
 * The decision (with `decidedBy` and the ticked items) is stored first, artefact edits are applied to the
 * run, then the engine resumes; routing (the option's port) is done by the graph. Audited as
 * `RUN_GATE_DECIDED`, write confirmations (ADR 0033) as `RUN_WRITE_DECIDED` (a refused rule is audited with result `DENIED`).
 */
class ApprovalService(
    private val approvals: ApprovalRequestRepository,
    private val runs: WorkflowRunRepository,
    private val engine: WorkflowEngine,
    private val events: EventPublisher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    /** Personal connections of approvers (ADR 0035); NONE = nobody has one (tests without connections). */
    private val connections: ConnectionRepository? = null,
) : DecideApproval, ApprovalQueries, WriteIdentityQuery {

    override fun decide(approvalId: ApprovalRequestId, command: DecisionCommand): ApprovalRequest {
        val approval = load(approvalId)
        val user = gate.require(ProjectsPermissions.RUN_APPROVE, approval.projectId.value)
        val check = GateApprovalPolicy.check(approval, user)
        if (!check.allowed) {
            audit.record(
                AuditEntry(
                    OPERATION, ENTITY, approvalId.toString(), AuditResult.DENIED, approval.projectId.value,
                    mapOf("runId" to approval.runId.toString(), "nodeId" to approval.nodeId, "option" to command.option, "reason" to check.reason),
                ),
            )
            throw InvalidStateException("you cannot decide approval $approvalId: ${check.reason}")
        }
        val run = runs.findById(approval.runId) ?: throw NotFoundException("Run", approval.runId)
        if (run.openApprovalId != approval.id) {
            throw InvalidStateException("approval $approvalId is not the open approval of run ${run.id}")
        }
        val now = clock.now()
        val writeAs = approval.write?.let { write -> writeIdentityFor(write, command, user.userId?.value) }
        val decided = approval.decide(
            Decision(command.option, command.comment, command.editedArtifacts, now, checkedItems = command.checkedItems, decidedBy = user.actor, writeAs = writeAs),
        )
        val decision = decided.decision!!

        var updatedRun = run
        decision.editedArtifacts.forEach { edit -> updatedRun = updatedRun.replaceArtifactContent(edit.artifactId, edit.content) }
        updatedRun = updatedRun.resumedWithDecision(decision, now)

        approvals.save(decided)
        val saved = runs.save(updatedRun)
        val write = approval.write
        audit.record(
            AuditEntry(
                if (write != null) WRITE_OPERATION else OPERATION, ENTITY, approvalId.toString(), projectId = approval.projectId.value,
                details = mapOf(
                    "runId" to run.id.toString(), "nodeId" to approval.nodeId, "option" to decision.option,
                    "checkedItems" to decision.checkedItems, "commentLength" to (decision.comment?.length ?: 0),
                    "editedArtifacts" to decision.editedArtifacts.size,
                ) + (
                    write?.let {
                        mapOf(
                            "action" to it.action.name, "issueKey" to it.issueKey, "toStatus" to it.toStatus, "contentSha256" to it.digest(),
                            "writeAs" to decision.writeAs?.name,
                        )
                    } ?: emptyMap()
                    ),
            ),
        )
        events.publish(ApprovalDecided(decided.id, run.id, run.projectId, decision.option, now))
        engine.resumeWithDecision(saved, decision)
        return decided
    }

    override fun writeIdentity(approvalId: ApprovalRequestId): WriteIdentityOptions {
        val approval = load(approvalId)
        val user = gate.require(ProjectsPermissions.PROJECT_VIEW, approval.projectId.value)
        val write = approval.write ?: throw InvalidStateException("approval $approvalId is not a write confirmation")
        val personal = if (write.personalAllowed) personalConnection(write, user.userId?.value) else null
        val problem = when {
            !write.personalAllowed -> null
            user.userId == null -> "only a signed-in person can write with a personal connection"
            personal == null -> "you have no personal Jira connection with ${write.action.capability}" +
                (Connection.jiraProjectOf(write.issueKey)?.let { " for Jira project $it" } ?: "") +
                "; add one under My account → Connections (manual: ${ConnectionSelector.PERSONAL_GUIDE})"
            else -> null
        }
        return WriteIdentityOptions(write.defaultIdentity, write.systemAllowed, write.connectionName, write.personalAllowed, personal?.name, problem)
    }

    /**
     * ADR 0035: the identity of an approved write. Explicit choices must be allowed by the step; approving as yourself
     * needs a fitting personal connection; without a choice the step's default applies, and a step that writes only as
     * the approver cannot be approved by someone without one. Rejecting writes nothing, so it never needs an identity.
     */
    private fun writeIdentityFor(write: PendingWrite, command: DecisionCommand, userId: UUID?): WriteIdentity? {
        if (!command.option.equals(pl.mos.backoffice.projects.domain.model.GateSpec.APPROVE, ignoreCase = true)) return null
        val identity = command.writeAs ?: write.defaultIdentity
        if (!write.allows(identity)) {
            val reason = if (identity == WriteIdentity.PERSONAL) {
                "this step does not allow writing with your personal connection"
            } else {
                "this step writes only as the approving person (or no system connection can make this write)"
            }
            throw ValidationException(reason, "writeAs")
        }
        if (identity == WriteIdentity.PERSONAL && personalConnection(write, userId) == null) {
            throw InvalidStateException(
                "you have no personal Jira connection with ${write.action.capability}" +
                    (Connection.jiraProjectOf(write.issueKey)?.let { " for Jira project $it" } ?: "") +
                    (if (write.systemAllowed) "; approve with the system connection instead, or reject" else "; add one under My account → Connections, or reject") +
                    " (manual: ${ConnectionSelector.PERSONAL_GUIDE})",
            )
        }
        return identity
    }

    private fun personalConnection(write: PendingWrite, userId: UUID?): Connection? {
        val repo = connections ?: return null
        val owner = userId ?: return null
        val required = if (write.action == pl.mos.backoffice.projects.domain.model.ExternalWriteAction.JIRA_TRANSITION) {
            setOf(write.action.capability, pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_READ)
        } else {
            setOf(write.action.capability)
        }
        val key = Connection.jiraProjectOf(write.issueKey)
        return repo.findByOwner(owner).filter { it.personal && it.ownedBy(owner) && ConnectionSelector.fits(it, required, key) }
            .minWithOrNull(ConnectionSelector.preference(required, emptySet()))
    }

    override fun get(approvalId: ApprovalRequestId): ApprovalRequest {
        val approval = load(approvalId)
        gate.require(ProjectsPermissions.PROJECT_VIEW, approval.projectId.value)
        return approval
    }

    @PublicOperation("lists only approvals of projects the caller holds project.view on")
    override fun list(status: ApprovalStatus): List<ApprovalRequest> =
        approvals.findByStatus(status, gate.projectFilter(ProjectsPermissions.PROJECT_VIEW)).sortedByDescending { it.createdAt }

    private fun load(approvalId: ApprovalRequestId): ApprovalRequest =
        approvals.findById(approvalId) ?: throw NotFoundException("Approval", approvalId)

    companion object {
        const val OPERATION = "RUN_GATE_DECIDED"

        /** A write confirmation was approved or rejected (ADR 0033); details carry the SHA-256 of the previewed write. */
        const val WRITE_OPERATION = "RUN_WRITE_DECIDED"
        const val ENTITY = "ApprovalRequest"
    }
}
