package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant

enum class RunStatus {
    PENDING, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED;

    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
    val active: Boolean get() = !terminal
}

enum class NodeExecutionStatus {
    RUNNING, WAITING, SUCCEEDED, FAILED, SKIPPED;

    val open: Boolean get() = this == RUNNING || this == WAITING
}

enum class ArtifactKind {
    SPEC, PLAN, TASKS, ANALYSIS, DIFF, REVIEW, LOG, JSON, MARKDOWN;

    companion object {
        /** Maps well-known artefact names (`spec`, `plan`, ...) to their kind; everything else is markdown or JSON. */
        fun forName(name: String, mediaType: String = "text/markdown"): ArtifactKind = when (name.lowercase()) {
            "spec" -> SPEC
            "plan" -> PLAN
            "tasks" -> TASKS
            "analysis" -> ANALYSIS
            "review" -> REVIEW
            "diff" -> DIFF
            "log" -> LOG
            else -> if (mediaType.contains("json")) JSON else MARKDOWN
        }
    }
}

enum class TriggerSource { MANUAL, API, CI, JIRA }

data class RunTrigger(val type: TriggerSource, val ciExecutionId: CiExecutionId? = null)

data class RunInput(
    val issueKey: String? = null,
    val parameters: Map<String, Any?> = emptyMap(),
    val ci: CiContext? = null,
)

/**
 * Why a run waits: a human gate, a CI signal, or the confirmation of an external write prepared by a TASK node
 * (ADR 0033; the node stays open and continues with the write or the `failure` port).
 */
enum class WaitKind { HUMAN_GATE, WAIT_FOR_CI, WRITE_CONFIRMATION }

/** Why a `WAITING` run is paused: an open approval (gate or write confirmation) or an expected CI signal. */
data class WaitingOn(
    val kind: WaitKind,
    val nodeId: String,
    val approvalId: ApprovalRequestId? = null,
    val signal: String? = null,
)

data class RunFailure(val nodeId: String?, val message: String, val retryable: Boolean = true)

data class Artifact(
    val id: ArtifactId,
    val nodeId: String,
    val name: String,
    val kind: ArtifactKind,
    val mediaType: String = "text/markdown",
    val content: String,
    val storageRef: String? = null,
    val createdAt: Instant,
) {
    val sizeBytes: Long get() = content.toByteArray(Charsets.UTF_8).size.toLong()

    fun withContent(newContent: String): Artifact = copy(content = newContent)

    /** Routing view exposed as `artifacts[name]` to decision expressions. */
    fun toStateMap(): Map<String, Any?> = mapOf("id" to id.toString(), "name" to name, "kind" to kind.name, "sizeBytes" to sizeBytes)

    companion object {
        const val MAX_CONTENT_BYTES = 2 * 1024 * 1024
    }
}

/** One attempt of one node; `port` is the output port the run left through. */
data class NodeExecution(
    val id: NodeExecutionId,
    val nodeId: String,
    val nodeName: String,
    val kind: NodeKind,
    val templateKey: String? = null,
    val attempt: Int,
    val status: NodeExecutionStatus,
    val port: String? = null,
    val startedAt: Instant?,
    val finishedAt: Instant? = null,
    val summary: String? = null,
    val confidence: Double? = null,
    val runnerJobId: RunnerJobId? = null,
    val usage: TokenUsage = TokenUsage.ZERO,
    val cost: Money = Money.ZERO,
    /** The cost at API list prices (ADR 0042): equal to [cost] unless a subscription paid for the step. */
    val apiCost: Money = Money.ZERO,
    val error: String? = null,
    val artifactIds: List<ArtifactId> = emptyList(),
    /** What a runner step did per repository (ADR 0034); empty for steps without repositories. */
    val repositories: List<RepositoryResult> = emptyList(),
) {
    val open: Boolean get() = status.open
}

/**
 * Aggregate root: one execution of a workflow definition version. The id doubles as the LangGraph
 * thread id. Invariants: only one node is `RUNNING` at a time; `WAITING` implies `waitingOn`; a terminal
 * run is immutable except for cost roll-ups; `maxVisits` is enforced per node.
 */
data class WorkflowRun(
    val id: WorkflowRunId,
    val projectId: ProjectId,
    val projectKey: String,
    val workflowId: WorkflowDefinitionId,
    val workflowVersion: Int,
    val definitionSnapshot: WorkflowDefinition,
    val trigger: RunTrigger,
    val input: RunInput,
    val status: RunStatus,
    val outcome: RunOutcome? = null,
    val currentNodeId: String? = null,
    val waitingOn: WaitingOn? = null,
    val nodes: List<NodeExecution> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val cost: Money = Money.ZERO,
    /** Sum of the steps' cost at API list prices (ADR 0042). */
    val apiCost: Money = Money.ZERO,
    val mergeRequest: MergeRequestSnapshot? = null,
    val review: ReviewReport? = null,
    val lastSignal: CiSignal? = null,
    val failure: RunFailure? = null,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Who started the run (the CI token actor for CI-triggered runs); kept for audit and self-approval rules. */
    val startedBy: Actor? = null,
    /**
     * Attempts granted on top of a node's `maxVisits` by a person retrying the run after the node used them up: the
     * loop guard stops runaway automatic loops, not a person's decision to try again.
     */
    val extraVisits: Map<String, Int> = emptyMap(),
) {
    val workflowName: String get() = definitionSnapshot.name
    val issueKey: String? get() = input.issueKey
    val openApprovalId: ApprovalRequestId? get() = waitingOn?.approvalId

    fun runningNode(): NodeExecution? = nodes.firstOrNull { it.status == NodeExecutionStatus.RUNNING }

    /** How many times [nodeId] has started in this run (attempts), for the `maxVisits` loop guard. */
    fun visits(nodeId: String): Int = nodes.count { it.nodeId == nodeId }

    /** The loop guard of [node] in this run: its `maxVisits` plus what retries by a person granted. */
    fun visitLimit(node: WorkflowNode): Int = node.maxVisits + (extraVisits[node.id] ?: 0)

    fun start(now: Instant): WorkflowRun {
        requireStatus(RunStatus.PENDING)
        return copy(status = RunStatus.RUNNING, startedAt = now)
    }

    /**
     * Records that [node] began. Idempotent: an already `RUNNING` execution of the same node (after a
     * restart) is re-attached instead of duplicated so runner job labels stay stable.
     */
    fun nodeStarted(node: WorkflowNode, now: Instant): WorkflowRun {
        requireMutable()
        val existing = nodes.firstOrNull { it.nodeId == node.id && it.status == NodeExecutionStatus.RUNNING }
        if (existing != null) return copy(currentNodeId = node.id, status = RunStatus.RUNNING, waitingOn = null)
        val other = runningNode()
        if (other != null) throw InvalidStateException("run $id already has running node ${other.nodeId}")
        val attempt = visits(node.id) + 1
        if (attempt > visitLimit(node)) throw InvalidStateException("node ${node.id} exceeded maxVisits=${visitLimit(node)}")
        val execution = NodeExecution(
            id = NodeExecutionId.new(),
            nodeId = node.id,
            nodeName = node.name,
            kind = node.kind,
            templateKey = (node as? WorkflowNode.Task)?.task?.templateKey,
            attempt = attempt,
            status = NodeExecutionStatus.RUNNING,
            startedAt = now,
        )
        return copy(status = RunStatus.RUNNING, currentNodeId = node.id, waitingOn = null, nodes = nodes + execution)
    }

    fun nodeFinished(
        nodeId: String,
        port: String?,
        now: Instant,
        summary: String? = null,
        confidence: Double? = null,
        usage: TokenUsage = TokenUsage.ZERO,
        cost: Money = Money.ZERO,
        runnerJobId: RunnerJobId? = null,
        artifactIds: List<ArtifactId> = emptyList(),
        repositories: List<RepositoryResult> = emptyList(),
        apiCost: Money? = null,
    ): WorkflowRun = finishNode(nodeId) {
        it.copy(
            repositories = repositories.ifEmpty { it.repositories },
            status = NodeExecutionStatus.SUCCEEDED,
            port = port,
            finishedAt = now,
            summary = summary,
            confidence = confidence,
            usage = usage,
            cost = cost,
            apiCost = apiCost ?: cost,
            runnerJobId = runnerJobId ?: it.runnerJobId,
            artifactIds = (it.artifactIds + artifactIds).distinct(),
        )
    }

    fun nodeFailed(
        nodeId: String,
        error: String,
        now: Instant,
        port: String? = null,
        usage: TokenUsage = TokenUsage.ZERO,
        cost: Money = Money.ZERO,
        runnerJobId: RunnerJobId? = null,
        repositories: List<RepositoryResult> = emptyList(),
        apiCost: Money? = null,
    ): WorkflowRun = finishNode(nodeId) {
        it.copy(
            repositories = repositories.ifEmpty { it.repositories },
            apiCost = apiCost ?: cost,
            status = NodeExecutionStatus.FAILED,
            port = port,
            finishedAt = now,
            error = error,
            usage = usage,
            cost = cost,
            runnerJobId = runnerJobId ?: it.runnerJobId,
        )
    }

    fun nodeSkipped(node: WorkflowNode, now: Instant, reason: String): WorkflowRun {
        requireMutable()
        val execution = NodeExecution(
            id = NodeExecutionId.new(), nodeId = node.id, nodeName = node.name, kind = node.kind,
            templateKey = (node as? WorkflowNode.Task)?.task?.templateKey, attempt = visits(node.id) + 1,
            status = NodeExecutionStatus.SKIPPED, startedAt = now, finishedAt = now, summary = reason,
        )
        return copy(nodes = nodes + execution)
    }

    /** Pauses the run before [node]: the node gets a `WAITING` execution and the approval is linked. */
    fun waitingForHuman(node: WorkflowNode, approvalId: ApprovalRequestId, now: Instant): WorkflowRun =
        pause(node, now, WaitingOn(WaitKind.HUMAN_GATE, node.id, approvalId = approvalId))

    /**
     * Pauses the RUNNING TASK [node] until a person confirms the external write it prepared (ADR 0033). Unlike a gate
     * the node execution is not closed on resume: the write (or the rejection) finishes it.
     */
    fun waitingForWriteConfirmation(node: WorkflowNode, approvalId: ApprovalRequestId, now: Instant): WorkflowRun {
        if (runningNode()?.nodeId != node.id) throw InvalidStateException("run $id is not running node ${node.id}, cannot ask to confirm its write")
        return pause(node, now, WaitingOn(WaitKind.WRITE_CONFIRMATION, node.id, approvalId = approvalId))
    }

    /** Pauses the run before [node] until CI reports [signal]. */
    fun waitingForCi(node: WorkflowNode, signal: String, now: Instant): WorkflowRun =
        pause(node, now, WaitingOn(WaitKind.WAIT_FOR_CI, node.id, signal = signal))

    private fun pause(node: WorkflowNode, now: Instant, waiting: WaitingOn): WorkflowRun {
        if (status != RunStatus.RUNNING) throw InvalidStateException("run $id is $status, cannot wait at ${node.id}")
        val running = runningNode()
        if (running != null && running.nodeId != node.id) {
            throw InvalidStateException("run $id has running node ${running.nodeId}, cannot wait at ${node.id}")
        }
        val updated = if (running != null) {
            nodes.map { if (it.id == running.id) it.copy(status = NodeExecutionStatus.WAITING) else it }
        } else {
            if (visits(node.id) + 1 > node.maxVisits) throw InvalidStateException("node ${node.id} exceeded maxVisits=${node.maxVisits}")
            nodes + NodeExecution(
                id = NodeExecutionId.new(), nodeId = node.id, nodeName = node.name, kind = node.kind,
                attempt = visits(node.id) + 1, status = NodeExecutionStatus.WAITING, startedAt = now,
            )
        }
        return copy(status = RunStatus.WAITING, currentNodeId = node.id, nodes = updated, waitingOn = waiting)
    }

    /**
     * Resumes after a decision. A gate's waiting execution is closed with the decision as summary and port; a write
     * confirmation's execution goes back to RUNNING (the engine writes, or fails the node on reject).
     */
    fun resumedWithDecision(decision: Decision, now: Instant): WorkflowRun {
        requireStatus(RunStatus.WAITING)
        return when (waitingOn?.kind) {
            WaitKind.HUMAN_GATE -> resume(now, decision.option.lowercase(), decision.describe())
            WaitKind.WRITE_CONFIRMATION -> copy(
                status = RunStatus.RUNNING,
                waitingOn = null,
                nodes = nodes.map { if (it.status == NodeExecutionStatus.WAITING) it.copy(status = NodeExecutionStatus.RUNNING) else it },
            )
            else -> throw InvalidStateException("run $id is not waiting for a human decision")
        }
    }

    /** Resumes after a CI signal; the waiting execution is closed with the signal status as port. */
    fun resumedWithSignal(signal: CiSignal, now: Instant): WorkflowRun {
        requireStatus(RunStatus.WAITING)
        val waiting = waitingOn
        if (waiting?.kind != WaitKind.WAIT_FOR_CI) throw InvalidStateException("run $id is not waiting for a CI signal")
        if (!waiting.signal.equals(signal.signal, ignoreCase = true)) {
            throw InvalidStateException("run $id waits for signal ${waiting.signal}, got ${signal.signal}")
        }
        val port = when (signal.status) {
            CiSignalStatus.SUCCESS -> WorkflowNode.PORT_SUCCESS
            CiSignalStatus.FAIL -> WorkflowNode.PORT_FAIL
        }
        val summary = "CI signal ${signal.signal}: ${signal.status}" + (signal.message?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
        return resume(now, port, summary).copy(lastSignal = signal)
    }

    /** Resumes a waiting node through its `timeout` port (scheduled sweep). */
    fun resumedByTimeout(now: Instant): WorkflowRun {
        requireStatus(RunStatus.WAITING)
        return resume(now, WorkflowNode.PORT_TIMEOUT, "timed out while waiting")
    }

    private fun resume(now: Instant, port: String, summary: String): WorkflowRun {
        val updated = nodes.map {
            if (it.status == NodeExecutionStatus.WAITING) it.copy(status = NodeExecutionStatus.SUCCEEDED, port = port, finishedAt = now, summary = summary) else it
        }
        return copy(status = RunStatus.RUNNING, nodes = updated, waitingOn = null)
    }

    fun succeeded(outcome: RunOutcome, now: Instant): WorkflowRun {
        requireMutable()
        return copy(status = RunStatus.SUCCEEDED, outcome = outcome, finishedAt = now, currentNodeId = null, waitingOn = null, nodes = closeOpenNodes(now))
    }

    fun failed(failure: RunFailure, now: Instant): WorkflowRun {
        requireMutable()
        val closed = nodes.map {
            if (it.open) it.copy(status = NodeExecutionStatus.FAILED, finishedAt = now, error = it.error ?: failure.message) else it
        }
        return copy(status = RunStatus.FAILED, outcome = RunOutcome.FAILURE, failure = failure, finishedAt = now, waitingOn = null, nodes = closed)
    }

    fun cancelled(now: Instant): WorkflowRun {
        requireMutable()
        return copy(status = RunStatus.CANCELLED, finishedAt = now, waitingOn = null, nodes = closeOpenNodes(now))
    }

    /**
     * Re-opens a `FAILED` run so the engine can continue from the failing node. A node that used up its `maxVisits`
     * gets that many attempts more, and so does any other node that used them up (the gate of a retry loop): the
     * person retrying decided the work should go on.
     */
    fun retry(now: Instant): WorkflowRun {
        requireStatus(RunStatus.FAILED)
        val from = retryNodeId()
        // every node that used up its attempts (the failing step and, for example, the gate of its retry loop)
        val granted = extraVisits + definitionSnapshot.nodes.filter { visits(it.id) >= visitLimit(it) }
            .associate { it.id to (extraVisits[it.id] ?: 0) + it.maxVisits }
        return copy(status = RunStatus.RUNNING, outcome = null, failure = null, finishedAt = null, currentNodeId = from, extraVisits = granted)
    }

    /** The node a retry starts from: the failing node, else the last failed execution, else START. */
    fun retryNodeId(): String? =
        failure?.nodeId ?: nodes.lastOrNull { it.status == NodeExecutionStatus.FAILED }?.nodeId ?: definitionSnapshot.startNode()?.id

    fun addArtifact(artifact: Artifact): WorkflowRun {
        requireMutable()
        if (artifact.sizeBytes > Artifact.MAX_CONTENT_BYTES) {
            throw InvalidStateException("artifact ${artifact.name} exceeds ${Artifact.MAX_CONTENT_BYTES} bytes")
        }
        return copy(artifacts = artifacts.filterNot { it.id == artifact.id } + artifact)
    }

    fun replaceArtifactContent(artifactId: ArtifactId, content: String): WorkflowRun {
        val existing = artifacts.firstOrNull { it.id == artifactId }
            ?: throw InvalidStateException("artifact $artifactId does not belong to run $id")
        return copy(artifacts = artifacts.map { if (it.id == artifactId) existing.withContent(content) else it })
    }

    fun artifact(id: ArtifactId): Artifact? = artifacts.firstOrNull { it.id == id }

    /** Latest artefact with a given name (later nodes win). */
    fun artifactNamed(name: String): Artifact? = artifacts.lastOrNull { it.name == name }

    /** The latest result per repository over all node executions, in first-seen order (ADR 0034). */
    val repositoryResults: List<RepositoryResult>
        get() = nodes.flatMap { it.repositories }.associateBy { it.key }.values.toList()

    fun withMergeRequest(mr: MergeRequestSnapshot): WorkflowRun = copy(mergeRequest = mr)

    fun withReview(report: ReviewReport): WorkflowRun = copy(review = report)

    /** Cost roll-up is allowed on terminal runs too. */
    fun rollUpCost(): WorkflowRun = copy(cost = Money.sum(nodes.map { it.cost }), apiCost = Money.sum(nodes.map { it.apiCost }))

    private fun finishNode(nodeId: String, update: (NodeExecution) -> NodeExecution): WorkflowRun {
        requireMutable()
        val open = nodes.lastOrNull { it.nodeId == nodeId && it.open }
            ?: throw InvalidStateException("run $id has no open execution of node $nodeId")
        val updated = nodes.map { if (it.id == open.id) update(it) else it }
        return copy(nodes = updated, cost = Money.sum(updated.map { it.cost }), apiCost = Money.sum(updated.map { it.apiCost }))
    }

    private fun closeOpenNodes(now: Instant): List<NodeExecution> =
        nodes.map { if (it.open) it.copy(status = NodeExecutionStatus.SKIPPED, finishedAt = now) else it }

    private fun requireMutable() {
        if (status.terminal) throw InvalidStateException("run $id is $status and cannot change")
    }

    private fun requireStatus(expected: RunStatus) {
        if (status != expected) throw InvalidStateException("run $id is $status, expected $expected")
    }

    companion object {
        fun create(
            project: Project,
            definition: WorkflowDefinition,
            trigger: RunTrigger,
            input: RunInput,
            now: Instant,
            id: WorkflowRunId = WorkflowRunId.new(),
            startedBy: Actor? = null,
        ): WorkflowRun = WorkflowRun(
            id = id,
            projectId = project.id,
            projectKey = project.key,
            workflowId = definition.id,
            workflowVersion = definition.version,
            definitionSnapshot = definition,
            trigger = trigger,
            input = input,
            status = RunStatus.PENDING,
            createdAt = now,
            startedBy = startedBy,
        )
    }
}
