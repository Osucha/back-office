package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.Point
import pl.mos.backoffice.projects.domain.model.StepConnectionConfig
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode

/**
 * What of a workflow graph belongs to one project and what can be shared through the library (ADR 0037).
 *
 * Project-local settings, never published and kept when a linked copy takes a new library version:
 * - TASK `config.repositories` entries of the project's repositories (ADR 0034); entries of library sources (ADR 0043)
 *   are shared by every project and stay in the published graph;
 * - TASK `config.jiraConnection` when it pins a connection id (`SYSTEM` and `ACTING_USER` are portable modes, ADR 0035);
 * - named users of approver rules: HUMAN_GATE `approvers.users` and write-step `config.approvers.users` (roles stay).
 */
object LibraryPortability {

    const val REPOSITORIES_KEY = "repositories"
    const val APPROVERS_KEY = "approvers"
    private const val USERS_KEY = "users"
    private val ORIGIN = Point(0.0, 0.0)

    /** The graph as it is published: project-local settings removed. */
    fun portable(nodes: List<WorkflowNode>): List<WorkflowNode> = nodes.map(::strip)

    /**
     * The library [nodes] with the project-local settings of the [local] nodes that have the same id and kind
     * re-applied; library nodes without a local counterpart stay as published.
     */
    fun withLocalSettings(nodes: List<WorkflowNode>, local: List<WorkflowNode>): List<WorkflowNode> {
        val byId = local.associateBy { it.id }
        return nodes.map { node ->
            val mine = byId[node.id]?.takeIf { it.kind == node.kind } ?: return@map node
            when {
                node is WorkflowNode.Task && mine is WorkflowNode.Task -> node.copy(task = node.task.copy(overrides = node.task.overrides.copy(config = mergeConfig(node.task.overrides.config, mine.task.overrides.config))))
                node is WorkflowNode.HumanGate && mine is WorkflowNode.HumanGate -> node.copy(gate = node.gate.copy(approvers = node.gate.approvers.copy(users = mine.gate.approvers.users)))
                else -> node
            }
        }
    }

    /**
     * Whether a project graph equals a library version, ignoring project-local settings, node positions and edge ids
     * (the `modified` flag of a linked copy).
     */
    fun sameGraph(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>, libraryNodes: List<WorkflowNode>, libraryEdges: List<WorkflowEdge>): Boolean =
        comparable(nodes) == comparable(libraryNodes) && edgeSet(edges) == edgeSet(libraryEdges)

    private fun comparable(nodes: List<WorkflowNode>): Map<String, WorkflowNode> = portable(nodes).associate { it.id to atOrigin(it) }

    private fun edgeSet(edges: List<WorkflowEdge>): Set<List<String?>> = edges.map { listOf(it.from, it.fromPort.lowercase(), it.to, it.label?.ifBlank { null }) }.toSet()

    private fun strip(node: WorkflowNode): WorkflowNode = when (node) {
        is WorkflowNode.Task -> node.copy(task = node.task.copy(overrides = node.task.overrides.copy(config = portableConfig(node.task.overrides.config))))
        is WorkflowNode.HumanGate -> node.copy(gate = node.gate.copy(approvers = node.gate.approvers.copy(users = emptyList())))
        else -> node
    }

    private fun portableConfig(config: Map<String, Any?>): Map<String, Any?> {
        if (config.isEmpty()) return config
        val out = LinkedHashMap(config)
        val shared = entries(config).filter(::libraryEntry)
        if (shared.isEmpty()) out.remove(REPOSITORIES_KEY) else out[REPOSITORIES_KEY] = shared
        if (pinnedConnection(config[StepConnectionConfig.CONNECTION_KEY])) out.remove(StepConnectionConfig.CONNECTION_KEY)
        val approvers = config[APPROVERS_KEY]
        if (approvers is Map<*, *> && approvers.containsKey(USERS_KEY)) out[APPROVERS_KEY] = approvers + (USERS_KEY to emptyList<Any?>())
        return out
    }

    /** The published [config] plus the local repositories, pinned connection and approver users of [local]. */
    private fun mergeConfig(config: Map<String, Any?>, local: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(config)
        // the project's own repositories from the local copy, the library sources as published
        val merged = entries(local).filterNot(::libraryEntry) + entries(config).filter(::libraryEntry)
        if (merged.isNotEmpty()) out[REPOSITORIES_KEY] = merged else if (local.containsKey(REPOSITORIES_KEY)) out[REPOSITORIES_KEY] = local[REPOSITORIES_KEY]
        local[StepConnectionConfig.CONNECTION_KEY]?.takeIf(::pinnedConnection)?.let { out[StepConnectionConfig.CONNECTION_KEY] = it }
        val users = (local[APPROVERS_KEY] as? Map<*, *>)?.get(USERS_KEY)
        val approvers = out[APPROVERS_KEY]
        if (approvers is Map<*, *> && users is List<*> && users.isNotEmpty()) out[APPROVERS_KEY] = approvers + (USERS_KEY to users)
        return out
    }

    private fun entries(config: Map<String, Any?>): List<Any?> = (config[REPOSITORIES_KEY] as? List<*>).orEmpty()

    private fun libraryEntry(entry: Any?): Boolean = (entry as? Map<*, *>)?.get("library")?.toString()?.lowercase() == "true"

    private fun pinnedConnection(value: Any?): Boolean {
        val raw = value?.toString()?.trim().orEmpty()
        return raw.isNotEmpty() && !raw.equals(StepConnectionConfig.SYSTEM, ignoreCase = true) && !raw.equals(StepConnectionConfig.ACTING_USER, ignoreCase = true)
    }

    private fun atOrigin(node: WorkflowNode): WorkflowNode = when (node) {
        is WorkflowNode.Start -> node.copy(position = ORIGIN)
        is WorkflowNode.End -> node.copy(position = ORIGIN)
        is WorkflowNode.Task -> node.copy(position = ORIGIN)
        is WorkflowNode.Decision -> node.copy(position = ORIGIN)
        is WorkflowNode.HumanGate -> node.copy(position = ORIGIN)
        is WorkflowNode.WaitForCi -> node.copy(position = ORIGIN)
        is WorkflowNode.Note -> node.copy(position = ORIGIN)
    }
}
