package pl.mos.backoffice.projects.adapter.out.langgraph

import org.bsc.langgraph4j.CompileConfig
import org.bsc.langgraph4j.CompiledGraph
import org.bsc.langgraph4j.GraphDefinition
import org.bsc.langgraph4j.GraphInput
import org.bsc.langgraph4j.RunnableConfig
import org.bsc.langgraph4j.StateGraph
import org.bsc.langgraph4j.action.AsyncEdgeAction
import org.bsc.langgraph4j.action.AsyncNodeAction
import org.bsc.langgraph4j.action.EdgeAction
import org.bsc.langgraph4j.action.NodeAction
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver
import org.bsc.langgraph4j.state.AgentStateFactory
import org.slf4j.LoggerFactory
import pl.mos.backoffice.projects.adapter.out.langgraph.WorkflowState.Companion.NODE_CANCELLED
import pl.mos.backoffice.projects.adapter.out.langgraph.WorkflowState.Companion.NODE_FAILED
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.out.LogLevel
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunAdvance
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.nodes.NodeExecutors
import pl.mos.backoffice.projects.domain.model.ApprovalContext
import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.PendingWrite
import pl.mos.backoffice.projects.domain.model.WriteApprovalConfig
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.projects.domain.service.WorkflowGraph
import pl.mos.backoffice.shared.DomainException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [WorkflowEngine] on LangGraph4j (docs/architecture/03-pipelines-and-agents.md, ADR 0014), driven by the
 * PostgreSQL work queue (ADR 0023).
 *
 * The port methods never execute anything: they enqueue a `RUN_ADVANCE` work item (dedupe key
 * `RUN_ADVANCE:<runId>`, see [RunAdvance]), so a run is advanced only by the one worker that holds that
 * item's lease; checkpoints live in PostgreSQL, so any worker continues a run after a crash or scale-in.
 * [advance] (called by [RunAdvanceHandler] on a worker) invokes the graph on the worker's thread until it
 * interrupts (gate, CI wait, runner job) or ends. Re-delivered items are idempotent: a resume is applied only
 * when the checkpoint still waits at the node it targets, otherwise the run continues from its checkpoint.
 *
 * A workflow version compiles once into a `StateGraph<WorkflowState>`: one graph node per workflow node
 * (delegating to the [NodeExecutors]), `sys:failed`/`sys:cancelled` terminal nodes, conditional edges that route
 * on the output port (`lastPort`), and per TASK node an `<id>::await` node that is `interruptBefore`: a
 * runner-backed task submits its job and routes there, the job's continuation resumes it and the await node
 * maps `AgentRunner.result(jobId)` (no worker thread waits on a runner job). Per TASK node there is also an
 * `<id>::confirm` node, `interruptBefore` too (ADR 0033): a step that prepared an external write needing approval routes
 * there, the engine opens a write confirmation with the exact write, and the approve / reject decision resumes it; the
 * confirm node performs exactly the previewed write (no re-rendering) or fails the step through its `failure` port.
 * `HUMAN_GATE` and `WAIT_FOR_CI` nodes are `interruptBefore` and resume with `{decision|ci}`. Every invocation runs inside
 * `gate.asSystem("workflow engine")` (ADR 0017). Loops are bounded by `maxVisits`. Cancellation interrupts
 * the worker thread holding the run on whichever replica ([ClusterSignals] topic `workflow-run-cancel`).
 */
class LangGraphWorkflowEngine(
    private val runs: WorkflowRunRepository,
    private val projects: ProjectRepository,
    private val progress: RecordRunProgress,
    private val executors: NodeExecutors,
    private val renderer: TemplateRenderer,
    private val templateModel: TemplateModelBuilder,
    private val checkpointSaver: BaseCheckpointSaver,
    private val gate: AuthorizationGate,
    private val queue: WorkQueue,
    private val runner: AgentRunner,
    private val signals: ClusterSignals = ClusterSignals.local(),
) : WorkflowEngine {

    private val log = LoggerFactory.getLogger(LangGraphWorkflowEngine::class.java)
    private val compiled = ConcurrentHashMap<String, CompiledGraph<WorkflowState>>()
    private val inflight = ConcurrentHashMap<UUID, Thread>()

    init {
        signals.subscribe(CANCEL_TOPIC) { key -> runCatching { UUID.fromString(key) }.getOrNull()?.let { inflight[it]?.interrupt() } }
    }

    // ---- port: every call enqueues RUN_ADVANCE

    override fun start(run: WorkflowRun) = queue.enqueue(RunAdvance.request(run.id, mapOf(RunAdvance.KIND to RunAdvance.START)))

    override fun resumeWithDecision(run: WorkflowRun, decision: Decision) = queue.enqueue(
        RunAdvance.request(run.id, mapOf(RunAdvance.KIND to RunAdvance.RESUME_DECISION, RunAdvance.NODE_ID to run.waitingOn?.nodeId, RunAdvance.DECISION to WorkflowState.normalise(decision.toStateMap()))),
    )

    override fun resumeWithSignal(run: WorkflowRun, signal: CiSignal?) {
        val ci = signal?.toStateMap() ?: mapOf("signal" to run.waitingOn?.signal, "status" to RunStateKeys.CI_STATUS_TIMEOUT, "message" to "timed out")
        queue.enqueue(RunAdvance.request(run.id, mapOf(RunAdvance.KIND to RunAdvance.RESUME_CI, RunAdvance.NODE_ID to run.waitingOn?.nodeId, RunAdvance.CI to WorkflowState.normalise(ci))))
    }

    override fun retry(run: WorkflowRun, fromNodeId: String) =
        queue.enqueue(RunAdvance.request(run.id, mapOf(RunAdvance.KIND to RunAdvance.RETRY, RunAdvance.FROM_NODE to fromNodeId)))

    override fun cancel(runId: WorkflowRunId) {
        inflight[runId.value]?.interrupt()
        signals.publish(CANCEL_TOPIC, runId.toString())
        // a node waiting for a runner job: cancel the job too (its continuation then finds the run terminal)
        runCatching {
            val config = RunnableConfig.builder().threadId(runId.toString()).build()
            val run = runs.findById(runId) ?: return
            if (checkpointSaver.get(config).isEmpty) return
            val state = compiledFor(run.definitionSnapshot).getState(config).state()
            pendingJob(state)?.let { (_, jobId) -> runner.cancel(jobId) }
        }.onFailure { log.debug("could not cancel the runner job of run {}: {}", runId, it.toString()) }
    }

    /** Recovery scan: re-drives RUNNING runs; never overwrites a pending resume (`_merge=keep`). */
    override fun recoverAll(runs: List<WorkflowRun>) {
        runs.forEach { run -> queue.enqueue(RunAdvance.request(run.id, mapOf(RunAdvance.KIND to RunAdvance.RECOVER), keepExisting = true)) }
    }

    /** Waits until no graph invocation for [runId] is in flight on this replica (tests and diagnostics). */
    fun awaitIdle(runId: WorkflowRunId, timeoutMillis: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (inflight.containsKey(runId.value) && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
    }

    /** Runs advanced by this replica right now (`backoffice_runs_active`). */
    fun activeRuns(): Int = inflight.size

    // ---- worker side

    /** Handles one `RUN_ADVANCE` work item on the calling (worker) thread. */
    fun advance(item: WorkItem): WorkOutcome {
        val runId = item.payload[RunAdvance.RUN_ID]?.toString()?.let { runCatching { WorkflowRunId.of(it) }.getOrNull() }
            ?: return WorkOutcome.Dead("RUN_ADVANCE without a valid runId")
        @Suppress("UNCHECKED_CAST")
        val resume = item.payload[RunAdvance.RESUME] as? Map<String, Any?> ?: mapOf(RunAdvance.KIND to RunAdvance.RECOVER)
        val thread = Thread.currentThread()
        if (inflight.putIfAbsent(runId.value, thread) != null) {
            return WorkOutcome.Retry(java.time.Duration.ofSeconds(1), "run $runId is being advanced on this replica")
        }
        try {
            gate.asSystem(SYSTEM_REASON) { drive(runId, resume, item.attempt) }
        } finally {
            inflight.remove(runId.value, thread)
            Thread.interrupted()
        }
        return WorkOutcome.Done
    }

    private fun drive(runId: WorkflowRunId, resume: Map<String, Any?>, attempt: Int) {
        val run = runs.findById(runId) ?: return
        if (run.status.terminal) return
        val config = RunnableConfig.builder().threadId(runId.toString()).build()
        val kind = resume[RunAdvance.KIND]?.toString() ?: RunAdvance.RECOVER
        try {
            val graph = compiledFor(run.definitionSnapshot)
            val input = graphInput(run, graph, config, kind, resume, attempt)
            if (input != null) graph.invoke(input, config)
            if (Thread.currentThread().isInterrupted) return
            afterInvocation(runId, graph, config)
        } catch (e: Throwable) {
            if (e is InterruptedException || Thread.currentThread().isInterrupted) return
            failOnEngineError(runId, e)
        }
    }

    /**
     * What to feed the graph: the initial state or a retry update without a checkpoint, a retry update on the
     * first attempt of a retry, a resume (with the decision or CI result for interrupting nodes), or null when
     * the run is still waiting / already at the end.
     */
    private fun graphInput(
        run: WorkflowRun,
        graph: CompiledGraph<WorkflowState>,
        config: RunnableConfig,
        kind: String,
        resume: Map<String, Any?>,
        attempt: Int,
    ): GraphInput? {
        if (!checkpointSaver.get(config).isPresent) {
            return GraphInput.args(if (kind == RunAdvance.RETRY) retryUpdate(run, resume) else initialState(run))
        }
        val snapshot = graph.getState(config)
        val next = snapshot.next()
        val nextNode = next?.let { run.definitionSnapshot.node(it) }
        return when {
            kind == RunAdvance.RETRY && attempt <= 1 -> GraphInput.args(retryUpdate(run, resume))
            next.isNullOrBlank() || next == GraphDefinition.END -> null
            isAwait(next) -> if (runnerResultReady(run.id, snapshot.state())) GraphInput.resume() else null
            isConfirm(next) -> if (kind == RunAdvance.RESUME_DECISION && resume[RunAdvance.DECISION] != null) {
                GraphInput.resume(WorkflowState.normaliseUpdates(mapOf(RunStateKeys.DECISION to resume[RunAdvance.DECISION])))
            } else {
                null
            }
            nextNode?.interrupting == true -> resumeAt(nextNode, kind, resume)
            // the previous holder died mid-graph: continue from the last checkpoint
            else -> GraphInput.resume()
        }
    }

    /** An unexpected engine failure fails the (still active) run as retryable. */
    private fun failOnEngineError(runId: WorkflowRunId, e: Throwable) {
        val current = runs.findById(runId)
        if (current == null || current.status.terminal) return
        val message = (e.cause ?: e).message ?: e.javaClass.simpleName
        log.error("run failed with engine error id={} node={} error={}", runId, current.currentNodeId, message, e)
        runCatching { progress.appendLog(runId, current.currentNodeId, LogLevel.ERROR, "engine error: $message", current.projectId) }
        runCatching { progress.finished(runId, RunStatus.FAILED, RunOutcome.FAILURE, RunFailure(current.currentNodeId, message, retryable = true)) }
    }

    private fun resumeAt(node: WorkflowNode, kind: String, resume: Map<String, Any?>): GraphInput? = when {
        node is WorkflowNode.HumanGate && kind == RunAdvance.RESUME_DECISION && resume[RunAdvance.DECISION] != null ->
            GraphInput.resume(WorkflowState.normaliseUpdates(mapOf(RunStateKeys.DECISION to resume[RunAdvance.DECISION])))
        node is WorkflowNode.WaitForCi && kind == RunAdvance.RESUME_CI && resume[RunAdvance.CI] != null ->
            GraphInput.resume(WorkflowState.normaliseUpdates(mapOf(RunStateKeys.CI to resume[RunAdvance.CI])))
        // still waiting (recovery, stale or duplicate signal): only make sure the wait is recorded
        else -> null
    }

    private fun afterInvocation(runId: WorkflowRunId, graph: CompiledGraph<WorkflowState>, config: RunnableConfig) {
        val current = runs.findById(runId) ?: return
        if (current.status.terminal) return
        val snapshot = graph.getState(config)
        val next = snapshot.next()
        val state = snapshot.state()
        if (!next.isNullOrBlank() && next != GraphDefinition.END) {
            if (isAwait(next)) {
                log.debug("run {} waits for runner job {}", runId, pendingJob(state)?.second)
                return
            }
            if (isConfirm(next)) {
                openWriteConfirmation(current, next.removeSuffix(CONFIRM_SUFFIX), state)
                return
            }
            when (val node = current.definitionSnapshot.node(next)) {
                is WorkflowNode.HumanGate -> openApproval(current, node, state)
                is WorkflowNode.WaitForCi -> progress.waitingForCi(runId, node.id, node.ci.signal)
                else -> progress.finished(runId, RunStatus.FAILED, RunOutcome.FAILURE, RunFailure(next, "graph stopped before node $next", retryable = true))
            }
        } else {
            when (val outcome = state.string(RunStateKeys.RUN_OUTCOME)) {
                RunStateKeys.RUN_FAILED -> progress.finished(
                    runId, RunStatus.FAILED, RunOutcome.FAILURE,
                    RunFailure(state.string(RunStateKeys.FAILED_NODE) ?: current.currentNodeId, state.string(RunStateKeys.LAST_ERROR) ?: "node failed", retryable = true),
                )
                RunStateKeys.RUN_CANCELLED -> progress.finished(runId, RunStatus.CANCELLED)
                RunStateKeys.OUTCOME_SUCCESS -> progress.finished(runId, RunStatus.SUCCEEDED, RunOutcome.SUCCESS)
                RunStateKeys.OUTCOME_FAILURE -> progress.finished(runId, RunStatus.SUCCEEDED, RunOutcome.FAILURE)
                else -> progress.finished(runId, RunStatus.FAILED, RunOutcome.FAILURE, RunFailure(current.currentNodeId, "run ended without an END node (outcome $outcome)", retryable = false))
            }
        }
    }

    private fun runnerResultReady(runId: WorkflowRunId, state: WorkflowState): Boolean {
        val (nodeId, jobId) = pendingJob(state) ?: return true
        val ready = runner.result(jobId) != null
        if (!ready) log.debug("run {} node {}: runner job {} has no result yet", runId, nodeId, jobId)
        return ready
    }

    private fun pendingJob(state: WorkflowState): Pair<String, RunnerJobId>? {
        val pending = state.map(RunStateKeys.PENDING_JOB) ?: return null
        val nodeId = pending["nodeId"]?.toString() ?: return null
        val jobId = pending["jobId"]?.toString()?.let { runCatching { RunnerJobId.of(it) }.getOrNull() } ?: return null
        return nodeId to jobId
    }

    private fun initialState(run: WorkflowRun): Map<String, Any?> = WorkflowState.normaliseUpdates(
        mapOf(
            RunStateKeys.RUN_ID to run.id.toString(),
            RunStateKeys.ENTRY_NODE to (run.definitionSnapshot.startNode()?.id ?: error("workflow has no START node")),
            RunStateKeys.ARTIFACTS to run.artifacts.associate { it.name to it.id.toString() },
            RunStateKeys.LAST_OUTCOME to "NONE",
            RunStateKeys.LOOP_COUNTS to emptyMap<String, Any?>(),
        ),
    )

    private fun retryUpdate(run: WorkflowRun, resume: Map<String, Any?>): Map<String, Any?> {
        val update = HashMap<String, Any?>()
        update[RunStateKeys.RUN_ID] = run.id.toString()
        update[RunStateKeys.ENTRY_NODE] = resume[RunAdvance.FROM_NODE]?.toString() ?: run.currentNodeId
        update[RunStateKeys.LAST_OUTCOME] = "NONE"
        update[RunStateKeys.RUN_OUTCOME] = null
        update[RunStateKeys.FAILED_NODE] = null
        update[RunStateKeys.LAST_ERROR] = null
        update[RunStateKeys.LAST_PORT] = null
        update[RunStateKeys.PENDING_JOB] = null
        return update
    }

    private fun openApproval(run: WorkflowRun, node: WorkflowNode.HumanGate, state: WorkflowState) {
        val project = projects.findById(run.projectId) ?: throw NotFoundException("Project", run.projectId)
        val attempt = run.visits(node.id) + 1
        val model = templateModel.build(run, project, node, attempt, state.data())
        val question = renderer.render(node.gate.question.ifBlank { "Approve \"${node.name}\"?" }, model)
            .also { r -> r.warnings.forEach { progress.appendLog(run.id, node.id, LogLevel.WARN, "question template: $it", run.projectId) } }
            .text
        val shown = node.gate.showArtifacts.mapNotNull { run.artifactNamed(it)?.id }
        val lastFinished = run.nodes.lastOrNull { it.status == NodeExecutionStatus.SUCCEEDED || it.status == NodeExecutionStatus.FAILED }
        val context = ApprovalContext(
            summary = lastFinished?.summary ?: lastFinished?.error,
            confidence = state.double(RunStateKeys.CONFIDENCE)?.takeIf { it >= 0 },
            artifactIds = shown.ifEmpty { lastFinished?.artifactIds ?: emptyList() },
            mergeRequestUrl = run.mergeRequest?.url,
        )
        progress.waitingForHuman(run.id, node.id, question, node.gate.options, context)
    }

    /** ADR 0033: the TASK [nodeId] prepared an external write; ask for its confirmation with exactly that content. */
    private fun openWriteConfirmation(run: WorkflowRun, nodeId: String, state: WorkflowState) {
        val pending = state.map(RunStateKeys.PENDING_WRITE)
        val write = pendingWrite(pending)
        if (pending?.get("nodeId") != nodeId || write == null) {
            progress.finished(run.id, RunStatus.FAILED, RunOutcome.FAILURE, RunFailure(nodeId, "node $nodeId waits for a confirmation without a prepared write", retryable = true))
            return
        }
        @Suppress("UNCHECKED_CAST")
        val approvers = (pending["approvers"] as? Map<String, Any?>)?.let(::approversFromState) ?: WriteApprovalConfig.DEFAULT_APPROVERS
        val context = ApprovalContext(summary = write.describe(), mergeRequestUrl = run.mergeRequest?.url)
        progress.waitingForWriteConfirmation(run.id, nodeId, write, approvers, context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingWrite(pending: Map<String, Any?>?): PendingWrite? =
        (pending?.get("write") as? Map<String, Any?>)?.let { runCatching { PendingWrite.fromStateMap(it) }.getOrNull() }

    // ---- graph compilation

    fun compiledFor(definition: WorkflowDefinition): CompiledGraph<WorkflowState> =
        compiled.computeIfAbsent("${definition.id}:${definition.version}") { compile(definition) }

    private fun compile(definition: WorkflowDefinition): CompiledGraph<WorkflowState> {
        val graph = StateGraph(WorkflowState.SCHEMA, AgentStateFactory { WorkflowState(it) })
        val topology = WorkflowGraph(definition)
        val executable = definition.nodes.filter { it.kind != NodeKind.NOTE }
        val awaiting = executable.filter { it.kind == NodeKind.TASK }
        val mapping = (executable.map { it.id } + awaiting.map { awaitId(it.id) } + awaiting.map { confirmId(it.id) } + listOf(NODE_FAILED, NODE_CANCELLED))
            .associateWith { it } +
            (GraphDefinition.END to GraphDefinition.END)
        val startId = definition.startNode()?.id ?: error("workflow ${definition.name} has no START node")

        executable.forEach { node ->
            graph.addNode(node.id, node { state -> nodeBody(node, topology, state) })
            if (node.kind == NodeKind.END) {
                graph.addEdge(node.id, GraphDefinition.END)
            } else {
                graph.addConditionalEdges(node.id, edge { state -> route(node, topology, state) }, mapping)
            }
        }
        awaiting.forEach { node ->
            graph.addNode(awaitId(node.id), node { state -> awaitBody(node, topology, state) })
            graph.addConditionalEdges(awaitId(node.id), edge { state -> route(node, topology, state) }, mapping)
            graph.addNode(confirmId(node.id), node { state -> confirmBody(node, topology, state) })
            graph.addConditionalEdges(confirmId(node.id), edge { state -> route(node, topology, state) }, mapping)
        }
        graph.addNode(NODE_FAILED, node { mapOf(RunStateKeys.RUN_OUTCOME to RunStateKeys.RUN_FAILED) })
        graph.addNode(NODE_CANCELLED, node { mapOf(RunStateKeys.RUN_OUTCOME to RunStateKeys.RUN_CANCELLED) })
        graph.addEdge(NODE_FAILED, GraphDefinition.END)
        graph.addEdge(NODE_CANCELLED, GraphDefinition.END)
        graph.addConditionalEdges(
            GraphDefinition.START,
            edge { state -> state.string(RunStateKeys.ENTRY_NODE)?.takeIf { id -> executable.any { it.id == id } } ?: startId },
            mapping,
        )

        val config = CompileConfig.builder()
            .checkpointSaver(checkpointSaver)
            .interruptsBefore(executable.filter { it.interrupting }.map { it.id } + awaiting.map { awaitId(it.id) } + awaiting.map { confirmId(it.id) })
            .releaseThread(false)
            .recursionLimit(RECURSION_LIMIT)
            .build()
        return graph.compile(config)
    }

    private fun node(body: (WorkflowState) -> Map<String, Any?>): AsyncNodeAction<WorkflowState> =
        AsyncNodeAction.node_async(NodeAction { state -> WorkflowState.normaliseUpdates(body(state)) })

    private fun edge(body: (WorkflowState) -> String): AsyncEdgeAction<WorkflowState> =
        AsyncEdgeAction.edge_async(EdgeAction { state -> body(state) })

    /** Conditional edge: the port recorded by the node body, resolved through the definition's edges. */
    private fun route(node: WorkflowNode, topology: WorkflowGraph, state: WorkflowState): String {
        val port = state.string(RunStateKeys.LAST_PORT) ?: WorkflowNode.PORT_FAILURE
        if (port == NODE_FAILED || port == NODE_CANCELLED) return port
        if (port == PORT_AWAIT) return awaitId(node.id)
        if (port == PORT_CONFIRM) return confirmId(node.id)
        return when (val route = topology.route(node.id, port)) {
            is WorkflowGraph.Route.Next -> route.nodeId
            is WorkflowGraph.Route.Fail -> NODE_FAILED
            is WorkflowGraph.Route.Cancel -> NODE_CANCELLED
        }
    }

    // ---- node bodies

    private fun nodeBody(node: WorkflowNode, topology: WorkflowGraph, state: WorkflowState): Map<String, Any?> {
        val runId = WorkflowRunId.of(state.string(RunStateKeys.RUN_ID) ?: error("state has no runId"))
        if (state.visits(node.id) >= node.maxVisits) {
            val error = "node ${node.id} exceeded maxVisits=${node.maxVisits}"
            progress.appendLog(runId, node.id, LogLevel.ERROR, error)
            return failUpdates(node, error)
        }
        val loopCounts = HashMap<String, Any?>(state.map(RunStateKeys.LOOP_COUNTS) ?: emptyMap())
        loopCounts[node.id] = state.visits(node.id) + 1

        val run = if (node.interrupting) {
            runs.findById(runId) ?: throw NotFoundException("Run", runId)
        } else {
            try {
                progress.nodeStarted(runId, node.id)
            } catch (e: DomainException) {
                progress.appendLog(runId, node.id, LogLevel.ERROR, e.message ?: "cannot start node")
                return failUpdates(node, e.message ?: "cannot start node") + (RunStateKeys.LOOP_COUNTS to loopCounts)
            }
        }
        val result = runExecutor(run, node, state) { context -> executors.forKind(node.kind).execute(context) }
        val awaitingJob = result.awaitingJob
        if (awaitingJob != null && !result.failed) {
            progress.appendLog(runId, node.id, LogLevel.INFO, "waiting for runner job $awaitingJob", run.projectId)
            return mapOf(
                RunStateKeys.LOOP_COUNTS to loopCounts,
                RunStateKeys.PENDING_JOB to mapOf("nodeId" to node.id, "jobId" to awaitingJob.toString()),
                RunStateKeys.LAST_PORT to PORT_AWAIT,
            )
        }
        val write = result.pendingWrite
        if (write != null && !result.failed) {
            progress.appendLog(runId, node.id, LogLevel.INFO, "waiting for confirmation: ${write.describe()}", run.projectId)
            return mapOf(
                RunStateKeys.LOOP_COUNTS to loopCounts,
                RunStateKeys.PENDING_WRITE to mapOf(
                    "nodeId" to node.id, "write" to write.toStateMap(),
                    "approvers" to approversToState(result.writeApprovers ?: WriteApprovalConfig.DEFAULT_APPROVERS),
                ),
                RunStateKeys.LAST_PORT to PORT_CONFIRM,
            )
        }
        return applyResult(node, topology, state, run, result) + (RunStateKeys.LOOP_COUNTS to loopCounts)
    }

    /**
     * `<id>::confirm` (ADR 0033): the confirmation of the write [node] prepared was decided. Approve performs exactly the
     * stored write through `NodeExecutor.commit`; reject (or anything else) writes nothing and fails the step through
     * its `failure` port.
     */
    private fun confirmBody(node: WorkflowNode, topology: WorkflowGraph, state: WorkflowState): Map<String, Any?> {
        val runId = WorkflowRunId.of(state.string(RunStateKeys.RUN_ID) ?: error("state has no runId"))
        val run = runs.findById(runId) ?: throw NotFoundException("Run", runId)
        val pending = state.map(RunStateKeys.PENDING_WRITE)
        val decision = state.map(RunStateKeys.DECISION)
        val write = pendingWrite(pending)
        val by = decision?.get("decidedBy")?.toString() ?: "unknown"
        val result = when {
            pending?.get("nodeId") != node.id || write == null -> NodeResult.failure("node ${node.id} resumed without a prepared write")
            decision == null -> NodeResult.failure("node ${node.id} resumed without a decision on its write")
            decision["option"]?.toString().equals(GateSpec.APPROVE, ignoreCase = true) -> {
                progress.appendLog(runId, node.id, LogLevel.INFO, "write approved by $by: ${write.describe()}", run.projectId)
                runExecutor(run, node, state) { context -> executors.forKind(node.kind).commit(context, write) }
            }
            else -> {
                val comment = decision["comment"]?.toString()?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
                NodeResult.failure("write rejected by $by, nothing was written (${write.describe()})$comment")
            }
        }
        return applyResult(node, topology, state, run, result) + mapOf(
            RunStateKeys.PENDING_WRITE to null, RunStateKeys.DECISION to null, RunStateKeys.LAST_DECISION to decision,
        )
    }

    /** `<id>::await`: the runner job of [node] finished; map its result like any node completion. */
    private fun awaitBody(node: WorkflowNode, topology: WorkflowGraph, state: WorkflowState): Map<String, Any?> {
        val runId = WorkflowRunId.of(state.string(RunStateKeys.RUN_ID) ?: error("state has no runId"))
        val run = runs.findById(runId) ?: throw NotFoundException("Run", runId)
        val pending = pendingJob(state)
        val result = if (pending == null || pending.first != node.id) {
            NodeResult.failure("node ${node.id} resumed without a pending runner job")
        } else {
            runExecutor(run, node, state) { context -> executors.forKind(node.kind).resume(context, pending.second) }
        }
        return applyResult(node, topology, state, run, result) + (RunStateKeys.PENDING_JOB to null)
    }

    private fun runExecutor(run: WorkflowRun, node: WorkflowNode, state: WorkflowState, call: (NodeContext) -> NodeResult): NodeResult {
        val project = projects.findById(run.projectId) ?: throw NotFoundException("Project", run.projectId)
        val execution = run.nodes.lastOrNull { it.nodeId == node.id } ?: run.nodes.last()
        val warn: (String) -> Unit = { message -> progress.appendLog(run.id, node.id, LogLevel.WARN, message, run.projectId) }
        val context = NodeContext(run, project, node, execution, state.data(), warn) { message ->
            progress.appendLog(run.id, node.id, LogLevel.INFO, message, run.projectId)
        }
        return try {
            call(context)
        } catch (e: DomainException) {
            NodeResult.failure(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            log.error("node executor crashed run={} node={}", run.id, node.id, e)
            NodeResult.failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun applyResult(node: WorkflowNode, topology: WorkflowGraph, state: WorkflowState, run: WorkflowRun, result: NodeResult): Map<String, Any?> {
        val runId = run.id
        val updates = HashMap<String, Any?>()
        if (result.failed) {
            val error = result.error ?: "node failed"
            val port = result.port ?: WorkflowNode.PORT_FAILURE
            if (!node.interrupting) progress.nodeFailed(runId, node.id, error, port, result.usage, result.cost, result.runnerJobId, result.repositories, result.apiCost)
            progress.appendLog(runId, node.id, LogLevel.ERROR, error, run.projectId)
            updates.putAll(result.stateUpdates)
            updates[RunStateKeys.LAST_OUTCOME] = RunStateKeys.OUTCOME_FAILURE
            updates[RunStateKeys.LAST_ERROR] = error
            updates[RunStateKeys.FAILED_NODE] = node.id
            updates[RunStateKeys.LAST_PORT] = port
            return updates
        }

        if (!node.interrupting) {
            val saved = progress.nodeFinished(
                runId, node.id,
                RecordRunProgress.NodeCompletion(
                    port = result.port, summary = result.summary, confidence = result.confidence, usage = result.usage, cost = result.cost,
                    apiCost = result.apiCost, runnerJobId = result.runnerJobId, artifacts = result.artifacts, mergeRequest = result.mergeRequest, review = result.review,
                    repositories = result.repositories,
                ),
            )
            val artifactIndex = HashMap<String, Any?>(state.map(RunStateKeys.ARTIFACTS) ?: emptyMap())
            result.artifacts.forEach { a -> saved.artifactNamed(a.name)?.let { artifactIndex[a.name] = it.id.toString() } }
            updates[RunStateKeys.ARTIFACTS] = artifactIndex
            result.confidence?.let { updates[RunStateKeys.CONFIDENCE] = it }
        }
        updates.putAll(result.stateUpdates)
        updates[RunStateKeys.LAST_OUTCOME] = RunStateKeys.OUTCOME_SUCCESS
        updates[RunStateKeys.LAST_ERROR] = null
        result.outcome?.let { updates[RunStateKeys.RUN_OUTCOME] = it.name }
        val port = result.port
        if (node.kind != NodeKind.END && port != null) {
            when (val route = topology.route(node.id, port)) {
                is WorkflowGraph.Route.Fail -> {
                    progress.appendLog(runId, node.id, LogLevel.ERROR, route.reason, run.projectId)
                    updates[RunStateKeys.LAST_ERROR] = route.reason
                    updates[RunStateKeys.FAILED_NODE] = node.id
                }
                is WorkflowGraph.Route.Cancel -> progress.appendLog(runId, node.id, LogLevel.WARN, route.reason, run.projectId)
                is WorkflowGraph.Route.Next -> Unit
            }
        }
        updates[RunStateKeys.LAST_PORT] = port
        return updates
    }

    private fun failUpdates(node: WorkflowNode, error: String): Map<String, Any?> = mapOf(
        RunStateKeys.LAST_OUTCOME to RunStateKeys.OUTCOME_FAILURE,
        RunStateKeys.LAST_ERROR to error,
        RunStateKeys.FAILED_NODE to node.id,
        RunStateKeys.LAST_PORT to NODE_FAILED,
    )

    companion object {
        const val RECURSION_LIMIT = 500
        const val SYSTEM_REASON = "workflow engine"
        const val CANCEL_TOPIC = "workflow-run-cancel"
        const val AWAIT_SUFFIX = "::await"
        const val CONFIRM_SUFFIX = "::confirm"
        /** Internal port: the node prepared an external write and routes to its confirm node (ADR 0033). */
        const val PORT_CONFIRM = "sys:confirm"
        /** Internal port: the node submitted a runner job and routes to its await node. */
        const val PORT_AWAIT = "sys:await"
        private const val POLL_MILLIS = 50L

        fun awaitId(nodeId: String): String = nodeId + AWAIT_SUFFIX

        fun isAwait(graphNodeId: String?): Boolean = graphNodeId?.endsWith(AWAIT_SUFFIX) == true

        fun confirmId(nodeId: String): String = nodeId + CONFIRM_SUFFIX

        fun isConfirm(graphNodeId: String?): Boolean = graphNodeId?.endsWith(CONFIRM_SUFFIX) == true

        fun approversToState(rule: GateApprovers): Map<String, Any?> = mapOf(
            "projectRoles" to rule.projectRoles, "users" to rule.users.map { it.toString() }, "accountable" to rule.accountable,
            "forbidSelfApproval" to rule.forbidSelfApproval,
        )

        fun approversFromState(map: Map<String, Any?>): GateApprovers = GateApprovers(
            projectRoles = (map["projectRoles"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            users = (map["users"] as? List<*>)?.mapNotNull { runCatching { UUID.fromString(it.toString()) }.getOrNull() } ?: emptyList(),
            accountable = map["accountable"]?.toString(),
            forbidSelfApproval = map["forbidSelfApproval"] == true,
        )
    }
}
