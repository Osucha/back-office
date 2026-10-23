package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.TaskOverrides
import java.time.Instant
import java.util.UUID

enum class NodeKind { START, END, TASK, DECISION, HUMAN_GATE, WAIT_FOR_CI, NOTE }

enum class RunOutcome { SUCCESS, FAILURE }

enum class TriggerType { MANUAL, JIRA_TRANSITION, API, CI, SCHEDULE }

/** Lower-case slug of up to 40 characters: role keys, node ids and CI signal names. */
private const val SLUG_PATTERN = "^[a-z][a-z0-9-]{0,39}$"

/** [intervalMinutes] (ADR 0052): a SCHEDULE trigger starts the workflow every that many minutes, never while a run of it is active. */
data class Trigger(val type: TriggerType, val status: String? = null, val intervalMinutes: Int? = null) {
    init {
        if (type == TriggerType.SCHEDULE && (intervalMinutes == null || intervalMinutes < MIN_INTERVAL_MINUTES)) {
            throw pl.mos.backoffice.shared.ValidationException("a schedule needs intervalMinutes of at least $MIN_INTERVAL_MINUTES", "triggers.intervalMinutes")
        }
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 5
    }
}

data class Point(val x: Double, val y: Double)

/** Viewport of the designer canvas; node positions live on the nodes. */
data class Canvas(val zoom: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0)


/** A `TASK` node: which Task Library template runs, plus node-level overrides applied last. */
data class TaskBinding(val templateKey: String, val overrides: TaskOverrides = TaskOverrides.NONE)

data class DecisionBranch(val id: String, val label: String, val expression: String)

/** A `DECISION` node: the first branch whose expression is true wins; `defaultBranch` when none is. */
data class DecisionSpec(val branches: List<DecisionBranch>, val defaultBranch: String? = null)

/**
 * Who may decide a `HUMAN_GATE` (RACI on gates, docs/architecture/08-identity-and-access.md). Empty
 * [projectRoles] and [users] mean "any holder of `run.approve` on the project". [accountable] is the single
 * accountable project role ("A" in RACI) shown in the inbox; when roles are listed it must be one of them.
 */
data class GateApprovers(
    val projectRoles: List<String> = emptyList(),
    val users: List<UUID> = emptyList(),
    val accountable: String? = null,
    val forbidSelfApproval: Boolean = false,
) {
    companion object {
        val ANYONE = GateApprovers()
        val ROLE_PATTERN = Regex(SLUG_PATTERN)
    }
}

/** One Definition-of-Ready/Done item of a gate; `approve` needs every [required] item ticked. */
data class ChecklistItem(val id: String, val text: String, val required: Boolean = true)

/** A `HUMAN_GATE` node: an approver picks one of `options` in the inbox; each option is an output port. */
data class GateSpec(
    val question: String,
    val options: List<String> = DEFAULT_OPTIONS,
    val showArtifacts: List<String> = emptyList(),
    val timeoutSeconds: Long? = null,
    val approvers: GateApprovers = GateApprovers.ANYONE,
    val checklist: List<ChecklistItem> = emptyList(),
) {
    companion object {
        const val APPROVE = "approve"
        const val REVISE = "revise"
        const val REJECT = "reject"
        val DEFAULT_OPTIONS = listOf(APPROVE, REVISE, REJECT)
    }
}

/** A `WAIT_FOR_CI` node: pauses until the project's CI sends `signal` for the run. */
/**
 * [watchPipeline] (ADR 0052): Back Office itself watches the pipeline of the run's branch in [repository] (a project
 * repository key; null = the primary one) with the project's CI_READ token and delivers [signal] when it finishes,
 * instead of waiting for the project's CI to call `backoffice-ci signal`.
 */
data class CiWaitSpec(
    val signal: String,
    val timeoutSeconds: Long? = null,
    val watchPipeline: Boolean = false,
    val repository: String? = null,
)

data class EndSpec(val outcome: RunOutcome)

data class NoteSpec(val text: String = "")

/**
 * One block on the canvas. Sealed by kind so executors and the validator dispatch statically; every
 * subclass carries the common fields and its kind-specific section exactly as in `openapi.yaml`.
 * `maxVisits` limits how often a node may run in one workflow run (loop guard).
 */
sealed class WorkflowNode {
    abstract val id: String
    abstract val name: String
    abstract val position: Point
    abstract val timeoutSeconds: Long?
    abstract val maxVisits: Int
    abstract val kind: NodeKind

    /** Output ports as known from the definition alone (TASK ports may be refined by the Task Library). */
    abstract val ports: List<String>

    /** Ports that must be connected for the workflow to be valid. */
    open val requiredPorts: List<String> get() = ports.take(1)

    /** Whether the engine pauses before executing this node. */
    val interrupting: Boolean get() = kind == NodeKind.HUMAN_GATE || kind == NodeKind.WAIT_FOR_CI

    /** Effective timeout of a waiting node (section value wins over the generic one). */
    open val waitTimeoutSeconds: Long? get() = timeoutSeconds

    data class Start(
        override val id: String,
        override val name: String = "Start",
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
    ) : WorkflowNode() {
        override val kind = NodeKind.START
        override val ports: List<String> get() = listOf(PORT_OUT)
    }

    data class End(
        override val id: String,
        override val name: String,
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
        val end: EndSpec,
    ) : WorkflowNode() {
        override val kind = NodeKind.END
        override val ports: List<String> get() = emptyList()
        override val requiredPorts: List<String> get() = emptyList()
    }

    data class Task(
        override val id: String,
        override val name: String,
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
        val task: TaskBinding,
    ) : WorkflowNode() {
        override val kind = NodeKind.TASK
        override val ports: List<String> get() = DEFAULT_TASK_PORTS
        override val requiredPorts: List<String> get() = listOf(PORT_SUCCESS)
    }

    data class Decision(
        override val id: String,
        override val name: String,
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
        val decision: DecisionSpec,
    ) : WorkflowNode() {
        override val kind = NodeKind.DECISION
        override val ports: List<String>
            get() = (decision.branches.map { it.id } + listOfNotNull(decision.defaultBranch)).distinct()
        override val requiredPorts: List<String> get() = ports
    }

    data class HumanGate(
        override val id: String,
        override val name: String,
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
        val gate: GateSpec,
    ) : WorkflowNode() {
        override val kind = NodeKind.HUMAN_GATE
        override val ports: List<String> get() = (gate.options.ifEmpty { GateSpec.DEFAULT_OPTIONS } + PORT_TIMEOUT).distinct()
        override val requiredPorts: List<String> get() = listOf(gate.options.firstOrNull() ?: GateSpec.APPROVE)
        override val waitTimeoutSeconds: Long? get() = gate.timeoutSeconds ?: timeoutSeconds
    }

    data class WaitForCi(
        override val id: String,
        override val name: String,
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
        val ci: CiWaitSpec,
    ) : WorkflowNode() {
        override val kind = NodeKind.WAIT_FOR_CI
        override val ports: List<String> get() = listOf(PORT_SUCCESS, PORT_FAIL, PORT_TIMEOUT)
        override val requiredPorts: List<String> get() = listOf(PORT_SUCCESS)
        override val waitTimeoutSeconds: Long? get() = ci.timeoutSeconds ?: timeoutSeconds
    }

    data class Note(
        override val id: String,
        override val name: String = "Note",
        override val position: Point = Point(0.0, 0.0),
        override val timeoutSeconds: Long? = null,
        override val maxVisits: Int = DEFAULT_MAX_VISITS,
        val note: NoteSpec = NoteSpec(),
    ) : WorkflowNode() {
        override val kind = NodeKind.NOTE
        override val ports: List<String> get() = emptyList()
        override val requiredPorts: List<String> get() = emptyList()
    }

    companion object {
        const val DEFAULT_MAX_VISITS = 3
        const val PORT_OUT = "out"
        const val PORT_SUCCESS = "success"
        const val PORT_FAILURE = "failure"
        const val PORT_FAIL = "fail"
        const val PORT_TIMEOUT = "timeout"
        val DEFAULT_TASK_PORTS = listOf(PORT_SUCCESS, PORT_FAILURE)
        val ID_PATTERN = Regex(SLUG_PATTERN)
        val SIGNAL_PATTERN = Regex(SLUG_PATTERN)

        /** Ports whose unconnected use has a defined meaning: fail the run (or cancel it for `reject`). */
        fun isTerminalWhenUnconnected(port: String): Boolean =
            port == PORT_FAILURE || port == PORT_FAIL || port == PORT_TIMEOUT || port.equals(GateSpec.REJECT, ignoreCase = true)
    }
}

/** A directed connection from an output port of `from` to `to`. `(from, fromPort)` is unique. */
data class WorkflowEdge(val id: String, val from: String, val fromPort: String, val to: String, val label: String? = null)

/**
 * Aggregate root: a graph of nodes and edges designed on the canvas. `version` is bumped on every save;
 * runs pin the version they started with through their definition snapshot.
 */
data class WorkflowDefinition(
    val id: WorkflowDefinitionId,
    val projectId: ProjectId,
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val triggers: List<Trigger> = listOf(Trigger(TriggerType.MANUAL)),
    val enabled: Boolean = true,
    val canvas: Canvas? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who saved this version (creator for version 1). */
    val updatedBy: Actor? = null,
    /** Set when this workflow is a linked copy of a library workflow (ADR 0037); runs snapshot it. */
    val library: LibraryLink? = null,
    /**
     * Saved with validation errors (a work in progress): kept like any version, but no run starts from it and it
     * cannot be published to the library until a save without errors.
     */
    val draft: Boolean = false,
) {
    init {
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (nodes.isEmpty()) throw ValidationException("must contain nodes", "nodes")
    }

    fun node(nodeId: String): WorkflowNode? = nodes.firstOrNull { it.id == nodeId }

    fun requireNode(nodeId: String): WorkflowNode = node(nodeId) ?: throw ValidationException("unknown node $nodeId", "nodes")

    fun startNode(): WorkflowNode? = nodes.firstOrNull { it.kind == NodeKind.START }

    /** Target node id of the edge leaving `nodeId` through `port`, or null when the port is unconnected. */
    fun successor(nodeId: String, port: String): String? =
        edges.firstOrNull { it.from == nodeId && it.fromPort.equals(port, ignoreCase = true) }?.to

    fun outgoing(nodeId: String): List<WorkflowEdge> = edges.filter { it.from == nodeId }

    fun newVersion(
        name: String,
        description: String,
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        triggers: List<Trigger>,
        enabled: Boolean,
        canvas: Canvas?,
        now: Instant,
        actor: Actor? = null,
    ): WorkflowDefinition = copy(
        name = name,
        description = description,
        nodes = nodes,
        edges = edges,
        triggers = triggers,
        enabled = enabled,
        canvas = canvas,
        version = version + 1,
        updatedAt = now,
        updatedBy = actor ?: updatedBy,
        // callers that save without validating mark the new version as a draft themselves
        draft = false,
    )

    fun withLibrary(link: LibraryLink?): WorkflowDefinition = copy(library = link)
}
