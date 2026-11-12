package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.Canvas
import pl.mos.backoffice.projects.domain.model.ChecklistItem
import pl.mos.backoffice.projects.domain.model.CiWaitSpec
import pl.mos.backoffice.projects.domain.model.DecisionBranch
import pl.mos.backoffice.projects.domain.model.DecisionSpec
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.NoteSpec
import pl.mos.backoffice.projects.domain.model.Point
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ActionCatalog
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.ParameterBinding
import pl.mos.backoffice.shared.tasks.ParameterBindingSource
import pl.mos.backoffice.shared.tasks.TaskOverrides
import java.util.UUID

/*
 * JSON shape of a workflow graph exactly as in openapi.yaml (`WorkflowNode`, `WorkflowEdge`, `Trigger`,
 * canvas). Shared by the REST layer (request/response bodies), the persistence layer (JSONB columns and
 * the run's definition snapshot) and the MCP server (ADR 0049) so all agree on one encoding. Plain data, no
 * framework: it lives next to the use cases that take it.
 *
 * ADR 0056 compatibility on read (stored definitions, library versions and run snapshots alike): a TASK node naming a
 * former action template is read as the ACTION node, and the step config key `agent` as `overrides.agentKey`.
 * ADR 0060 compatibility on read: the step config key `workspace` (ADR 0044 run workspaces) is dropped, every coding step
 * works in the run directory; `addSkills` / `removeSkills` are ignored. A legacy `sessionRole` stays in the config and is
 * read when the run plans the step (RunDirectory): steps sharing it keep sharing their session.
 * ADR 0062 compatibility on read (V308 rewrote the stored steps; library imports and MCP clients may still send them):
 * `systemPromptAppend` / `promptAppend` read as `instructionsAppend`, `promptReplace` as `instructionsReplace`, step
 * `parameters` as VALUE `parameterBindings`; the config keys `session`, `continueSession` and `directory` are dropped.
 * Only the current fields are written.
 */

data class PointJson(val x: Double = 0.0, val y: Double = 0.0)
data class CanvasJson(val zoom: Double = 1.0, val x: Double = 0.0, val y: Double = 0.0)
/** ADR 0062: `ParameterBinding` (VALUE, START or ARTIFACT). */
data class ParameterBindingJson(val source: String = "", val value: Any? = null, val artifact: String? = null)

data class TaskOverridesJson(
    val agentKey: String? = null,
    val modelProfileId: UUID? = null,
    /** ADR 0062: notes after the template's instructions. */
    val instructionsAppend: String? = null,
    /** ADR 0062: replaces the template's instructions. */
    val instructionsReplace: String? = null,
    val config: Map<String, Any?> = emptyMap(),
    /** ADR 0062: per parameter, where its value comes from; null = none. */
    val parameterBindings: Map<String, ParameterBindingJson>? = null,
    /** ADR 0062: read as [instructionsAppend], never written. */
    val systemPromptAppend: String? = null,
    /** ADR 0062: read as [instructionsAppend], never written. */
    val promptAppend: String? = null,
    /** ADR 0062: read as [instructionsReplace], never written. */
    val promptReplace: String? = null,
    /** ADR 0058 step values; ADR 0062: read as VALUE bindings, never written. */
    val parameters: Map<String, Any?>? = null,
    /** ADR 0060: removed (the environment is the agent's); read and ignored, never written. */
    val addSkills: List<String>? = null,
    /** ADR 0060: removed; read and ignored, never written. */
    val removeSkills: List<String>? = null,
)
data class TaskSectionJson(val templateKey: String = "", val overrides: TaskOverridesJson = TaskOverridesJson())
/** ADR 0056: the `action` section of an ACTION node. */
data class ActionSectionJson(val type: String = "", val config: Map<String, Any?> = emptyMap())
data class DecisionBranchJson(val id: String = "", val label: String = "", val expression: String = "")
data class DecisionSectionJson(val branches: List<DecisionBranchJson> = emptyList(), val defaultBranch: String? = null)
data class GateApproversJson(
    val projectRoles: List<String> = emptyList(),
    val users: List<UUID> = emptyList(),
    val accountable: String? = null,
    val forbidSelfApproval: Boolean = false,
)
data class ChecklistItemJson(val id: String = "", val text: String = "", val required: Boolean = true)
data class GateSectionJson(
    val question: String = "",
    val options: List<String> = GateSpec.DEFAULT_OPTIONS,
    val showArtifacts: List<String> = emptyList(),
    val timeoutSeconds: Long? = null,
    val approvers: GateApproversJson? = null,
    val checklist: List<ChecklistItemJson> = emptyList(),
)
data class CiSectionJson(val signal: String = "", val timeoutSeconds: Long? = null, val watchPipeline: Boolean? = null, val repository: String? = null)
data class EndSectionJson(val outcome: String = "SUCCESS")
data class NoteSectionJson(val text: String = "")

data class WorkflowNodeJson(
    val id: String = "",
    val kind: String = "",
    val name: String = "",
    val position: PointJson = PointJson(),
    val timeoutSeconds: Long? = null,
    val maxVisits: Int = WorkflowNode.DEFAULT_MAX_VISITS,
    val task: TaskSectionJson? = null,
    val action: ActionSectionJson? = null,
    val decision: DecisionSectionJson? = null,
    val gate: GateSectionJson? = null,
    val ci: CiSectionJson? = null,
    val end: EndSectionJson? = null,
    val note: NoteSectionJson? = null,
)

data class WorkflowEdgeJson(val id: String = "", val from: String = "", val fromPort: String = "", val to: String = "", val label: String? = null)
data class TriggerJson(val type: String = "MANUAL", val status: String? = null, val intervalMinutes: Int? = null)

/** A whole workflow as `WorkflowDefinitionInput` in openapi.yaml: what the MCP tools read and write (ADR 0049). */
data class WorkflowDocumentJson(
    val name: String = "",
    val description: String = "",
    val nodes: List<WorkflowNodeJson> = emptyList(),
    val edges: List<WorkflowEdgeJson> = emptyList(),
    val triggers: List<TriggerJson> = listOf(TriggerJson()),
    val enabled: Boolean = true,
    val canvas: CanvasJson? = null,
    /** ADR 0060: runs once per run directory before the first coding step. */
    val setupCommand: String? = null,
)

/** Conversions between the JSON shape and the sealed domain model. Kind/section mismatches are validation errors. */
object WorkflowJson {

    private inline fun <reified E : Enum<E>> enum(value: String?, field: String): E {
        if (value.isNullOrBlank()) throw ValidationException("is required", field)
        return enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw ValidationException("must be one of ${enumValues<E>().joinToString()}", field)
    }

    fun toDomain(n: WorkflowNodeJson): WorkflowNode {
        val id = n.id.trim()
        val kind = enum<NodeKind>(n.kind, "nodes.$id.kind")
        val position = Point(n.position.x, n.position.y)
        val name = n.name.trim().ifBlank { defaultName(kind) }
        return when (kind) {
            NodeKind.START -> WorkflowNode.Start(id, name, position, n.timeoutSeconds, n.maxVisits)
            NodeKind.END -> WorkflowNode.End(id, name, position, n.timeoutSeconds, n.maxVisits, EndSpec(enum<RunOutcome>(n.end?.outcome ?: "SUCCESS", "nodes.$id.end.outcome")))
            NodeKind.TASK -> {
                val task = n.task ?: throw ValidationException("task section is required for TASK nodes", "nodes.$id.task")
                val templateKey = task.templateKey.trim()
                val former = ActionCatalog.FORMER_TEMPLATES[templateKey]
                if (former != null) {
                    WorkflowNode.Action(id, name, position, n.timeoutSeconds, n.maxVisits, formerActionTemplate(former, task.overrides))
                } else {
                    WorkflowNode.Task(id, name, position, n.timeoutSeconds, n.maxVisits, TaskBinding(templateKey, toDomain(task.overrides)))
                }
            }
            NodeKind.ACTION -> {
                val action = n.action ?: throw ValidationException("action section is required for ACTION nodes", "nodes.$id.action")
                WorkflowNode.Action(id, name, position, n.timeoutSeconds, n.maxVisits, ActionBinding(enum<ActionType>(action.type, "nodes.$id.action.type"), action.config))
            }
            NodeKind.DECISION -> {
                val decision = n.decision ?: throw ValidationException("decision section is required for DECISION nodes", "nodes.$id.decision")
                WorkflowNode.Decision(
                    id, name, position, n.timeoutSeconds, n.maxVisits,
                    DecisionSpec(decision.branches.map { DecisionBranch(it.id.trim(), it.label, it.expression.trim()) }, decision.defaultBranch?.trim()?.takeIf { it.isNotBlank() }),
                )
            }
            NodeKind.HUMAN_GATE -> {
                val gate = n.gate ?: throw ValidationException("gate section is required for HUMAN_GATE nodes", "nodes.$id.gate")
                WorkflowNode.HumanGate(
                    id, name, position, n.timeoutSeconds, n.maxVisits,
                    GateSpec(
                        gate.question, gate.options.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { GateSpec.DEFAULT_OPTIONS }, gate.showArtifacts, gate.timeoutSeconds,
                        toDomain(gate.approvers), gate.checklist.map(::toDomain),
                    ),
                )
            }
            NodeKind.WAIT_FOR_CI -> {
                val ci = n.ci ?: throw ValidationException("ci section is required for WAIT_FOR_CI nodes", "nodes.$id.ci")
                WorkflowNode.WaitForCi(
                    id, name, position, n.timeoutSeconds, n.maxVisits,
                    CiWaitSpec(ci.signal.trim(), ci.timeoutSeconds, ci.watchPipeline == true, ci.repository?.trim()?.takeIf { it.isNotEmpty() }),
                )
            }
            NodeKind.NOTE -> WorkflowNode.Note(id, name, position, n.timeoutSeconds, n.maxVisits, NoteSpec(n.note?.text ?: ""))
        }
    }

    fun toJson(n: WorkflowNode): WorkflowNodeJson = WorkflowNodeJson(
        id = n.id, kind = n.kind.name, name = n.name, position = PointJson(n.position.x, n.position.y), timeoutSeconds = n.timeoutSeconds, maxVisits = n.maxVisits,
        task = (n as? WorkflowNode.Task)?.let { TaskSectionJson(it.task.templateKey, toJson(it.task.overrides)) },
        action = (n as? WorkflowNode.Action)?.let { ActionSectionJson(it.action.type.name, it.action.config) },
        decision = (n as? WorkflowNode.Decision)?.let { d -> DecisionSectionJson(d.decision.branches.map { DecisionBranchJson(it.id, it.label, it.expression) }, d.decision.defaultBranch) },
        gate = (n as? WorkflowNode.HumanGate)?.let {
            GateSectionJson(it.gate.question, it.gate.options, it.gate.showArtifacts, it.gate.timeoutSeconds, toJson(it.gate.approvers), it.gate.checklist.map(::toJson))
        },
        ci = (n as? WorkflowNode.WaitForCi)?.let { CiSectionJson(it.ci.signal, it.ci.timeoutSeconds, it.ci.watchPipeline.takeIf { w -> w }, it.ci.repository) },
        end = (n as? WorkflowNode.End)?.let { EndSectionJson(it.end.outcome.name) },
        note = (n as? WorkflowNode.Note)?.let { NoteSectionJson(it.note.text) },
    )

    /**
     * The step's overrides; a legacy config key `agent` naming a harness becomes [TaskOverrides.agentKey] unless one is
     * set. ADR 0062 compatibility: `systemPromptAppend` / `promptAppend` read as the append, `promptReplace` as the
     * replacement, `parameters` values as VALUE bindings; the config keys `session`, `continueSession` and `directory` are
     * dropped (they are the agent's now).
     */
    fun toDomain(o: TaskOverridesJson): TaskOverrides {
        val legacy = legacyAgentKey(o.config)
        val bindings = o.parameterBindings.orEmpty().mapValues { (name, b) -> toDomain(name, b) }
        return TaskOverrides(
            agentKey = o.agentKey?.trim()?.takeIf { it.isNotEmpty() } ?: legacy,
            modelProfileId = o.modelProfileId?.let(::ModelProfileId),
            instructionsAppend = TaskOverrides.joinedAppend(o.instructionsAppend, o.systemPromptAppend, o.promptAppend),
            instructionsReplace = o.instructionsReplace?.takeIf { it.isNotBlank() } ?: o.promptReplace?.takeIf { it.isNotBlank() },
            config = (if (legacy != null) o.config - LEGACY_AGENT_KEY else o.config) - LEGACY_WORKSPACE_KEY - LEGACY_AGENT_STEP_KEYS,
            parameterBindings = TaskOverrides.withLegacyValues(bindings, o.parameters),
        )
    }

    /** ADR 0062: one binding; an unknown source is a validation error. */
    private fun toDomain(name: String, b: ParameterBindingJson): ParameterBinding {
        val source = ParameterBindingSource.entries.firstOrNull { it.name.equals(b.source.trim(), ignoreCase = true) }
            ?: throw ValidationException("must be one of ${ParameterBindingSource.entries.joinToString()}", "task.overrides.parameterBindings.$name.source")
        return when (source) {
            ParameterBindingSource.VALUE -> ParameterBinding.value(b.value)
            ParameterBindingSource.START -> ParameterBinding.START
            ParameterBindingSource.ARTIFACT -> ParameterBinding(source, artifact = b.artifact?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    fun toJson(o: TaskOverrides) = TaskOverridesJson(
        agentKey = o.agentKey,
        modelProfileId = o.modelProfileId?.value,
        instructionsAppend = o.instructionsAppend,
        instructionsReplace = o.instructionsReplace,
        config = o.config,
        parameterBindings = o.parameterBindings.takeIf { it.isNotEmpty() }?.mapValues { (_, b) -> ParameterBindingJson(b.source.name, b.value, b.artifact) },
    )

    /** The former config key that chose the coding agent harness of a step. */
    private const val LEGACY_AGENT_KEY = "agent"

    /** ADR 0044 run workspace of a step; ADR 0060: dropped, the run directory replaces it. */
    const val LEGACY_WORKSPACE_KEY = "workspace"

    /** ADR 0060 step keys that ADR 0062 moved to the agent (`Agent.session`, `Agent.access.directory`); dropped on read. */
    val LEGACY_AGENT_STEP_KEYS = setOf("session", "continueSession", "directory")

    private fun legacyAgentKey(config: Map<String, Any?>): String? =
        (config[LEGACY_AGENT_KEY] as? String)?.let { value -> AgentHarness.entries.firstOrNull { it.name == value } }?.let { AgentSnapshot.keyOf(it) }

    /**
     * ADR 0056: a step of a former action template as its action: the step's config (the template defaults apply at run
     * time) and, for a comment, the step's replacement text (`instructionsReplace`, formerly `promptReplace`) as the
     * message when the config has none.
     */
    private fun formerActionTemplate(type: ActionType, overrides: TaskOverridesJson): ActionBinding {
        val message = overrides.instructionsReplace?.takeIf { it.isNotBlank() } ?: overrides.promptReplace?.takeIf { it.isNotBlank() }
        val config = if (type == ActionType.COMMENT_ISSUE && message != null && overrides.config[ActionCatalog.MESSAGE_TEMPLATE] == null) {
            overrides.config + (ActionCatalog.MESSAGE_TEMPLATE to message)
        } else {
            overrides.config
        }
        return ActionBinding(type, config)
    }

    fun toDomain(a: GateApproversJson?): GateApprovers = a?.let {
        GateApprovers(
            projectRoles = it.projectRoles.map { r -> r.trim() }.filter { r -> r.isNotEmpty() },
            users = it.users.distinct(),
            accountable = it.accountable?.trim()?.takeIf { r -> r.isNotEmpty() },
            forbidSelfApproval = it.forbidSelfApproval,
        )
    } ?: GateApprovers.ANYONE

    fun toJson(a: GateApprovers): GateApproversJson = GateApproversJson(a.projectRoles, a.users, a.accountable, a.forbidSelfApproval)

    fun toDomain(c: ChecklistItemJson) = ChecklistItem(c.id.trim(), c.text.trim(), c.required)
    fun toJson(c: ChecklistItem) = ChecklistItemJson(c.id, c.text, c.required)

    fun toDomain(e: WorkflowEdgeJson) = WorkflowEdge(e.id.trim().ifBlank { "${e.from}-${e.fromPort}-${e.to}" }, e.from.trim(), e.fromPort.trim(), e.to.trim(), e.label?.takeIf { it.isNotBlank() })
    fun toJson(e: WorkflowEdge) = WorkflowEdgeJson(e.id, e.from, e.fromPort, e.to, e.label)

    fun toDomain(t: TriggerJson) = Trigger(enum<TriggerType>(t.type, "triggers.type"), t.status?.takeIf { it.isNotBlank() }, t.intervalMinutes)
    fun toJson(t: Trigger) = TriggerJson(t.type.name, t.status, t.intervalMinutes)

    fun toDomain(c: CanvasJson?) = c?.let { Canvas(it.zoom, it.x, it.y) }
    fun toJson(c: Canvas?) = c?.let { CanvasJson(it.zoom, it.x, it.y) }

    fun toCommand(d: WorkflowDocumentJson) = ManageWorkflows.WorkflowCommand(
        name = d.name.trim(),
        description = d.description,
        nodes = d.nodes.map { toDomain(it) },
        edges = d.edges.map { toDomain(it) },
        triggers = d.triggers.map { toDomain(it) }.ifEmpty { listOf(Trigger(TriggerType.MANUAL)) },
        enabled = d.enabled,
        canvas = toDomain(d.canvas),
        setupCommand = d.setupCommand?.takeIf { it.isNotBlank() },
    )

    fun toDocument(d: WorkflowDefinition) = WorkflowDocumentJson(
        name = d.name,
        description = d.description,
        nodes = d.nodes.map { toJson(it) },
        edges = d.edges.map { toJson(it) },
        triggers = d.triggers.map { toJson(it) },
        enabled = d.enabled,
        canvas = toJson(d.canvas),
        setupCommand = d.setupCommand,
    )

    private fun defaultName(kind: NodeKind) = when (kind) {
        NodeKind.START -> "Start"
        NodeKind.END -> "End"
        NodeKind.NOTE -> "Note"
        else -> kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
