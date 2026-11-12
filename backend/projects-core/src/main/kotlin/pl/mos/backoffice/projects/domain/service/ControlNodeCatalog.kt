package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.WorkflowNode

/** Designer-facing description of a control node kind: title, description, ports and a JSON Schema for its section. */
data class ControlNodeDescriptor(
    val kind: NodeKind,
    val title: String,
    val description: String,
    val ports: List<String>,
    val configSchema: Map<String, Any?>,
)

/** A field of the run state available to decision expressions (`WorkflowStateView`). */
data class StateField(val path: String, val description: String)

/** Static catalogue of control nodes, step and action config schemas and expression state fields for the designer palette. */
object ControlNodeCatalog {

    fun controlNodes(): List<ControlNodeDescriptor> = listOf(
        ControlNodeDescriptor(NodeKind.START, "Start", "Entry point; exactly one per workflow.", listOf(WorkflowNode.PORT_OUT), schema(emptyMap())),
        ControlNodeDescriptor(
            NodeKind.END, "End", "Terminal node; its outcome decides the run outcome and the CI gate result.", emptyList(),
            schema(mapOf("end" to obj(mapOf("outcome" to enum("Run outcome", listOf("SUCCESS", "FAILURE"), "SUCCESS")), listOf("outcome")))),
        ),
        ControlNodeDescriptor(
            NodeKind.DECISION, "Decision", "Routes on boolean SpEL expressions over the run state; the first true branch wins.", listOf("<branch id>"),
            schema(
                mapOf(
                    "decision" to obj(
                        mapOf(
                            "branches" to mapOf(
                                "type" to "array", "minItems" to 1,
                                "items" to obj(
                                    mapOf(
                                        "id" to string("Branch id (output port)"),
                                        "label" to string("Label shown on the edge"),
                                        "expression" to string("SpEL boolean, e.g. confidence >= 0.8"),
                                    ),
                                    listOf("id", "label", "expression"),
                                ),
                            ),
                            "defaultBranch" to string("Port taken when no expression is true; omitted = the run fails"),
                        ),
                        listOf("branches"),
                    ),
                ),
            ),
        ),
        ControlNodeDescriptor(
            NodeKind.HUMAN_GATE, "Human gate", "Pauses the run; an approver picks an option in the inbox. Each option is an output port.",
            GateSpec.DEFAULT_OPTIONS + WorkflowNode.PORT_TIMEOUT,
            schema(
                mapOf(
                    "gate" to obj(
                        mapOf(
                            "question" to string("Question shown in the inbox (template, e.g. {{issue.key}})"),
                            "options" to stringArray("Answer options (output ports)", GateSpec.DEFAULT_OPTIONS),
                            "showArtifacts" to stringArray("Artefact names shown next to the question"),
                            "timeoutSeconds" to integer("Expire the approval and leave through the timeout port after this many seconds"),
                        ),
                        listOf("question"),
                    ),
                ),
            ),
        ),
        ControlNodeDescriptor(
            NodeKind.WAIT_FOR_CI, "Wait for CI",
            "Pauses until the project's CI reports the signal with backoffice-ci signal <name>, or - with watchPipeline - until the " +
                "pipeline of the run's branch finishes (Back Office reads it with the project's CI_READ token; failed jobs and " +
                "their log tails go to the next step as feedback).",
            listOf(WorkflowNode.PORT_SUCCESS, WorkflowNode.PORT_FAIL, WorkflowNode.PORT_TIMEOUT),
            schema(
                mapOf(
                    "ci" to obj(
                        mapOf(
                            "signal" to string("Signal name, e.g. tests", pattern = WorkflowNode.SIGNAL_PATTERN.pattern),
                            "timeoutSeconds" to integer("Leave through the timeout port after this many seconds"),
                            "watchPipeline" to boolean("Back Office watches the pipeline of the run's branch itself (needs a CI_READ token of the repository)", false),
                            "repository" to string("Repository key whose pipeline is watched; empty = the primary repository"),
                        ),
                        listOf("signal"),
                    ),
                ),
            ),
        ),
        ControlNodeDescriptor(
            NodeKind.NOTE, "Note", "Sticky note on the canvas; never executed.", emptyList(),
            schema(mapOf("note" to obj(mapOf("text" to string("Markdown text")), emptyList()))),
        ),
    )

    /** ADR 0056: JSON Schema for `overrides.config` of a step a model answer agent runs (the former LLM executor). */
    fun modelAnswerConfigSchema(): Map<String, Any?> = schema(
        mapOf(
            "maxInputChars" to integer("Truncate rendered prompts beyond this many characters", 200_000),
        ) + questionSettings(),
    )

    /**
     * ADR 0056: JSON Schema for `overrides.config` of a step a coding agent runs. The harness is the agent's, so there is
     * no `agent` key. ADR 0063: agents never push; the git keys belong to `DELIVER_CHANGES`, the repositories and the
     * setup command to the workflow, the output files to the template.
     */
    fun codingAgentConfigSchema(): Map<String, Any?> = schema(
        mapOf(
            "verifyCommand" to string("Command run after the agent, e.g. ./gradlew test; a non-zero exit fails the step"),
            CODING_AGENT to boolean("The work needs a coding agent in the workflow's sources (a model answer cannot do it)", false),
        ) + directorySettings() + questionSettings(),
    )

    /** ADR 0061/0063: the config key that says a step needs a coding agent. */
    const val CODING_AGENT = "codingAgent"

    /** ADR 0056: JSON Schema for `action.config` of an action node. */
    fun actionConfigSchema(type: ActionType): Map<String, Any?> = when (type) {
        ActionType.FETCH_ISSUE -> schema(
            mapOf("issueKeyFrom" to string("Where to read the issue key: input (default) or a run parameter name", "input")),
        )
        ActionType.COMMENT_ISSUE -> schema(mapOf("messageTemplate" to string("Jira comment body (template)"), APPROVAL_FIELD to approvalSetting()), listOf("messageTemplate"))
        ActionType.TRANSITION_ISSUE -> schema(
            mapOf(
                "toStatus" to string("Target Jira status name, e.g. In Review"),
                "failIfMissing" to boolean("Fail when the issue's workflow offers no transition to that status (off: succeed with a note)", true),
                APPROVAL_FIELD to approvalSetting(),
            ),
            listOf("toStatus"),
        )
        ActionType.START_WORKFLOW -> schema(
            mapOf(
                "workflow" to string("Id or name of the workflow of this project to start"),
                "parametersFrom" to string("Artefact with the run parameters: a JSON object (one run) or an array of objects (one run per entry)"),
                "selection" to enum("ALL: every entry; DECISION: the entries whose numbers the owner wrote in the comment of the gate just before (or all)", listOf("ALL", "DECISION"), "ALL"),
            ),
            listOf("workflow", "parametersFrom"),
        )
        ActionType.PROJECT_DIGEST -> schema(
            mapOf(
                "maxRuns" to integer("At most this many runs (newest first)"),
                "pipelineRefs" to string("Comma-separated branches whose latest pipeline is reported, e.g. develop,main (needs a CI_READ token)"),
            ),
        )
        ActionType.DELIVER_CHANGES -> schema(
            mapOf(
                ActionCatalog.BRANCH_TEMPLATE to string("Branch pushed (template; never the default branch), default: the run's branch, else feature/{{issue.key | lower}}"),
                ActionCatalog.COMMIT_SUBJECT_TEMPLATE to string("Commit subject (template), default {{issue.key}}: {{issue.summary}}"),
                ActionCatalog.MERGE_REQUEST to boolean("Open a merge request with git push options (never merged by Back Office)", true),
                ActionCatalog.MERGE_REQUEST_TITLE_TEMPLATE to string("Merge request title (template), default {{issue.key}}: {{issue.summary}}"),
                ActionCatalog.MERGE_REQUEST_DESCRIPTION_TEMPLATE to string("Merge request description (template)"),
                ActionCatalog.MERGE_REQUEST_DRAFT to boolean("Open the merge request as draft", false),
                ActionCatalog.REPOSITORIES to deliveredRepositoriesSetting(),
            ),
        )
    }

    /**
     * ADR 0063: the project repositories a delivery writes back to. `x-editor: repositories` tells the designer to render
     * its repository picker. Absent = every project repository of the workflow's sources that has changes.
     */
    private fun deliveredRepositoriesSetting(): Map<String, Any?> = mapOf(
        "type" to "array",
        "x-editor" to "repositories",
        "description" to "Project repositories to deliver (keys); absent = every project repository among the workflow's sources that has " +
            "changes. Library sources are read-only.",
        "items" to mapOf("type" to "string"),
    )

    private const val APPROVAL_FIELD = "approval"

    /**
     * ADR 0060: prepare-only (ADR 0062: the directory and the session are the agent's). `x-editor: directory` tells the
     * designer to render it in its directory section instead of as a generic field.
     */
    private fun directorySettings(): Map<String, Any?> = mapOf(
        RunDirectory.PREPARE_ONLY to (boolean("Only prepare the run directory: clone the workflow's sources and run its setup, no agent", false) + DIRECTORY_EDITOR),
    )

    private val DIRECTORY_EDITOR = mapOf("x-editor" to "directory")

    /** ADR 0060: whether the agent may stop with questions to the owner, and how many rounds per step. */
    private fun questionSettings(): Map<String, Any?> = mapOf(
        RunDirectory.ALLOW_QUESTIONS to boolean("The agent may stop with questions to the owner instead of guessing; the run waits for the answers", true),
        RunDirectory.MAX_QUESTION_ROUNDS to integer("Most rounds of questions per execution of the step; after that the agent must finish with what it has", RunDirectory.DEFAULT_MAX_QUESTION_ROUNDS),
    )

    /** ADR 0033: approval before the external write; missing = REQUIRED. Who approves is `approvers` (designer section). */
    private fun approvalSetting(): Map<String, Any?> =
        enum("Approval before the Jira write: REQUIRED pauses for a person to confirm the exact write", listOf("REQUIRED", "NOT_REQUIRED"), "REQUIRED")

    fun stateFields(): List<StateField> = listOf(
        StateField("input.issueKey", "Issue key the run was started with"),
        StateField("input.parameters['name']", "Run parameter (also CI --var values)"),
        StateField("input.ci.provider", "CI provider of a CI-triggered run (GITLAB, GITHUB, OTHER)"),
        StateField("input.ci.ref", "Git ref of the CI job"),
        StateField("input.ci.commitSha", "Commit reviewed by the CI job"),
        StateField("input.ci.mergeRequest.targetBranch", "Target branch of the merge request under review"),
        StateField("input.ci.mergeRequest.sourceBranch", "Source branch of the merge request under review"),
        StateField("issue.key", "Jira issue key after fetch-issue"),
        StateField("issue.status", "Jira issue status"),
        StateField("issue.type", "Jira issue type"),
        StateField("issue.priority", "Jira issue priority"),
        StateField("issue.labels", "Jira labels (list)"),
        StateField("artifacts['spec']", "Artefact by name: {id, name, kind, sizeBytes} or null"),
        StateField("confidence", "Last numeric confidence produced by a task (0..1)"),
        StateField("lastOutcome", "SUCCESS or FAILURE of the previous node"),
        StateField("review.verdict", "Last review verdict: APPROVE, COMMENT, REQUEST_CHANGES"),
        StateField("review.score", "Last review score 0..100"),
        StateField("review.blockers", "Number of BLOCKER findings"),
        StateField("review.majors", "Number of MAJOR findings"),
        StateField("mr.url", "Merge request URL after Deliver changes"),
        StateField("mr.branch", "Branch pushed by Deliver changes"),
        StateField("mr.targetBranch", "Target branch of the merge request"),
        StateField("ci.signal", "Name of the last CI signal"),
        StateField("ci.status", "SUCCESS or FAIL of the last CI signal"),
        StateField("ci.message", "Message sent with the last CI signal"),
        StateField("decision.option", "Option chosen at the last human gate"),
        StateField("decision.comment", "Comment left at the last human gate"),
        StateField("feedback", "All revision comments (list of strings)"),
    )

    private fun schema(properties: Map<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> = mapOf(
        "\$schema" to "https://json-schema.org/draft/2020-12/schema",
        "type" to "object",
        "properties" to properties,
        "required" to required,
        "additionalProperties" to true,
    )

    private fun obj(properties: Map<String, Any?>, required: List<String>): Map<String, Any?> =
        mapOf("type" to "object", "properties" to properties, "required" to required)

    private fun string(description: String, default: String? = null, pattern: String? = null): Map<String, Any?> =
        listOfNotNull("type" to "string", "description" to description, default?.let { "default" to it }, pattern?.let { "pattern" to it }).toMap()

    private fun integer(description: String, default: Int? = null): Map<String, Any?> =
        listOfNotNull("type" to "integer", "description" to description, default?.let { "default" to it }).toMap()

    private fun boolean(description: String, default: Boolean): Map<String, Any?> =
        mapOf("type" to "boolean", "description" to description, "default" to default)

    private fun enum(description: String, values: List<String>, default: String? = null): Map<String, Any?> =
        listOfNotNull("type" to "string", "description" to description, "enum" to values, default?.let { "default" to it }).toMap()

    private fun stringArray(description: String, default: List<String>? = null): Map<String, Any?> =
        listOfNotNull("type" to "array", "description" to description, "items" to mapOf("type" to "string"), default?.let { "default" to it }).toMap()
}
