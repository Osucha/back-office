package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.DecisionBranch
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.tasks.RunInputField

data class ValidationIssue(val nodeId: String? = null, val edgeId: String? = null, val field: String? = null, val message: String)

data class WorkflowValidationResult(val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>) {
    val valid: Boolean get() = errors.isEmpty()
}

/**
 * What the validator needs to know about a task template: its output ports, whether it is abstract,
 * whether it can push to the repository (runner-backed `IMPLEMENT` / `SPEC_KIT` with `push` not false), the run
 * inputs it declares and, for Jira reads, the template's `issueKeyFrom` setting (ADR 0053).
 */
data class TaskTemplateInfo(
    val ports: List<String>,
    val abstract: Boolean = false,
    val pushCapable: Boolean = false,
    val runInputs: List<RunInputField> = emptyList(),
    val issueKeyFrom: String? = null,
)

/**
 * Looks up a template key for validation. Returns null for unknown keys. The default provider treats
 * every key as a known concrete template with `success`/`failure` ports (catalog unavailable).
 */
fun interface TaskPortsProvider {
    fun lookup(templateKey: String): TaskTemplateInfo?

    companion object {
        val DEFAULT = TaskPortsProvider { TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS) }
    }
}

/** Checks expression syntax; returns an error message or null when the expression is fine. */
fun interface ExpressionValidator {
    fun validate(expression: String): String?

    companion object {
        /** Accepts everything non-blank (syntax is checked by the adapter). */
        val LENIENT = ExpressionValidator { if (it.isBlank()) "expression must not be blank" else null }
    }
}

/**
 * Pure domain service checking a graph before it becomes a workflow version
 * (docs/architecture/02-domain-model.md): exactly one START, at least one END, unique slug ids, edge
 * endpoints and ports exist, required ports of reachable nodes are connected, expressions parse,
 * template keys resolve, gate options and signal names are sane. Unreachable nodes are warnings.
 */
class WorkflowValidator {

    fun validate(
        name: String?,
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        taskPorts: TaskPortsProvider = TaskPortsProvider.DEFAULT,
        expressions: ExpressionValidator = ExpressionValidator.LENIENT,
        triggers: List<Trigger> = emptyList(),
    ): WorkflowValidationResult {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()

        if (name.isNullOrBlank()) errors += ValidationIssue(field = "name", message = MUST_NOT_BE_BLANK)
        if (nodes.isEmpty()) {
            errors += ValidationIssue(field = "nodes", message = "must contain at least a START and an END node")
            return WorkflowValidationResult(errors, warnings)
        }

        validateNodes(nodes, taskPorts, expressions, errors, warnings)
        val portsOf = nodes.associate { it.id to effectivePorts(it, taskPorts) }
        validateEdges(nodes, edges, portsOf, errors)

        val starts = nodes.filter { it.kind == NodeKind.START }
        if (starts.size != 1) errors += ValidationIssue(field = "nodes", message = "exactly one START node is required, found ${starts.size}")
        if (nodes.none { it.kind == NodeKind.END }) errors += ValidationIssue(field = "nodes", message = "at least one END node is required")

        if (errors.none { it.field == "id" } && starts.size == 1) {
            val graph = WorkflowGraph(nodes, edges)
            val reachable = graph.reachable()
            nodes.filter { it.kind != NodeKind.NOTE && it.id !in reachable }.forEach {
                warnings += ValidationIssue(nodeId = it.id, message = "node is never reached from START")
            }
            nodes.filter { it.id in reachable }.forEach { node ->
                val connected = graph.outgoing(node.id).map { it.fromPort.lowercase() }.toSet()
                val ports = portsOf[node.id] ?: node.ports
                requiredPorts(node, ports).filter { it.lowercase() !in connected }.forEach { port ->
                    errors += ValidationIssue(nodeId = node.id, field = "ports.$port", message = "port $port must be connected")
                }
                ports.filter { it.lowercase() !in connected && it !in requiredPorts(node, ports) && !WorkflowNode.isTerminalWhenUnconnected(it) }
                    .forEach { port -> warnings += ValidationIssue(nodeId = node.id, field = "ports.$port", message = "port $port is not connected; the run fails when it is taken") }
            }
        }
        // A CI-triggered workflow (a merge request gate) is meant to run unattended; others are told.
        if (nodes.none { it.kind == NodeKind.HUMAN_GATE } && triggers.none { it.type == TriggerType.CI }) {
            warnings += ValidationIssue(message = "workflow has no human gate; it will run fully unattended")
        }
        if (errors.none { it.field == "id" }) warnings += modelRoutedPushes(nodes, edges, taskPorts)
        warnings += runInputWarnings(nodes, taskPorts)
        return WorkflowValidationResult(errors, warnings)
    }

    /**
     * ADR 0053, warnings only: a step that reads the issue key from a run parameter (`issueKeyFrom`) that no task of
     * the workflow declares gets no field for it in the start form; two tasks that declare the same run input with
     * different labels show only the first label.
     */
    private fun runInputWarnings(nodes: List<WorkflowNode>, taskPorts: TaskPortsProvider): List<ValidationIssue> {
        val tasks = nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }
            .map { it to taskPorts.lookup(it.task.templateKey) }
        val declared = tasks.flatMap { (_, info) -> info?.runInputs.orEmpty() }.map { it.name }.toSet()
        val warnings = mutableListOf<ValidationIssue>()
        tasks.forEach { (node, info) ->
            val from = (node.task.overrides.config[ISSUE_KEY_FROM]?.toString() ?: info?.issueKeyFrom)?.trim()
            if (!from.isNullOrEmpty() && from != ISSUE_KEY_FROM_INPUT && from !in declared) {
                warnings += ValidationIssue(
                    nodeId = node.id, field = "task.config.$ISSUE_KEY_FROM",
                    message = "this step reads the issue key from the run parameter $from, but no task of this workflow declares it as a run " +
                        "input, so the start form has no field for it; declare it in the task library or use issueKeyFrom input",
                )
            }
        }
        val firstLabel = mutableMapOf<String, String>()
        tasks.forEach { (node, info) ->
            info?.runInputs.orEmpty().forEach { field ->
                val first = firstLabel.getOrPut(field.name) { field.label }
                if (first != field.label) {
                    warnings += ValidationIssue(
                        nodeId = node.id, field = "runInputs.${field.name}",
                        message = "run input ${field.name} is declared with the label \"$first\" by an earlier step and \"${field.label}\" here; " +
                            "the start form shows one field and the first label wins",
                    )
                }
            }
        }
        return warnings
    }

    /**
     * Prompt injection (ADR 0029): a DECISION branch whose expression reads only model-produced fields
     * (`confidence`, `review.*`) can be steered by whoever controls the model's input (issue text, diff,
     * repository). Warn when such a branch reaches a push-capable task without passing a HUMAN_GATE.
     * Branches that loop back to an earlier step (rework: the target leads back to the decision) are fine.
     */
    private fun modelRoutedPushes(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>, taskPorts: TaskPortsProvider): List<ValidationIssue> {
        val graph = WorkflowGraph(nodes, edges)
        val byId = nodes.associateBy { it.id }
        val pushCapable = nodes.filterIsInstance<WorkflowNode.Task>()
            .filter { node -> node.task.templateKey.isNotBlank() && taskPorts.lookup(node.task.templateKey)?.pushCapable == true }
            .map { it.id }.toSet()
        if (pushCapable.isEmpty()) return emptyList()
        return nodes.filterIsInstance<WorkflowNode.Decision>().flatMap { decision ->
            decision.decision.branches.mapNotNull { branch -> modelRoutedPush(decision, branch, graph, byId, pushCapable) }
        }
    }

    /** The warning for one DECISION branch of [modelRoutedPushes], or null when the branch is safe. */
    private fun modelRoutedPush(
        decision: WorkflowNode.Decision,
        branch: DecisionBranch,
        graph: WorkflowGraph,
        byId: Map<String, WorkflowNode>,
        pushCapable: Set<String>,
    ): ValidationIssue? {
        val roots = expressionRoots(branch.expression)
        if (roots.isEmpty() || !MODEL_PRODUCED_ROOTS.containsAll(roots)) return null
        val target = graph.successor(decision.id, branch.id) ?: return null
        if (decision.id in graph.reachable(target)) return null
        val reached = firstPushWithoutGate(target, graph, byId, pushCapable) ?: return null
        val laterGate = graph.reachable(reached).any { byId[it]?.kind == NodeKind.HUMAN_GATE }
        val consequence = if (laterGate) {
            "a later human gate still guards the merge, but the push (and the CI pipeline it triggers) happens unreviewed"
        } else {
            "no human gate follows before the run ends"
        }
        return ValidationIssue(
            nodeId = decision.id,
            field = "decision.branches.${branch.id}.expression",
            message = "branch ${branch.id} routes only on model output (${roots.sorted().joinToString()}) and reaches the push-capable step " +
                "$reached without a human gate ($consequence); model output can be steered by prompt injection in issues, diffs or " +
                "repository content - add a HUMAN_GATE or route on a deterministic signal (docs/architecture/12-prompt-injection.md)",
        )
    }

    /** Breadth-first from [start], not passing HUMAN_GATE nodes; the first push-capable task reached, or null. */
    private fun firstPushWithoutGate(start: String, graph: WorkflowGraph, byId: Map<String, WorkflowNode>, pushCapable: Set<String>): String? {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val node = byId[id] ?: continue
            if (node.kind == NodeKind.HUMAN_GATE) continue
            if (id in pushCapable) return id
            graph.outgoing(id).forEach { queue += it.to }
        }
        return null
    }

    companion object {
        /** State fields written from model answers; routing on them alone is steerable by prompt injection. */
        val MODEL_PRODUCED_ROOTS = setOf("confidence", "review")

        private const val MUST_NOT_BE_BLANK = "must not be blank"
        private const val ISSUE_KEY_FROM = "issueKeyFrom"
        private const val ISSUE_KEY_FROM_INPUT = "input"
        private const val MUST_BE_POSITIVE = "must be positive"
        private const val FIELD_TEMPLATE_KEY = "task.templateKey"
        private const val FIELD_DEFAULT_BRANCH = "decision.defaultBranch"
        private const val FIELD_GATE_OPTIONS = "gate.options"

        private val STRING_LITERAL = Regex("'(?:[^']|'')*'|\"(?:[^\"\\\\]|\\\\.)*\"")
        private val IDENTIFIER = Regex("(?<![.\\w#@$])([A-Za-z_][A-Za-z0-9_]*)")
        private val KEYWORDS = setOf("and", "or", "not", "true", "false", "null", "eq", "ne", "lt", "gt", "le", "ge", "div", "mod", "matches", "between", "instanceof", "new", "T")

        /** First segments of the property paths an expression reads (`review.verdict != 'X' and confidence > 0.8` -> review, confidence). */
        fun expressionRoots(expression: String): Set<String> =
            IDENTIFIER.findAll(STRING_LITERAL.replace(expression, "''")).map { it.groupValues[1] }.filterNot { it in KEYWORDS }.toSet()
    }

    /** Ports of a node as the designer sees them: template ports for TASK nodes, static ports otherwise. */
    fun effectivePorts(node: WorkflowNode, taskPorts: TaskPortsProvider): List<String> = when (node) {
        is WorkflowNode.Task -> taskPorts.lookup(node.task.templateKey)?.ports?.takeIf { it.isNotEmpty() } ?: node.ports
        else -> node.ports
    }

    private fun requiredPorts(node: WorkflowNode, ports: List<String>): List<String> = when (node) {
        is WorkflowNode.Task -> listOf(ports.firstOrNull { it == WorkflowNode.PORT_SUCCESS } ?: ports.first())
        else -> node.requiredPorts
    }

    private fun validateNodes(
        nodes: List<WorkflowNode>,
        taskPorts: TaskPortsProvider,
        expressions: ExpressionValidator,
        errors: MutableList<ValidationIssue>,
        warnings: MutableList<ValidationIssue>,
    ) {
        nodes.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach {
            errors += ValidationIssue(nodeId = it, field = "id", message = "node id is not unique")
        }
        nodes.forEach { node ->
            validateCommon(node, errors)
            when (node) {
                is WorkflowNode.Task -> validateTask(node, taskPorts, errors, warnings)
                is WorkflowNode.Decision -> validateDecision(node, expressions, errors)
                is WorkflowNode.HumanGate -> validateGate(node, errors)
                is WorkflowNode.WaitForCi -> validateWaitForCi(node, errors)
                is WorkflowNode.Start, is WorkflowNode.End, is WorkflowNode.Note -> Unit
            }
        }
    }

    /** Checks every node kind shares: slug id, name, visit limit and timeout. */
    private fun validateCommon(node: WorkflowNode, errors: MutableList<ValidationIssue>) {
        if (!WorkflowNode.ID_PATTERN.matches(node.id)) errors += ValidationIssue(nodeId = node.id, field = "id", message = "must match ${WorkflowNode.ID_PATTERN.pattern}")
        if (node.name.isBlank()) errors += ValidationIssue(nodeId = node.id, field = "name", message = MUST_NOT_BE_BLANK)
        if (node.maxVisits < 1) errors += ValidationIssue(nodeId = node.id, field = "maxVisits", message = "must be at least 1")
        requirePositive(node, "timeoutSeconds", node.timeoutSeconds, errors)
    }

    private fun requirePositive(node: WorkflowNode, field: String, value: Long?, errors: MutableList<ValidationIssue>) {
        if (value != null && value <= 0) errors += ValidationIssue(nodeId = node.id, field = field, message = MUST_BE_POSITIVE)
    }

    /** The template key resolves to a concrete template (abstract ones are a warning). */
    private fun validateTask(
        node: WorkflowNode.Task,
        taskPorts: TaskPortsProvider,
        errors: MutableList<ValidationIssue>,
        warnings: MutableList<ValidationIssue>,
    ) {
        val key = node.task.templateKey
        if (key.isBlank()) {
            errors += ValidationIssue(nodeId = node.id, field = FIELD_TEMPLATE_KEY, message = "is required")
            return
        }
        val info = taskPorts.lookup(key)
        if (info == null) errors += ValidationIssue(nodeId = node.id, field = FIELD_TEMPLATE_KEY, message = "unknown task template $key")
        else if (info.abstract) warnings += ValidationIssue(nodeId = node.id, field = FIELD_TEMPLATE_KEY, message = "template $key is abstract and cannot run")
    }

    /** At least one branch, unique slug branch ids, parseable expressions and a sane default branch. */
    private fun validateDecision(node: WorkflowNode.Decision, expressions: ExpressionValidator, errors: MutableList<ValidationIssue>) {
        val branches = node.decision.branches
        if (branches.isEmpty()) errors += ValidationIssue(nodeId = node.id, field = "decision.branches", message = "at least one branch is required")
        branches.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach {
            errors += ValidationIssue(nodeId = node.id, field = "decision.branches.$it", message = "branch id is not unique")
        }
        branches.forEach { branch ->
            if (!WorkflowNode.ID_PATTERN.matches(branch.id)) errors += ValidationIssue(nodeId = node.id, field = "decision.branches.${branch.id}", message = "branch id must match ${WorkflowNode.ID_PATTERN.pattern}")
            val problem = if (branch.expression.isBlank()) "expression must not be blank" else expressions.validate(branch.expression)
            if (problem != null) errors += ValidationIssue(nodeId = node.id, field = "decision.branches.${branch.id}.expression", message = problem)
        }
        val defaultBranch = node.decision.defaultBranch ?: return
        if (defaultBranch.isBlank()) errors += ValidationIssue(nodeId = node.id, field = FIELD_DEFAULT_BRANCH, message = MUST_NOT_BE_BLANK)
        else if (!WorkflowNode.ID_PATTERN.matches(defaultBranch)) errors += ValidationIssue(nodeId = node.id, field = FIELD_DEFAULT_BRANCH, message = "must match ${WorkflowNode.ID_PATTERN.pattern}")
    }

    /** Question, options, timeout, approver rule and checklist of a HUMAN_GATE. */
    private fun validateGate(node: WorkflowNode.HumanGate, errors: MutableList<ValidationIssue>) {
        if (node.gate.question.isBlank()) errors += ValidationIssue(nodeId = node.id, field = "gate.question", message = MUST_NOT_BE_BLANK)
        if (node.gate.options.isEmpty()) errors += ValidationIssue(nodeId = node.id, field = FIELD_GATE_OPTIONS, message = "must not be empty")
        if (node.gate.options.any { it.isBlank() }) errors += ValidationIssue(nodeId = node.id, field = FIELD_GATE_OPTIONS, message = "options must not be blank")
        requirePositive(node, "gate.timeoutSeconds", node.gate.timeoutSeconds, errors)
        validateApprovers(node, errors)
        validateChecklist(node, errors)
    }

    private fun validateWaitForCi(node: WorkflowNode.WaitForCi, errors: MutableList<ValidationIssue>) {
        if (!WorkflowNode.SIGNAL_PATTERN.matches(node.ci.signal)) {
            errors += ValidationIssue(nodeId = node.id, field = "ci.signal", message = "must match ${WorkflowNode.SIGNAL_PATTERN.pattern}")
        }
        requirePositive(node, "ci.timeoutSeconds", node.ci.timeoutSeconds, errors)
    }

    /** Approver rule: role keys are slugs, and the single accountable role (RACI "A") is one of the listed roles. */
    private fun validateApprovers(node: WorkflowNode.HumanGate, errors: MutableList<ValidationIssue>) {
        val rule = node.gate.approvers
        rule.projectRoles.filterNot { GateApprovers.ROLE_PATTERN.matches(it) }.forEach {
            errors += ValidationIssue(nodeId = node.id, field = "gate.approvers.projectRoles", message = "role key $it must match ${GateApprovers.ROLE_PATTERN.pattern}")
        }
        rule.projectRoles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            errors += ValidationIssue(nodeId = node.id, field = "gate.approvers.projectRoles", message = "role $it is listed twice")
        }
        val accountable = rule.accountable
        if (accountable != null) {
            if (!GateApprovers.ROLE_PATTERN.matches(accountable)) {
                errors += ValidationIssue(nodeId = node.id, field = "gate.approvers.accountable", message = "role key $accountable must match ${GateApprovers.ROLE_PATTERN.pattern}")
            } else if (rule.projectRoles.isNotEmpty() && accountable !in rule.projectRoles) {
                errors += ValidationIssue(nodeId = node.id, field = "gate.approvers.accountable", message = "accountable role $accountable must be one of the approver roles ${rule.projectRoles}")
            }
        }
        if (rule.users.size != rule.users.toSet().size) {
            errors += ValidationIssue(nodeId = node.id, field = "gate.approvers.users", message = "users must be unique")
        }
    }

    /** Checklist: slug ids, unique, non-blank text. */
    private fun validateChecklist(node: WorkflowNode.HumanGate, errors: MutableList<ValidationIssue>) {
        val items = node.gate.checklist
        items.forEach { item ->
            if (!WorkflowNode.ID_PATTERN.matches(item.id)) {
                errors += ValidationIssue(nodeId = node.id, field = "gate.checklist.${item.id}", message = "checklist id must match ${WorkflowNode.ID_PATTERN.pattern}")
            }
            if (item.text.isBlank()) errors += ValidationIssue(nodeId = node.id, field = "gate.checklist.${item.id}", message = "text must not be blank")
        }
        items.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach {
            errors += ValidationIssue(nodeId = node.id, field = "gate.checklist.$it", message = "checklist id is not unique")
        }
    }

    private fun validateEdges(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
        portsOf: Map<String, List<String>>,
        errors: MutableList<ValidationIssue>,
    ) {
        val byId = nodes.associateBy { it.id }
        edges.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach {
            errors += ValidationIssue(edgeId = it, field = "id", message = "edge id is not unique")
        }
        edges.groupingBy { it.from to it.fromPort.lowercase() }.eachCount().filterValues { it > 1 }.keys.forEach { (from, port) ->
            errors += ValidationIssue(nodeId = from, field = "ports.$port", message = "port $port has more than one outgoing edge")
        }
        edges.forEach { edge ->
            val from = byId[edge.from]
            val to = byId[edge.to]
            if (from == null) errors += ValidationIssue(edgeId = edge.id, field = "from", message = "node ${edge.from} does not exist")
            if (to == null) errors += ValidationIssue(edgeId = edge.id, field = "to", message = "node ${edge.to} does not exist")
            if (from != null) validateEdgeSource(edge, from, portsOf, errors)
            if (to != null && (to.kind == NodeKind.START || to.kind == NodeKind.NOTE)) {
                errors += ValidationIssue(edgeId = edge.id, field = "to", message = "${to.kind} nodes cannot be targets")
            }
        }
    }

    /** The source node has output ports and [edge] leaves through one of them. */
    private fun validateEdgeSource(edge: WorkflowEdge, from: WorkflowNode, portsOf: Map<String, List<String>>, errors: MutableList<ValidationIssue>) {
        if (from.kind == NodeKind.END || from.kind == NodeKind.NOTE) {
            errors += ValidationIssue(edgeId = edge.id, field = "from", message = "${from.kind} nodes have no output ports")
            return
        }
        val ports = portsOf[from.id] ?: from.ports
        if (ports.none { it.equals(edge.fromPort, ignoreCase = true) }) {
            errors += ValidationIssue(edgeId = edge.id, field = "fromPort", message = "node ${from.id} has no port ${edge.fromPort}; available: $ports")
        }
    }
}
