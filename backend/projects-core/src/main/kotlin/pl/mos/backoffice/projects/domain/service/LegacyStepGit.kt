package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.Point
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowSource

/**
 * ADR 0063 read-time compatibility for workflows that still carry the former git keys of coding steps (library imports,
 * MCP clients, anything V309 did not rewrite). The same rules as V309:
 *
 * - a step with `push: true` becomes the step plus a `DELIVER_CHANGES` node `<step id>-deliver` to the right of it,
 *   carrying the step's git keys (`branchTemplate`, `commitSubjectTemplate`, `mergeRequest*`; its `repositories` with
 *   PUSH_MR as the delivered keys): the step's `success` edges start at the delivery's `success`, the step's `success`
 *   goes to the delivery and the delivery's `failure` goes where the step's `failure` went;
 * - step `repositories` join the workflow's [WorkflowSource]s (union), a step `setupCommand` the workflow's setup command
 *   (appended with ` && ` when different);
 * - a step of a former Spec Kit template (`spec-kit-*`) or with a Spec Kit `phase` (SPECIFY, PLAN, TASKS) names the
 *   replacement template (`write-spec`, `write-plan`, `write-task-list`);
 * - the keys `push`, the git keys, `repositories`, `setupCommand` and `phase` leave the step. `collectArtifacts` stays
 *   (read as extra output files).
 */
object LegacyStepGit {
    const val PUSH = "push"
    const val PHASE = "phase"
    const val SETUP_COMMAND = "setupCommand"
    const val REPOSITORIES = "repositories"

    /** Keys a coding step no longer has. */
    val STEP_KEYS: Set<String> = (ActionCatalog.DELIVERY_KEYS + listOf(PUSH, PHASE, SETUP_COMMAND, REPOSITORIES)).toSet()

    /** ADR 0063: the replacement of each former Spec Kit template (the deprecated keys still resolve, they extend these). */
    val SPEC_KIT_REPLACEMENTS: Map<String, String> = mapOf(
        "spec-kit-specify" to "write-spec", "spec-kit-plan" to "write-plan", "spec-kit-tasks" to "write-task-list",
    )

    /** The replacement template of a Spec Kit phase; the other phases (CLARIFY, ANALYZE, IMPLEMENT) keep the template. */
    private val PHASE_REPLACEMENTS: Map<String, String> = mapOf("SPECIFY" to "write-spec", "PLAN" to "write-plan", "TASKS" to "write-task-list")

    /** Offset of a generated delivery from its step on the canvas. */
    private const val DX = 168.0
    private const val DY = 160.0
    private const val DELIVER_SUFFIX = "-deliver"
    private const val MAX_ID = 40

    data class Result(val nodes: List<WorkflowNode>, val edges: List<WorkflowEdge>, val sources: List<WorkflowSource>, val setupCommand: String?)

    /** Whether any step still carries a key this compatibility rewrites (nothing to do otherwise). */
    fun needed(nodes: List<WorkflowNode>): Boolean =
        nodes.any { it is WorkflowNode.Task && (it.task.templateKey in SPEC_KIT_REPLACEMENTS || it.task.overrides.config.keys.any { k -> k in STEP_KEYS }) }

    fun normalise(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>, sources: List<WorkflowSource>, setupCommand: String?): Result {
        if (!needed(nodes)) return Result(nodes, edges, sources, setupCommand)
        val outNodes = mutableListOf<WorkflowNode>()
        var outEdges = edges.toMutableList()
        val outSources = sources.toMutableList()
        var setup = setupCommand?.takeIf { it.isNotBlank() }
        val ids = nodes.map { it.id }.toMutableSet()
        val edgeIds = edges.map { it.id }.toMutableSet()
        for (node in nodes) {
            if (node !is WorkflowNode.Task) {
                outNodes += node
                continue
            }
            val config = node.task.overrides.config
            val phase = config[PHASE]?.toString()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val templateKey = SPEC_KIT_REPLACEMENTS[node.task.templateKey] ?: phase?.let { PHASE_REPLACEMENTS[it] } ?: node.task.templateKey
            val requested = repositoryEntries(config[REPOSITORIES])
            requested.forEach { (source, _) -> if (source !in outSources) outSources += source }
            config[SETUP_COMMAND]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { step ->
                val parts = setup?.split(" && ")?.map { it.trim() }.orEmpty()
                if (step !in parts && step != setup) setup = listOfNotNull(setup, step).joinToString(" && ")
            }
            val step = node.copy(task = node.task.copy(templateKey = templateKey, overrides = node.task.overrides.copy(config = config - STEP_KEYS)))
            outNodes += step
            if (!flag(config[PUSH])) continue
            // the step pushed: a delivery after it carries its git settings
            val deliverId = unique(node.id.take(MAX_ID - DELIVER_SUFFIX.length).trimEnd('-') + DELIVER_SUFFIX, ids)
            val deliverConfig = LinkedHashMap<String, Any?>()
            ActionCatalog.DELIVERY_KEYS.forEach { key -> if (config.containsKey(key) && config[key] != null) deliverConfig[key] = config[key] }
            if (phase != null && !deliverConfig.containsKey(ActionCatalog.MERGE_REQUEST)) deliverConfig[ActionCatalog.MERGE_REQUEST] = false
            requested.filter { it.second }.map { it.first }.filterNot { it.library }.map { it.key }.takeIf { it.isNotEmpty() }
                ?.let { deliverConfig[ActionCatalog.REPOSITORIES] = it }
            outNodes += WorkflowNode.Action(
                deliverId, "Deliver changes", Point(node.position.x + DX, node.position.y + DY), maxVisits = node.maxVisits,
                action = ActionBinding(ActionType.DELIVER_CHANGES, deliverConfig),
            )
            val failureTarget = outEdges.firstOrNull { it.from == node.id && it.fromPort.equals(WorkflowNode.PORT_FAILURE, ignoreCase = true) }?.to
            outEdges = outEdges.map { e ->
                if (e.from == node.id && e.fromPort.equals(WorkflowNode.PORT_SUCCESS, ignoreCase = true)) e.copy(from = deliverId) else e
            }.toMutableList()
            outEdges += WorkflowEdge(unique("$deliverId-in", edgeIds), node.id, WorkflowNode.PORT_SUCCESS, deliverId)
            failureTarget?.let { outEdges += WorkflowEdge(unique("$deliverId-failure", edgeIds), deliverId, WorkflowNode.PORT_FAILURE, it) }
        }
        return Result(outNodes, outEdges, outSources, setup)
    }

    /** The former step `repositories` as sources, each with whether it had PUSH_MR. */
    private fun repositoryEntries(raw: Any?): List<Pair<WorkflowSource, Boolean>> = (raw as? List<*>).orEmpty().mapNotNull { item ->
        when (item) {
            is Map<*, *> -> item["key"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
                WorkflowSource(key, library = flag(item["library"])) to (item["access"]?.toString()?.uppercase() == "PUSH_MR")
            }
            is String -> item.trim().takeIf { it.isNotEmpty() }?.let { WorkflowSource(it) to false }
            else -> null
        }
    }.distinctBy { it.first }

    private fun flag(value: Any?): Boolean = value == true || value?.toString()?.lowercase() == "true"

    private fun unique(candidate: String, taken: MutableSet<String>): String {
        var id = candidate
        var n = 2
        while (id in taken) id = candidate.take(MAX_ID - 3) + "-" + n++
        taken += id
        return id
    }
}
