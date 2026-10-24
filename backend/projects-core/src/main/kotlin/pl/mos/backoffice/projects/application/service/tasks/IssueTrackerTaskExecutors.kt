package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.IssueTracker
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.port.out.TransitionUnavailableException
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ExternalWriteAction
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WriteApprovalConfig
import pl.mos.backoffice.projects.domain.model.WriteApprovalMode
import pl.mos.backoffice.projects.domain.model.StepConnectionConfig
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.projects.domain.service.ConnectionChoice
import pl.mos.backoffice.shared.IntegrationException
import java.util.UUID
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.prompt.UntrustedText
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor

/** `FETCH_ISSUE`: loads the Jira issue named by the run input (or a parameter, `config.issueKeyFrom`) into `state.issue`. */
class FetchIssueTaskExecutor(
    private val jira: JiraConnectionAccess,
    private val json: JsonCodec,
) : TaskExecutorStrategy {

    override val executor = TaskExecutor.FETCH_ISSUE

    override fun execute(context: NodeContext, task: ResolvedTask): NodeResult {
        val from = task.config["issueKeyFrom"]?.toString()?.takeIf { it.isNotBlank() } ?: "input"
        val issueKey = if (from == "input") context.run.input.issueKey else context.run.input.parameters[from]?.toString()
        if (issueKey.isNullOrBlank()) throw ValidationException("run has no issue key (issueKeyFrom=$from)", "issueKey")
        context.log("fetching issue $issueKey")
        val issue = jira.open(context, executor, task.config, issueKey).fetch(issueKey)
        return NodeResult(
            port = WorkflowNode.PORT_SUCCESS,
            summary = "${issue.key}: ${issue.summary}",
            artifacts = listOf(NewArtifact("issue", ArtifactKind.JSON, "application/json", json.toJson(issue.toMap()))),
            stateUpdates = mapOf(RunStateKeys.ISSUE to issue.toMap()),
        )
    }
}

/**
 * Approval before external writes (ADR 0033), shared by the Jira write executors: the step prepares the exact write,
 * and unless its node configuration says `approval: NOT_REQUIRED` it returns [NodeResult.confirming]; the engine
 * pauses, a person confirms the preview, and [commit] performs exactly that write. Every write is audited
 * (`EXTERNAL_WRITE`, with the SHA-256 of the written content).
 */
abstract class ApprovedWriteExecutor(protected val jira: JiraConnectionAccess, private val audit: AuditPort) : TaskExecutorStrategy {

    /** The external write action this executor makes. */
    protected abstract val action: ExternalWriteAction

    /** Performs [write] exactly as given through [client] (already narrowed to this step and connection). */
    protected abstract fun write(context: NodeContext, task: ResolvedTask, write: PendingWrite, client: IssueTracker): NodeResult

    /**
     * Prepares the write, then asks for confirmation or writes right away (`NOT_REQUIRED`). ADR 0035: the preview names
     * the system connection (when the step may use it) and which identities the approver may choose.
     */
    protected fun writeOrConfirm(context: NodeContext, task: ResolvedTask, prepared: PendingWrite): NodeResult {
        val mode = WriteApprovalConfig.mode(task.config)
        val step = StepConnectionConfig.of(task.config)
        if (mode == WriteApprovalMode.REQUIRED) {
            // fail before asking anybody when no connection may make this write; a step that lets the approver write as
            // themselves can still be approved without a usable system connection
            val shared = if (step.systemAllowed) jira.choice(context, executor, task.config, prepared.issueKey, acting = null, identity = WriteIdentity.SYSTEM) else null
            val sharedConnection = shared?.connection
            if (sharedConnection == null && !step.personalAllowed) {
                throw IntegrationException("jira", shared?.message ?: "no connection may make this write", retryable = false)
            }
            if (sharedConnection != null) jira.require(context, executor, task.config, prepared.issueKey, acting = null, identity = WriteIdentity.SYSTEM)
            val write = prepared.copy(
                connectionId = sharedConnection?.id?.toString(), connectionName = sharedConnection?.name,
                connectionMode = step.mode, personalAllowed = step.personalAllowed, systemAllowed = sharedConnection != null,
            )
            val who = when (write.defaultIdentity) {
                WriteIdentity.PERSONAL -> "the personal connection of the approver" + (if (write.systemAllowed) " (or ${write.connectionName})" else "")
                WriteIdentity.SYSTEM -> write.connectionName + (if (write.personalAllowed) " (or the approver's personal connection)" else "")
            }
            context.log("waiting for confirmation before the Jira write: ${write.describe()}, as $who")
            return NodeResult.confirming(write, WriteApprovalConfig.approvers(task.config))
        }
        // NOT_REQUIRED: the run start is the only consent there is, so ACTING_USER means the person who started the run
        val acting = ActingUser.startedBy(context.run)
        val opened = jira.openWith(context, executor, task.config, prepared.issueKey, acting, identity = null)
        val write = prepared.copy(
            connectionId = opened.connection.id.toString(), connectionName = opened.connection.name,
            connectionMode = step.mode, personalAllowed = step.personalAllowed, systemAllowed = step.systemAllowed,
        )
        context.warn("approval NOT_REQUIRED: ${write.describe()} without confirmation")
        return perform(context, task, write, approvedBy = null, opened)
    }

    override fun commit(context: NodeContext, task: ResolvedTask, write: PendingWrite): NodeResult {
        if (write.action != action) return NodeResult.failure("this step cannot perform ${write.action}")
        val decision = context.stateMap(RunStateKeys.DECISION) ?: context.stateMap(RunStateKeys.LAST_DECISION)
        val approver = decision?.get("decidedBy")?.toString()
        val approverId = decision?.get("decidedByUserId")?.toString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val identity = decision?.get("writeAs")?.toString()?.let { w -> WriteIdentity.entries.firstOrNull { it.name == w } } ?: write.defaultIdentity
        if (!write.allows(identity)) {
            return NodeResult.failure("the write may not be made through the ${identity.name.lowercase()} identity; nothing was written (${write.describe()})")
        }
        // the approval is the approver's consent: ACTING_USER (or a switch to PERSONAL) writes as exactly that person
        val acting = approverId?.let { ActingUser(it, approver ?: "the approver", ActingUser.Via.APPROVAL) }
        val opened = try {
            jira.openWith(context, executor, task.config, write.issueKey, acting, identity)
        } catch (e: IntegrationException) {
            recordWrite(context, write, approver ?: "unknown", "FAILED", null)
            return NodeResult.failure("${e.message}; nothing was written (${write.describe()})")
        }
        return perform(context, task, write, approver ?: "unknown", opened)
    }

    private fun perform(context: NodeContext, task: ResolvedTask, write: PendingWrite, approvedBy: String?, opened: OpenedTracker): NodeResult {
        val result = try {
            write(context, task, write, opened.client)
        } catch (e: RuntimeException) {
            recordWrite(context, write, approvedBy, "FAILED", opened.connection)
            throw e
        }
        recordWrite(context, write, approvedBy, if (result.failed) "FAILED" else "WRITTEN", opened.connection)
        return result
    }

    private fun recordWrite(context: NodeContext, write: PendingWrite, approvedBy: String?, outcome: String, connection: Connection?) {
        audit.record(
            AuditEntry(
                OPERATION, ENTITY, context.run.id.toString(), projectId = context.project.id.value,
                details = mapOf(
                    "nodeId" to context.node.id, "action" to write.action.name, "issueKey" to write.issueKey, "toStatus" to write.toStatus,
                    "connection" to (connection?.name ?: write.connectionName), "contentSha256" to write.digest(),
                    "approval" to (if (approvedBy == null) "NOT_REQUIRED" else "APPROVED"), "approvedBy" to approvedBy, "result" to outcome,
                    "identity" to (if (connection?.personal == true) WriteIdentity.PERSONAL.name else WriteIdentity.SYSTEM.name),
                    "via" to (connection?.let { ConnectionChoice.describe(it) } ?: "none"),
                    "personalOwnerId" to connection?.takeIf { it.personal }?.ownerUserId?.toString(),
                ),
            ),
        )
    }

    companion object {
        const val OPERATION = "EXTERNAL_WRITE"
        const val ENTITY = "WorkflowRun"
    }
}

/**
 * `NOTIFY`: posts a rendered message (`config.messageTemplate` or the task user prompt) as a Jira comment. The message
 * is rendered once; with `approval: REQUIRED` (default) exactly that text is shown for confirmation and posted.
 */
class NotifyTaskExecutor(
    jira: JiraConnectionAccess,
    private val renderer: TemplateRenderer,
    private val templateModel: TemplateModelBuilder,
    audit: AuditPort = AuditPort.NOOP,
) : ApprovedWriteExecutor(jira, audit) {

    override val executor = TaskExecutor.NOTIFY
    override val action = ExternalWriteAction.JIRA_COMMENT

    override fun execute(context: NodeContext, task: ResolvedTask): NodeResult {
        val model = templateModel.build(context, runInputs = task.runInputs)
        val template = task.config["messageTemplate"]?.toString()?.takeIf { it.isNotBlank() } ?: task.prompt.user ?: DEFAULT_MESSAGE
        // plain text only (the adapter sends one ADF text node per paragraph: no links, mentions or macros);
        // hidden/bidi characters from issue or model text are removed and the size is capped
        val message = UntrustedText.sanitize(renderer.renderText(template, model), MAX_MESSAGE_CHARS)
        val key = context.stateMap(RunStateKeys.ISSUE)?.get("key")?.toString() ?: context.run.input.issueKey
            ?: return NodeResult.failure("no issue key to comment on")
        return writeOrConfirm(context, task, PendingWrite(ExternalWriteAction.JIRA_COMMENT, key, comment = message))
    }

    override fun write(context: NodeContext, task: ResolvedTask, write: PendingWrite, client: IssueTracker): NodeResult {
        client.comment(write.issueKey, write.comment ?: "")
        return NodeResult(port = WorkflowNode.PORT_SUCCESS, summary = "commented on ${write.issueKey}")
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 30_000
        const val DEFAULT_MESSAGE = "Back Office run {{run.short}} of workflow {{run.workflow}}: {{node.name}}."
    }
}

/**
 * `TRANSITION_ISSUE`: moves the Jira issue to `config.toStatus`. When the issue's workflow offers no such
 * transition (e.g. it is already there) the node fails, or succeeds with a note when `config.failIfMissing`
 * is `false`. With `approval: REQUIRED` (default) the move is confirmed first.
 */
class TransitionIssueTaskExecutor(jira: JiraConnectionAccess, audit: AuditPort = AuditPort.NOOP) : ApprovedWriteExecutor(jira, audit) {

    override val executor = TaskExecutor.TRANSITION_ISSUE
    override val action = ExternalWriteAction.JIRA_TRANSITION

    override fun execute(context: NodeContext, task: ResolvedTask): NodeResult {
        val key = context.stateMap(RunStateKeys.ISSUE)?.get("key")?.toString() ?: context.run.input.issueKey
            ?: return NodeResult.failure("no issue key to transition")
        val toStatus = task.config["toStatus"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return NodeResult.failure("config.toStatus is required for ${task.key}")
        return writeOrConfirm(context, task, PendingWrite(ExternalWriteAction.JIRA_TRANSITION, key, toStatus = toStatus))
    }

    override fun write(context: NodeContext, task: ResolvedTask, write: PendingWrite, client: IssueTracker): NodeResult {
        val key = write.issueKey
        val toStatus = write.toStatus ?: return NodeResult.failure("the confirmed transition has no target status")
        try {
            client.transition(key, toStatus)
        } catch (e: TransitionUnavailableException) {
            val failIfMissing = task.config["failIfMissing"]?.toString()?.toBooleanStrictOrNull() ?: true
            if (failIfMissing) return NodeResult.failure(e.message ?: "no transition to $toStatus")
            context.log("${e.message}; skipped (failIfMissing=false)")
            return NodeResult(port = WorkflowNode.PORT_SUCCESS, summary = "$key: no transition to $toStatus, skipped")
        }
        val issue = context.stateMap(RunStateKeys.ISSUE)?.toMutableMap()?.apply { put("status", toStatus) }
        return NodeResult(
            port = WorkflowNode.PORT_SUCCESS,
            summary = "$key -> $toStatus",
            stateUpdates = issue?.let { mapOf(RunStateKeys.ISSUE to it) } ?: emptyMap(),
        )
    }
}
