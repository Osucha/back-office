package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ActionBinding
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType

/** Designer-facing description of an action (ADR 0056): what the palette shows and what a new node starts with. */
data class ActionDescriptor(
    val type: ActionType,
    val title: String,
    val description: String,
    /** Config values a new node starts with; at run time keys the node does not set take these values. */
    val defaults: Map<String, Any?>,
    val configSchema: Map<String, Any?>,
    /** Run start form fields the action asks for (ADR 0053). */
    val runInputs: List<RunInputField>,
    /** Artefact / state names the action produces. */
    val produces: List<String>,
) {
    val ports: List<String> get() = WorkflowNode.DEFAULT_TASK_PORTS
    val requiredCapabilities: Set<ConnectionCapability> get() = ConnectionCapability.requiredBy(type)

    /** The action writes to an external system: approval before write applies (ADR 0033). */
    val externalWrite: Boolean get() = requiredCapabilities.any { it.write }
}

/**
 * ADR 0056: the actions of a workflow, the former built-in action templates of the task library. Their defaults are
 * the former template configs; a node's config is deep-merged over them at run time ([effectiveConfig]).
 */
object ActionCatalog {

    /** The run's Jira issue key, read by the Jira actions; mapped to the run's issue key. */
    val ISSUE_KEY_INPUT = RunInputField("issueKey", "Jira issue key", RunInputType.ISSUE_KEY, required = true, help = "For example ABC-123.")

    /** Comment of a COMMENT_ISSUE node without a message of its own. */
    const val DEFAULT_COMMENT = "Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}"

    /** Config key of the comment template. */
    const val MESSAGE_TEMPLATE = "messageTemplate"

    /** The former built-in action template of each action; a TASK node naming one is read as that action. */
    val FORMER_TEMPLATES: Map<String, ActionType> = mapOf(
        "fetch-issue" to ActionType.FETCH_ISSUE,
        "notify-jira" to ActionType.COMMENT_ISSUE,
        "transition-issue" to ActionType.TRANSITION_ISSUE,
        "start-workflow" to ActionType.START_WORKFLOW,
        "project-digest" to ActionType.PROJECT_DIGEST,
    )

    val all: List<ActionDescriptor> = listOf(
        ActionDescriptor(
            ActionType.FETCH_ISSUE, "Fetch issue",
            "Loads the issue that triggered the run from the tracker (summary, description, acceptance criteria, labels; " +
                "no comments or attachments) and stores it as the issue state.",
            emptyMap(), ControlNodeCatalog.actionConfigSchema(ActionType.FETCH_ISSUE), listOf(ISSUE_KEY_INPUT), listOf("issue"),
        ),
        ActionDescriptor(
            ActionType.COMMENT_ISSUE, "Comment on issue",
            "Posts a comment on the issue; the message is a template over the run state.",
            mapOf(MESSAGE_TEMPLATE to DEFAULT_COMMENT), ControlNodeCatalog.actionConfigSchema(ActionType.COMMENT_ISSUE), listOf(ISSUE_KEY_INPUT), emptyList(),
        ),
        ActionDescriptor(
            ActionType.TRANSITION_ISSUE, "Transition issue",
            "Moves the issue to a target status by transition name.",
            mapOf("toStatus" to "In Review", "failIfMissing" to false), ControlNodeCatalog.actionConfigSchema(ActionType.TRANSITION_ISSUE),
            listOf(ISSUE_KEY_INPUT), emptyList(),
        ),
        ActionDescriptor(
            ActionType.START_WORKFLOW, "Start workflow",
            "Starts another workflow of the project (ADR 0052), right after a human gate: one run per entry of a JSON " +
                "artefact (for example accepted proposals), with the entry's fields as run parameters. Produces started-runs.",
            mapOf("workflow" to "", "parametersFrom" to "proposals", "selection" to "DECISION"),
            ControlNodeCatalog.actionConfigSchema(ActionType.START_WORKFLOW), emptyList(), listOf("started-runs"),
        ),
        ActionDescriptor(
            ActionType.PROJECT_DIGEST, "Project digest",
            "Writes what happened in the project since the run parameter since (ADR 0052): runs of other workflows with " +
                "outcome, duration and cost, repeated and failed steps, gate decisions, and the latest pipelines of pipelineRefs. Produces digest.",
            mapOf("maxRuns" to 20, "pipelineRefs" to "develop,main"), ControlNodeCatalog.actionConfigSchema(ActionType.PROJECT_DIGEST),
            emptyList(), listOf("digest"),
        ),
        ActionDescriptor(
            ActionType.DELIVER_CHANGES, "Deliver changes",
            "Commits what the agents changed in the run directory, pushes a branch (never the default branch) and opens a merge " +
                "request (ADR 0063). The only step with push rights: a project repository token with PUSH_BRANCH, else the " +
                "runner pool's token. Delivers every project repository of the workflow's sources that has changes, or the " +
                "listed ones. Produces mr and diff.",
            mapOf(MERGE_REQUEST to true), ControlNodeCatalog.actionConfigSchema(ActionType.DELIVER_CHANGES), emptyList(), listOf("mr", "diff"),
        ),
    )

    /** ADR 0063: config keys of `DELIVER_CHANGES` (the former git keys of a coding step). */
    const val BRANCH_TEMPLATE = "branchTemplate"
    const val COMMIT_SUBJECT_TEMPLATE = "commitSubjectTemplate"
    const val MERGE_REQUEST = "mergeRequest"
    const val MERGE_REQUEST_TITLE_TEMPLATE = "mergeRequestTitleTemplate"
    const val MERGE_REQUEST_DESCRIPTION_TEMPLATE = "mergeRequestDescriptionTemplate"
    const val MERGE_REQUEST_DRAFT = "mergeRequestDraft"
    const val REPOSITORIES = "repositories"

    /** ADR 0063: the git keys a coding step had and `DELIVER_CHANGES` now carries. */
    val DELIVERY_KEYS = listOf(
        BRANCH_TEMPLATE, COMMIT_SUBJECT_TEMPLATE, MERGE_REQUEST, MERGE_REQUEST_TITLE_TEMPLATE, MERGE_REQUEST_DESCRIPTION_TEMPLATE, MERGE_REQUEST_DRAFT,
    )

    private val byType = all.associateBy { it.type }

    fun of(type: ActionType): ActionDescriptor = byType.getValue(type)

    /** The node's config deep-merged over the action's defaults; a `null` value in the node removes the key. */
    fun effectiveConfig(binding: ActionBinding): Map<String, Any?> = deepMerge(of(binding.type).defaults, binding.config)

    /**
     * The run inputs an action node asks for: the action's, with the `ISSUE_KEY` field optional when the node reads the
     * issue key from another run parameter (`issueKeyFrom` other than `input`).
     */
    fun runInputs(binding: ActionBinding): List<RunInputField> {
        val inputs = of(binding.type).runInputs
        val from = effectiveConfig(binding)[ISSUE_KEY_FROM]?.toString()?.trim()
        if (from.isNullOrEmpty() || from == ISSUE_KEY_FROM_INPUT) return inputs
        return inputs.map { if (it.type == RunInputType.ISSUE_KEY) it.copy(required = false) else it }
    }

    const val ISSUE_KEY_FROM = "issueKeyFrom"
    const val ISSUE_KEY_FROM_INPUT = "input"

    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(base: Map<String, Any?>, patch: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap(base)
        for ((key, value) in patch) {
            val existing = result[key]
            when {
                value == null -> result.remove(key)
                value is Map<*, *> && existing is Map<*, *> -> result[key] = deepMerge(existing as Map<String, Any?>, value as Map<String, Any?>)
                else -> result[key] = value
            }
        }
        return result
    }
}
