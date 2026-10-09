package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode

/**
 * Pure graph helpers over a workflow definition: successors by port, reachability from START, cycle
 * detection (loops are legal; the runtime guard is `maxVisits`) and the routing rule for unconnected ports.
 */
class WorkflowGraph(val nodes: List<WorkflowNode>, val edges: List<WorkflowEdge>) {

    constructor(definition: WorkflowDefinition) : this(definition.nodes, definition.edges)

    private val byId: Map<String, WorkflowNode> = nodes.associateBy { it.id }
    private val outgoing: Map<String, List<WorkflowEdge>> = edges.groupBy { it.from }

    fun node(id: String): WorkflowNode? = byId[id]

    fun startNode(): WorkflowNode? = nodes.firstOrNull { it.kind == NodeKind.START }

    fun outgoing(nodeId: String): List<WorkflowEdge> = outgoing[nodeId] ?: emptyList()

    /** Target of the edge leaving [nodeId] through [port]; null when unconnected. Ports compare case-insensitively. */
    fun successor(nodeId: String, port: String): String? =
        outgoing(nodeId).firstOrNull { it.fromPort.equals(port, ignoreCase = true) }?.to

    /** All direct successors of a node, regardless of port. */
    fun successors(nodeId: String): List<String> = outgoing(nodeId).map { it.to }.distinct()

    /** Node ids reachable from START (or from [from]) following any edge; NOTE nodes are never reached. */
    fun reachable(from: String? = startNode()?.id): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        if (from != null && from in byId) queue += from
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            successors(id).filter { it in byId && byId[it]?.kind != NodeKind.NOTE }.forEach { queue += it }
        }
        return seen
    }

    /** True when [to] can be reached from [from] following edges (including [from] itself when equal). */
    fun pathExists(from: String, to: String): Boolean = from == to || to in reachable(from)

    /** Node ids that lie on at least one cycle (a loop back to an earlier node). */
    fun nodesOnCycles(): Set<String> = byId.keys.filter { id -> successors(id).any { pathExists(it, id) } }.toSet()

    /** The routing decision when a node leaves through [port]. */
    fun route(nodeId: String, port: String): Route {
        val target = successor(nodeId, port)
        if (target != null) return Route.Next(target)
        return when {
            port.equals(pl.mos.backoffice.projects.domain.model.GateSpec.REJECT, ignoreCase = true) -> Route.Cancel("gate rejected")
            WorkflowNode.isTerminalWhenUnconnected(port) -> Route.Fail("node $nodeId left through unconnected port $port")
            else -> Route.Fail("port $port of node $nodeId is not connected")
        }
    }

    sealed class Route {
        data class Next(val nodeId: String) : Route()
        data class Fail(val reason: String) : Route()
        data class Cancel(val reason: String) : Route()
    }
}
