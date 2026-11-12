package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.time.Instant

/**
 * The shipped library of docs/architecture/07-task-library.md: global templates, five agents, eight skills and four
 * rules (ADR 0055/0057), including the SDLC conventions of ADR 0019 (user story, use case, ADR, change-request impact
 * analysis, task list planning and execution, review against the specification). Every concrete template has a category.
 *
 * ADR 0057: the agents carry the environment (the ground rules `agent-ground-rules` and `ask-dont-guess`, the skills of
 * their role, short instructions); the platform preamble that the abstract `base-*` templates held is rendered by the
 * Projects context. ADR 0058: the built-in templates are flat (only `implement-task-list` and the deprecated
 * `implement-claude-code` extend `implement-opencode`); `base-agent`, `base-llm` and `base-coding-agent` stay as empty,
 * deprecated abstract templates for user templates that extend them. `llm-task` and `agent-task` take their task as the
 * parameter `task`. ADR 0061: templates name no agent and no model; the skills they require guide the designer's choice
 * of the step's agent.
 *
 * Prompts use the executor placeholders `{{issue.*}}`, `{{artifacts.*}}`, `{{params.*}}` (ADR 0058),
 * `{{runInputText}}` (ADR 0053: the run inputs the task declares; templates that ask for `input` render them as a "Run
 * input" section of their system prompt), `{{input.ci.*}}` and `{{feedback}}` (docs/architecture/03-pipelines-and-agents.md),
 * and sections `{{#path}}...{{/path}}` so that empty values (no issue, no review yet, no feedback) leave no empty
 * headings behind. `{{feedback}}` holds only the revision comments given for the step that renders it. The project
 * constitution is part of the agent's environment and no longer placed by templates.
 */
object BuiltInLibrary {

    // ----- keys ---------------------------------------------------------------------------------------

    /** ADR 0057/0058: deprecated, empty abstract root (its text is the platform preamble now); kept for user templates. */
    const val BASE_AGENT = "base-agent"

    /** Deprecated, empty abstract template for model answer tasks (ADR 0058); kept for user templates that extend it. */
    const val BASE_LLM = "base-llm"

    /** Deprecated abstract template for coding agent tasks (agent OpenCode); kept for user templates that extend it. */
    const val BASE_CODING_AGENT = "base-coding-agent"

    const val FETCH_ISSUE = "fetch-issue"
    const val SPEC_KIT_SPECIFY = "spec-kit-specify"
    const val CLARIFY_SPEC = "clarify-spec"
    const val SPEC_KIT_PLAN = "spec-kit-plan"
    const val SPEC_KIT_TASKS = "spec-kit-tasks"
    const val IMPLEMENT_OPENCODE = "implement-opencode"
    const val IMPLEMENT_CLAUDE_CODE = "implement-claude-code"
    const val CODE_REVIEW = "code-review"
    const val ANALYSE_ISSUE = "analyse-issue"
    const val NOTIFY_JIRA = "notify-jira"
    const val TRANSITION_ISSUE = "transition-issue"
    const val START_WORKFLOW = "start-workflow"
    const val PROJECT_DIGEST = "project-digest"

    /** ADR 0054: a built-in template that stays for existing workflows but is hidden from the palette and the list. */
    const val DEPRECATED_TAG = "deprecated"

    /** Generic LLM task of projects without repositories (ADR 0034): the task is the parameter `task` (ADR 0058). */
    const val LLM_TASK = "llm-task"

    /** Generic coding-agent task: any task (parameter `task`), run by an agent in the project's repository (no push by default). */
    const val AGENT_TASK = "agent-task"

    /** ADR 0044: clones the step's repositories and sources into the run workspace and runs setupCommand; no agent. */
    const val PREPARE_WORKSPACE = "prepare-workspace"

    /** ADR 0058: the parameter of `llm-task` and `agent-task` that holds the task. */
    const val TASK_PARAMETER = "task"

    // ADR 0057: the built-in agents besides model-answer, opencode and claude-code
    const val ANALYST = "analyst"
    const val REVIEWER = "reviewer"

    const val SPEC_WRITING = "spec-writing"
    const val KOTLIN_HEXAGONAL = "kotlin-hexagonal"
    const val CONVENTIONAL_COMMITS = "conventional-commits"
    const val SECURITY_REVIEW_CHECKLIST = "security-review-checklist"
    const val ANGULAR_NG_ZORRO = "angular-ng-zorro"

    // SDLC conventions derived from a reference framework (ADR 0019)
    const val WRITE_USER_STORY = "write-user-story"
    const val WRITE_USE_CASE = "write-use-case"
    const val WRITE_ADR = "write-adr"
    const val IMPACT_ANALYSIS = "impact-analysis"
    const val PLAN_TASK_LIST = "plan-task-list"
    const val IMPLEMENT_TASK_LIST = "implement-task-list"
    const val REVIEW_AGAINST_SPEC = "review-against-spec"

    const val AGENT_GROUND_RULES = "agent-ground-rules"
    const val ASK_DONT_GUESS = "ask-dont-guess"
    const val DEFINITION_OF_READY_DONE = "definition-of-ready-done"
    const val USER_STORY_FORMAT = "user-story-format"
    const val USE_CASE_FORMAT = "use-case-format"
    const val ADR_FORMAT = "adr-format"
    const val BUILD_GUARDRAILS = "build-guardrails"

    /** Artifact and tag names used by several entries; the impact analysis artifact is named like its template. */
    private const val ARTIFACT_USER_STORY = "user-story"
    private const val ARTIFACT_USE_CASE = "use-case"
    private const val ARTIFACT_IMPACT_ANALYSIS = IMPACT_ANALYSIS
    private const val ARTIFACT_WORK_LOG = "work-log"
    private const val TAG_SPEC_KIT = "spec-kit"

    /** Title of the task every generated task list must end with (poc/WORKFLOW.md of the reference framework). */
    const val FINAL_TASK_TITLE = "reformat, lint, and test"

    val sdlcTemplateKeys = listOf(WRITE_USER_STORY, WRITE_USE_CASE, WRITE_ADR, IMPACT_ANALYSIS, PLAN_TASK_LIST, IMPLEMENT_TASK_LIST, REVIEW_AGAINST_SPEC)
    val sdlcSkillKeys = listOf(DEFINITION_OF_READY_DONE, USER_STORY_FORMAT, USE_CASE_FORMAT, ADR_FORMAT, BUILD_GUARDRAILS)

    val templateKeys = listOf(
        BASE_AGENT, BASE_LLM, BASE_CODING_AGENT, SPEC_KIT_SPECIFY, CLARIFY_SPEC, SPEC_KIT_PLAN, SPEC_KIT_TASKS,
        IMPLEMENT_OPENCODE, IMPLEMENT_CLAUDE_CODE, CODE_REVIEW, ANALYSE_ISSUE, LLM_TASK, AGENT_TASK, PREPARE_WORKSPACE,
    ) + sdlcTemplateKeys

    /** ADR 0057/0058: the abstract roots the built-ins no longer extend; deprecated and empty. */
    val deprecatedBaseKeys = listOf(BASE_AGENT, BASE_LLM, BASE_CODING_AGENT)

    /**
     * ADR 0056: the former built-in action templates; their steps are read as action nodes and the seed removes the
     * built-in rows (V304 does the same).
     */
    val formerActionTemplateKeys = listOf(FETCH_ISSUE, NOTIFY_JIRA, TRANSITION_ISSUE, START_WORKFLOW, PROJECT_DIGEST)

    /** ADR 0056/0057: the built-in agents. */
    val agentKeys = listOf(AgentSnapshot.MODEL_ANSWER, ANALYST, REVIEWER, AgentSnapshot.OPENCODE, AgentSnapshot.CLAUDE_CODE)
    val skillKeys = listOf(SPEC_WRITING, CONVENTIONAL_COMMITS, SECURITY_REVIEW_CHECKLIST) + sdlcSkillKeys

    /** ADR 0055: built-in skills that were rules in fact (always in context); moved by V303 in existing databases. */
    val ruleKeys = listOf(AGENT_GROUND_RULES, ASK_DONT_GUESS, KOTLIN_HEXAGONAL, ANGULAR_NG_ZORRO)

    /** ADR 0057: the rules every built-in agent carries (and V305 gives the agents it creates). */
    val baseAgentRules = listOf(AGENT_GROUND_RULES, ASK_DONT_GUESS)

    /** ADR 0057 section 6: the skills of each built-in agent. */
    val agentSkills: Map<String, List<String>> = mapOf(
        AgentSnapshot.MODEL_ANSWER to emptyList(),
        ANALYST to listOf(USER_STORY_FORMAT, USE_CASE_FORMAT, ADR_FORMAT, DEFINITION_OF_READY_DONE, BUILD_GUARDRAILS),
        REVIEWER to listOf(SECURITY_REVIEW_CHECKLIST, DEFINITION_OF_READY_DONE),
        AgentSnapshot.OPENCODE to listOf(SPEC_WRITING, DEFINITION_OF_READY_DONE, BUILD_GUARDRAILS),
        AgentSnapshot.CLAUDE_CODE to listOf(SPEC_WRITING, DEFINITION_OF_READY_DONE, BUILD_GUARDRAILS),
    )

    // ----- templates ----------------------------------------------------------------------------------

    fun templates(now: Instant): List<TaskTemplate> = listOf(
        template(now, TaskTemplate.Content(
            key = BASE_AGENT, name = "Base agent", abstract = true,
            description = "Deprecated (ADR 0057, 0058): the platform preamble and the agent's rules replace it; the built-in templates no longer " +
                "extend it. Kept for templates that extend it: it asks for the run input material and shows it in the system prompt.",
            prompt = Prompt(system = RUN_INPUT_SECTION),
            tags = listOf("base", DEPRECATED_TAG),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = BASE_LLM, name = "Base LLM step", abstract = true, extendsKey = BASE_AGENT,
            description = "Deprecated (ADR 0057, 0058): the platform preamble for model answers replaces it. Kept for templates that extend it.",
            tags = listOf("base", "llm", DEPRECATED_TAG),
        )),
        template(now, TaskTemplate.Content(
            key = BASE_CODING_AGENT, name = "Base coding agent", abstract = true, extendsKey = BASE_AGENT,
            description = "Deprecated (ADR 0057, 0058): the platform preamble for coding agents replaces it. Kept for templates that extend it.",
            tags = listOf("base", "agent", DEPRECATED_TAG),
        )),
        template(now, TaskTemplate.Content(
            key = SPEC_KIT_SPECIFY, name = "Spec Kit: specify", category = TemplateCategory.SPECIFICATION,
            requiresSkills = listOf(SPEC_WRITING),
            description = "Runs the Spec Kit SPECIFY phase in the repository and returns the feature specification. " +
                "The user prompt starts with the subject line and the issue text, which become the feature description.",
            prompt = Prompt(system = RUN_INPUT_SECTION, user = SPECIFY_USER),
            config = mapOf("phase" to "SPECIFY"),
            consumes = listOf("issue"), produces = listOf("spec"), tags = listOf(TAG_SPEC_KIT),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = CLARIFY_SPEC, name = "Clarify specification", category = TemplateCategory.SPECIFICATION,
            requiresSkills = listOf(DEFINITION_OF_READY_DONE),
            description = "Reads the specification as the implementer would and reports confidence plus the questions (markdown) that would block a clean implementation.",
            prompt = Prompt(system = withRunInput(CLARIFY_SYSTEM), user = CLARIFY_USER),
            outputSchema = CLARIFY_SCHEMA, confidenceField = "confidence",
            consumes = listOf("spec"), produces = listOf("confidence", "clarifications"), tags = listOf("spec", "gate"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = SPEC_KIT_PLAN, name = "Spec Kit: plan", category = TemplateCategory.PLANNING,
            requiresSkills = listOf(SPEC_WRITING),
            description = "Runs the Spec Kit PLAN phase: architecture, data model and contracts derived from the specification Spec Kit reads from the repository. " +
                "Optional run input techHints passes stack hints.",
            prompt = Prompt(system = RUN_INPUT_SECTION, user = PLAN_USER),
            config = mapOf("phase" to "PLAN"),
            consumes = listOf("spec"), produces = listOf("plan"), tags = listOf(TAG_SPEC_KIT),
            runInputs = listOf(
                INPUT_MATERIAL,
                RunInputField(
                    "techHints", "Technical hints", RunInputType.MULTILINE,
                    help = "Stack, libraries or constraints the plan should respect (optional).",
                ),
            ),
        )),
        template(now, TaskTemplate.Content(
            key = SPEC_KIT_TASKS, name = "Spec Kit: tasks", category = TemplateCategory.PLANNING,
            requiresSkills = listOf(SPEC_WRITING),
            description = "Runs the Spec Kit TASKS phase: an ordered, dependency-aware task list from the plan Spec Kit reads from the repository.",
            prompt = Prompt(system = RUN_INPUT_SECTION, user = TASKS_USER),
            config = mapOf("phase" to "TASKS"),
            consumes = listOf("plan"), produces = listOf("tasks"), tags = listOf(TAG_SPEC_KIT),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = IMPLEMENT_OPENCODE, name = "Implement", category = TemplateCategory.IMPLEMENTATION,
            requiresSkills = listOf(BUILD_GUARDRAILS),
            description = "Implements the task list (or the issue) in a runner job with the step's coding agent (OpenCode when the step " +
                "names none); on a rework it gets the latest review findings. " +
                "The runner runs verifyCommand when set (none by default), commits, pushes a feature branch and opens a merge request.",
            prompt = Prompt(system = RUN_INPUT_SECTION, user = IMPLEMENT_USER),
            config = IMPLEMENT_CONFIG,
            produces = listOf("diff", "mr"), tags = listOf("implement", "opencode"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = IMPLEMENT_CLAUDE_CODE, name = "Implement with Claude Code", extendsKey = IMPLEMENT_OPENCODE,
            description = "Deprecated (ADR 0054, 0056): use implement-opencode, implement-task-list or agent-task and choose the agent " +
                "Claude Code on the step. Same as implement-opencode (ADR 0061: the step names the agent).",
            tags = listOf("claude-code", DEPRECATED_TAG),
        )),
        template(now, TaskTemplate.Content(
            key = CODE_REVIEW, name = "Code review", category = TemplateCategory.REVIEW,
            requiresSkills = listOf(SECURITY_REVIEW_CHECKLIST),
            description = "Strict review of a diff: findings with severity, a verdict and a 0..100 score. Uses the issue, or in a CI run the merge request " +
                "title and description, as context. The diff comes last, so a cut at maxInputChars never removes the instructions.",
            prompt = Prompt(system = withRunInput(REVIEW_SYSTEM), user = REVIEW_USER),
            outputSchema = REVIEW_SCHEMA,
            consumes = listOf("diff"), produces = listOf("review"), tags = listOf("review"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = ANALYSE_ISSUE, name = "Analyse issue", category = TemplateCategory.ANALYSIS,
            description = "Deprecated (ADR 0054): use impact-analysis. Reads the issue and produces a structured analysis (stored as JSON): " +
                "scope, affected areas, risks and open questions.",
            prompt = Prompt(system = withRunInput(ANALYSE_SYSTEM), user = ANALYSE_USER),
            outputSchema = ANALYSE_SCHEMA,
            consumes = listOf("issue"), produces = listOf("analysis"), tags = listOf("analysis", DEPRECATED_TAG),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = LLM_TASK, name = "LLM task", category = TemplateCategory.GENERAL,
            description = "Generic LLM work without a repository (ADR 0034): write the task into the parameter task in the designer; " +
                "the run input material is what it works on and revision comments come back as feedback (both in the system prompt). " +
                "Produces the result artefact.",
            prompt = Prompt(system = LLM_TASK_SYSTEM, user = TASK_PARAMETER_PROMPT),
            produces = listOf("result"), tags = listOf("llm", "generic"),
            runInputs = listOf(INPUT_MATERIAL),
            parameters = listOf(taskParameter(LLM_TASK_USER)),
        )),
        template(now, TaskTemplate.Content(
            key = AGENT_TASK, name = "Agent task", category = TemplateCategory.GENERAL,
            description = "Generic coding-agent work in the project's repository: write the task into the parameter task in the designer. " +
                "The issue, the run input material and revision comments are in the system prompt. Choose a coding agent on the step, " +
                "no push by default: set push to true for a branch and a merge request. In a project without repositories it runs in an empty " +
                "workspace and its final answer becomes the result artefact.",
            prompt = Prompt(system = AGENT_TASK_SYSTEM, user = TASK_PARAMETER_PROMPT),
            config = IMPLEMENT_CONFIG + mapOf("push" to false, "codingAgent" to true, "branchTemplate" to "agent/{{issue.key | lower}}", "repositoryOptional" to true),
            produces = listOf("result"), tags = listOf("agent", "generic"),
            runInputs = listOf(INPUT_MATERIAL),
            parameters = listOf(taskParameter(AGENT_TASK_USER)),
        )),
        template(now, TaskTemplate.Content(
            key = PREPARE_WORKSPACE, name = "Prepare workspace", category = TemplateCategory.OPERATIONS,
            description = "Rarely needed (ADR 0060): the run directory is prepared automatically before the first coding step (the " +
                "workflow's setup command). This step clones its repositories into the run directory and runs the workflow setup and " +
                "the step's setupCommand at a chosen point. Starts no agent and never pushes.",
            prompt = Prompt(user = PREPARE_WORKSPACE_USER),
            config = mapOf(
                "prepareOnly" to true, "push" to false, "mergeRequest" to false,
                "repositoryOptional" to true, "setupCommand" to null,
            ),
            tags = listOf("workspace"),
        )),
    ) + sdlcTemplates(now)

    /** Templates of the delivery conventions adopted in ADR 0019. */
    fun sdlcTemplates(now: Instant): List<TaskTemplate> = listOf(
        template(now, TaskTemplate.Content(
            key = WRITE_USER_STORY, name = "Write user story", category = TemplateCategory.SPECIFICATION,
            requiresSkills = listOf(USER_STORY_FORMAT),
            description = "Turns the issue into a user story with falsifiable acceptance criteria, explicit open questions and a confidence score.",
            prompt = Prompt(system = withRunInput(WRITE_US_SYSTEM), user = WRITE_US_USER),
            outputSchema = WRITE_US_SCHEMA, confidenceField = "confidence",
            consumes = listOf("issue"), produces = listOf(ARTIFACT_USER_STORY, "confidence"), tags = listOf("sdlc", "requirements"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = WRITE_USE_CASE, name = "Write use case", category = TemplateCategory.SPECIFICATION,
            requiresSkills = listOf(USE_CASE_FORMAT),
            description = "Expands the user story into a use case: actors, triggers, main and alternative flows, business rules, postconditions and permissions.",
            prompt = Prompt(system = withRunInput(WRITE_UC_SYSTEM), user = WRITE_UC_USER),
            outputSchema = WRITE_UC_SCHEMA, confidenceField = "confidence",
            consumes = listOf(ARTIFACT_USER_STORY), produces = listOf(ARTIFACT_USE_CASE, "confidence"), tags = listOf("sdlc", "analysis"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = WRITE_ADR, name = "Write ADR", category = TemplateCategory.PLANNING,
            requiresSkills = listOf(ADR_FORMAT),
            description = "Drafts an Architecture Decision Record with at least two options, a decision, its rationale and honest consequences " +
                "(status Proposed; number and date stay placeholders for people to fill in).",
            prompt = Prompt(system = withRunInput(WRITE_ADR_SYSTEM), user = WRITE_ADR_USER),
            outputSchema = WRITE_ADR_SCHEMA, confidenceField = "confidence",
            consumes = listOf("issue"), produces = listOf("adr", "confidence"), tags = listOf("sdlc", "architecture"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = IMPACT_ANALYSIS, name = "Change request impact analysis", category = TemplateCategory.ANALYSIS,
            requiresSkills = listOf(USER_STORY_FORMAT),
            description = "Analyses a change request: description, business justification, impact on artefacts and components, new stories, effect on the " +
                "specification, estimate, options and a recommendation (stored as JSON; plan-task-list reads it).",
            prompt = Prompt(system = withRunInput(IMPACT_SYSTEM), user = IMPACT_USER),
            outputSchema = IMPACT_SCHEMA, confidenceField = "confidence",
            consumes = listOf("issue"), produces = listOf(ARTIFACT_IMPACT_ANALYSIS, "confidence"), tags = listOf("sdlc", "change-request"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = PLAN_TASK_LIST, name = "Plan task list", category = TemplateCategory.PLANNING,
            requiresSkills = listOf(BUILD_GUARDRAILS, DEFINITION_OF_READY_DONE),
            description = "Breaks the work (plan or approved impact analysis, user story) into an ordered task list (title, description, verification, " +
                "test_command, completed) that ends with the mandatory \"$FINAL_TASK_TITLE\" task.",
            prompt = Prompt(system = withRunInput(PLAN_TASKS_SYSTEM), user = PLAN_TASKS_USER),
            outputSchema = PLAN_TASKS_SCHEMA,
            consumes = listOf("plan", "issue", ARTIFACT_IMPACT_ANALYSIS, ARTIFACT_USER_STORY), produces = listOf("tasks"), tags = listOf("sdlc", "planning"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
        template(now, TaskTemplate.Content(
            key = IMPLEMENT_TASK_LIST, name = "Implement task list", extendsKey = IMPLEMENT_OPENCODE,
            description = "Executes the task list in order with TDD, runs every test_command, stops on the first red verification (the work log's RESULT " +
                "line is the verifyCommand, so a stop fails the step), keeps a work log and lists what it noticed out of scope without fixing it.",
            prompt = Prompt(user = IMPLEMENT_TASKS_USER, mode = PromptMode.REPLACE),
            config = mapOf("collectArtifacts" to listOf(WORK_LOG_FILE), "verifyCommand" to WORK_LOG_VERIFY_COMMAND),
            consumes = listOf("tasks"), produces = listOf(ARTIFACT_WORK_LOG), tags = listOf("sdlc", "implement"),
        )),
        template(now, TaskTemplate.Content(
            key = REVIEW_AGAINST_SPEC, name = "Review against specification", category = TemplateCategory.REVIEW,
            requiresSkills = listOf(SECURITY_REVIEW_CHECKLIST, DEFINITION_OF_READY_DONE),
            description = "Reviews the diff against the user story, specification and task list: a requirement coverage matrix (kept in the review; " +
                "a MISSING requirement requests changes), scope creep and defects, with a verdict and a score.",
            prompt = Prompt(system = withRunInput(REVIEW_SPEC_SYSTEM), user = REVIEW_SPEC_USER),
            outputSchema = REVIEW_SPEC_SCHEMA,
            consumes = listOf("diff", ARTIFACT_USER_STORY, "tasks"), produces = listOf("review"), tags = listOf("sdlc", "review"),
            runInputs = listOf(INPUT_MATERIAL),
        )),
    )

    /** File the `implement-task-list` agent keeps its work log in; collected (and named `work-log`) after the job, never committed. */
    const val WORK_LOG_FILE = "work-log.md"

    /** Line the work log ends with when every task, including the final full test run, passed. */
    const val WORK_LOG_GREEN = "RESULT: GREEN"

    /**
     * ADR 0052: every check the sandbox can run passed, the rest (tests that need Docker, the real pipeline) is left to
     * CI. A workflow follows it with a pipeline watch, so the step succeeds.
     */
    const val WORK_LOG_PARTIAL = "RESULT: PARTIAL"

    /** verifyCommand of `implement-task-list`: the step fails unless the work log ends with [WORK_LOG_GREEN] or [WORK_LOG_PARTIAL]. */
    const val WORK_LOG_VERIFY_COMMAND = "tail -n 3 $WORK_LOG_FILE | grep -qE \"^($WORK_LOG_GREEN|$WORK_LOG_PARTIAL)\""

    private fun template(now: Instant, content: TaskTemplate.Content) = TaskTemplate.create(content, now, builtIn = true, actor = Actor.SYSTEM)

    /** ADR 0058: the user prompt of `llm-task` and `agent-task`: the step's task. */
    const val TASK_PARAMETER_PROMPT = "{{params.$TASK_PARAMETER}}"

    /** ADR 0058: the parameter `task` of `llm-task` and `agent-task`, with the former placeholder task as its default. */
    private fun taskParameter(default: String) = TemplateParameter(
        TASK_PARAMETER, "Task", TemplateParameterType.MULTILINE, required = false, default = default,
        help = "What the agent should do in this step; the run input material and the feedback are added to its context.",
    )

    /** [system] followed by the "Run input" section (the run input material the template asks for, ADR 0053). */
    private fun withRunInput(system: String): String = system.trimEnd() + "\n" + RUN_INPUT_SECTION

    /** Runner config of the implementers; the keys are the ones `RunnerTaskSupport` reads. */
    val IMPLEMENT_CONFIG: Map<String, Any?> = mapOf(
        "verifyCommand" to null,
        "branchTemplate" to "feature/{{issue.key | lower}}",
        "push" to true,
        "mergeRequest" to true,
        "mergeRequestTitleTemplate" to "{{issue.key}}: {{issue.summary}}",
        "mergeRequestDraft" to false,
    )

    // ----- produced names -----------------------------------------------------------------------------

    /**
     * LLM templates whose output schema has no field of the produced artefact's name on purpose: the executor stores
     * the whole JSON answer under that name (the structured analysis is the artefact).
     */
    val WHOLE_ANSWER_ARTIFACTS: Map<String, String> = mapOf(ANALYSE_ISSUE to "analysis", IMPACT_ANALYSIS to ARTIFACT_IMPACT_ANALYSIS)

    /** Names a coding agent job produces itself (runner artefacts), whatever the template declares. */
    private val AGENT_JOB_OUTPUTS = setOf("diff", "mr", "result")

    /** Names a Spec Kit job (coding agent with a `phase`) produces itself. */
    private val SPEC_KIT_OUTPUTS = setOf("spec", "plan", "tasks", "analysis", "diff", "mr")

    /** State names an LLM step may produce when its schema has the field (`confidence` through the confidence field). */
    private val LLM_STATE_NAMES = setOf("confidence")

    /**
     * Why a resolved template's `produces` names would not appear at run time: every name must be a top-level field of
     * the output schema, the confidence field, `review` of a reviewer, a declared whole-answer artefact, an artefact the
     * runner collects (`collectArtifacts`, named like the file without its extension) or an output of the coding agent job
     * (ADR 0056: what runs follows from the [agent] and the Spec Kit `phase`).
     * Empty when every name is backed. Used by the seed tests so a renamed field cannot silently drop an artefact.
     */
    fun producedNameProblems(
        key: String,
        agent: AgentSnapshot?,
        produces: List<String>,
        outputSchema: String?,
        confidenceField: String?,
        config: Map<String, Any?>,
    ): List<String> {
        if (agent == null) return emptyList()
        val schemaFields = schemaProperties(outputSchema)
        val collected = collectedNames(config)
        val specKit = (config["phase"] as? String)?.isNotBlank() == true
        val jobOutputs = if (specKit) SPEC_KIT_OUTPUTS else AGENT_JOB_OUTPUTS
        val withoutSchema = produces.filterNot { it == "review" || it in LLM_STATE_NAMES }
        return produces.mapNotNull { name ->
            val backed = when {
                agent.modelAnswer -> when {
                    name == "review" -> true
                    name in LLM_STATE_NAMES -> name == confidenceField || name in schemaFields
                    outputSchema.isNullOrBlank() -> withoutSchema.size == 1
                    else -> name in schemaFields || WHOLE_ANSWER_ARTIFACTS[key] == name
                }
                // ADR 0057: a coding agent with an output schema answers in structured-answer.json, backed like a model answer
                else -> name in jobOutputs || name in collected || name in schemaFields || (name in LLM_STATE_NAMES && name == confidenceField)
            }
            if (backed) null else "$key: produces \"$name\", but neither its output schema, its collectArtifacts nor its agent (${agent.key}) provides it"
        }
    }

    /** Names of the collected files: `work-log.md` -> `work-log`; glob patterns name nothing in advance. */
    private fun collectedNames(config: Map<String, Any?>): Set<String> {
        val raw = config["collectArtifacts"]
        val patterns = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(",").map { it.trim() }
            else -> emptyList()
        }
        return patterns.filterNot { '*' in it || '?' in it }.map { it.substringAfterLast('/').substringBeforeLast('.') }.toSet()
    }

    /** Top-level property names of a JSON schema (the keys of the root object's `properties`); empty for no schema. */
    fun schemaProperties(schema: String?): Set<String> =
        if (schema.isNullOrBlank()) emptySet() else SchemaKeyScanner(schema).topLevelProperties()

    /** Single pass over a JSON schema that collects the keys of the root object's `properties` object (no JSON library in core). */
    private class SchemaKeyScanner(private val s: String) {
        private val names = LinkedHashSet<String>()
        private var depth = 0
        private var lastRootKey: String? = null
        private var propertiesDepth = NOT_OPENED

        fun topLevelProperties(): Set<String> {
            var i = 0
            while (i < s.length) i = step(i)
            return names
        }

        private fun step(i: Int): Int {
            when (s[i]) {
                '"' -> return string(i)
                '{', '[' -> open(s[i])
                '}', ']' -> close()
            }
            return i + 1
        }

        /** A string at [start]: a key when a colon follows. Returns the index after its closing quote. */
        private fun string(start: Int): Int {
            val end = stringEnd(start)
            if (end >= s.length) return s.length // unterminated string: malformed schema, nothing more to read
            if (colonFollows(end)) onKey(s.substring(start + 1, end))
            return end + 1
        }

        private fun onKey(key: String) {
            if (depth == 1) lastRootKey = key
            if (depth == propertiesDepth) names += key
        }

        private fun open(bracket: Char) {
            depth++
            val opensRootProperties = bracket == '{' && depth == 2 && lastRootKey == "properties"
            if (opensRootProperties && propertiesDepth == NOT_OPENED) propertiesDepth = 2
        }

        private fun close() {
            if (depth == propertiesDepth) propertiesDepth = CLOSED
            depth--
        }

        private fun colonFollows(end: Int): Boolean {
            var j = end + 1
            while (j < s.length && s[j].isWhitespace()) j++
            return j < s.length && s[j] == ':'
        }

        private fun stringEnd(start: Int): Int {
            var i = start + 1
            while (i < s.length && s[i] != '"') i += if (s[i] == '\\') 2 else 1
            return i
        }

        private companion object {
            const val NOT_OPENED = -1
            const val CLOSED = -2
        }
    }

    // ----- shared prompt sections ---------------------------------------------------------------------

    /** The issue, or nothing when the run has none (no bare ": " line). */
    val ISSUE_SECTION = """
        {{#issue.key}}
        ## Issue
        {{issue.key}}: {{issue.summary}}
        {{#issue.description}}

        {{issue.description}}
        {{/issue.description}}
        {{#issue.acceptanceCriteria}}

        Acceptance criteria:
        {{issue.acceptanceCriteria}}
        {{/issue.acceptanceCriteria}}
        {{/issue.key}}
    """.trimIndent()

    /** Revision comments given for this step (never those of other steps); nothing on the first attempt. */
    val FEEDBACK_SECTION = """
        {{#feedback}}
        ## Feedback on the previous attempt of this step (address every point)
        {{feedback}}
        {{/feedback}}
    """.trimIndent()

    /** The latest review (findings, coverage, verdict) when the run has one: what a rework must fix. */
    val REVIEW_SECTION = """
        {{#artifacts.review}}
        ## Latest review findings
        {{artifacts.review}}
        {{/artifacts.review}}
    """.trimIndent()

    // ----- run inputs (ADR 0053) -----------------------------------------------------------------------

    /** The free-text box of the start dialog: material an agent step works on. */
    val INPUT_MATERIAL = RunInputField(
        "input", "Input material", RunInputType.MULTILINE,
        help = "Text the agent steps should read: a request, notes, a pasted document (optional).",
    )

    /** The run's Jira issue key, read by the Jira actions (ADR 0056: their run input); mapped to the run's issue key. */
    val ISSUE_KEY_INPUT = RunInputField("issueKey", "Jira issue key", RunInputType.ISSUE_KEY, required = true, help = "For example ABC-123.")

    // ----- prompts ------------------------------------------------------------------------------------

    /**
     * ADR 0053: the run input material the person who started the run entered, as a section of a template's system
     * prompt (nothing when no value was entered). ADR 0058: templates that ask for `input` carry it themselves.
     */
    val RUN_INPUT_SECTION = """
        {{#runInputText}}

        ## Run input
        What the person who started the run entered for this step:

        {{runInputText}}
        {{/runInputText}}
    """.trimIndent()

    /** Material and reviewer feedback live in the system prompt, so the task (parameter or replaced prompt) keeps them. */
    val LLM_TASK_SYSTEM = """
        You are a careful assistant working for the owner of this project inside Back Office. A person reviews
        your result before it is used. Be accurate and concise; when something is unknown, say so instead of
        guessing. The task is the user message.
        {{#runInputText}}

        ## Run input
        {{runInputText}}
        {{/runInputText}}
        {{#feedback}}

        ## Feedback of the reviewer on your previous result
        {{feedback}}
        {{/feedback}}
    """.trimIndent()

    val AGENT_TASK_SYSTEM = """
        You are a coding agent working for the owner of this project inside Back Office, in a sandbox with a
        clone of the project's repository (or an empty workspace when the project has none). A person reviews
        what you produce before it is used. The task is the user message: do exactly that, in this repository,
        and finish with a short summary of what you did and found. Change files only when the task asks for
        changes; when something is unknown, say so instead of guessing. Do not commit or push: when this step
        pushes, the runner commits and pushes your working tree. Follow your rules.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n" + """
        {{#runInputText}}

        ## Run input
        {{runInputText}}
        {{/runInputText}}
        {{#feedback}}

        ## Feedback of the reviewer on your previous result
        {{feedback}}
        {{/feedback}}
    """.trimIndent()

    /** Never sent to an agent (prepareOnly); the runner derives nothing from it. */
    val PREPARE_WORKSPACE_USER = "Prepare the workspace of this run."

    /** The default task of agent-task (parameter `task`): harmless (read-only) until a designer sets another. */
    val AGENT_TASK_USER = """
        Describe the repository: its structure, how to build and test it, and anything that looks unfinished
        (without a repository: say so in one sentence).
    """.trimIndent()

    /** The default task of llm-task (parameter `task`); designers set their own per step. */
    val LLM_TASK_USER = """
        Summarise the input material in at most five bullet points and list open questions.
    """.trimIndent()

    /**
     * Starts with the subject line and the issue text (the runner job's feature description and commit subject are
     * taken from them), then the instructions; feedback last.
     */
    val SPECIFY_USER = """
        {{issue.key}}: {{issue.summary}}
        {{#issue.description}}

        {{issue.description}}
        {{/issue.description}}
        {{#issue.acceptanceCriteria}}

        Acceptance criteria:
        {{issue.acceptanceCriteria}}
        {{/issue.acceptanceCriteria}}

        ## Instructions
        Phase SPECIFY of Spec Kit: write the feature specification for the issue above with the specify
        command. The specification describes what and why, never how: user scenarios, functional
        requirements, acceptance scenarios and open questions marked `[NEEDS CLARIFICATION: <question>]`.
        Do not design the solution and do not write code. Apply the skill $SPEC_WRITING and follow your rules.

    """.trimIndent() + "\n" + FEEDBACK_SECTION

    /** Spec Kit reads the specification from the repository: only feedback and optional tech hints travel in the prompt. */
    val PLAN_USER = """
        {{issue.key}}: technical plan

        Phase PLAN of Spec Kit: create the implementation plan (architecture decisions, data model, API
        contracts, verification strategy) with the plan command. Spec Kit reads the specification of this
        feature from the repository; do not ask for it to be pasted. Respect the project constitution; when
        it conflicts with the specification, follow the constitution and list the conflict as an open
        question in the plan. Follow your rules.
        Technical hints from the owner, when given, are in the run input of the system prompt.

    """.trimIndent() + "\n" + FEEDBACK_SECTION

    val TASKS_USER = """
        {{issue.key}}: task list

        Phase TASKS of Spec Kit: break the plan of this feature, which Spec Kit reads from the repository,
        into an ordered task list with the tasks command. Each task is small enough to verify on its own,
        names the files it touches and the test that proves it, and marks tasks that can run in parallel.
        Follow your rules.

    """.trimIndent() + "\n" + FEEDBACK_SECTION

    val IMPLEMENT_USER = """
        {{#issue.key}}Implement {{issue.key}}: {{issue.summary}}{{/issue.key}}{{^issue.key}}Implement the requested change{{/issue.key}}

        How to work:
        1. Work through the task list below in order when there is one; otherwise make the smallest set of
           changes that implements the issue.
        2. When the latest review findings are given below, fix every BLOCKER and MAJOR finding and every
           MISSING requirement, and answer the other findings in your summary (fixed, or why not).
        3. Verify before you finish: run the repository's own build and tests (see its README or build
           files) and fix what fails. The runner then runs this step's `verifyCommand` when the owner set
           one (the built-in default sets none) and fails the step when it exits non-zero.
        4. Do not commit or push: the runner commits your working tree and pushes the branch.
        5. Follow your rules.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + """
        {{#artifacts.tasks}}
        ## Task list
        {{artifacts.tasks}}
        {{/artifacts.tasks}}

    """.trimIndent() + "\n" + REVIEW_SECTION + "\n\n" + FEEDBACK_SECTION

    val CLARIFY_SYSTEM = """
        You now act as the implementer who will receive this specification next. Your job is to find
        out whether it can be implemented without guessing. Read it critically: ambiguous behaviour,
        missing acceptance criteria, undefined terms, conflicting requirements, unstated non-functional
        needs and anything an implementer would have to decide alone.

        Confidence is a number from 0 to 1: 1.0 means every decision is settled by the text; 0.5 means
        an implementer would make several product decisions on their own; below 0.3 means the spec is
        not actionable. Ask only questions whose answer changes the implementation, at most ten, most
        important first, each with a suggested default answer the owner can accept as it is.
    """.trimIndent()

    val CLARIFY_USER = """
        Read the specification below as its implementer and return only the JSON described by the schema:
        `confidence`, a one-paragraph `summary` and `clarifications`, a markdown numbered list (most
        important first) in which every item gives the question, why it matters and a suggested default
        answer. Write "No open questions." when nothing blocks the implementation.

        ## Specification
        {{artifacts.spec}}
    """.trimIndent()

    val CLARIFY_SCHEMA = """
        {
          "type": "object",
          "required": ["confidence", "clarifications", "summary"],
          "properties": {
            "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
            "clarifications": { "type": "string" },
            "summary": { "type": "string" }
          }
        }
    """.trimIndent()

    val REVIEW_SYSTEM = """
        You are a strict senior reviewer. You review the diff only, in the context of the project
        constitution and the specification when given. You are looking for defects, not for things to
        say: an empty findings list with a short summary is a valid and welcome result.

        Severity definitions:
        - BLOCKER: data loss, security vulnerability, broken build or tests, violates the constitution.
        - MAJOR: incorrect behaviour in a realistic scenario, missing error handling on a main path,
          a public contract changed without the callers.
        - MINOR: correct but fragile: unclear naming, duplicated logic, missing test for a new branch.
        - NIT: style and wording. Report only when the constitution or the project conventions define
          the rule; never argue taste.

        Rules:
        1. Every finding points at a file and, when possible, a line from the diff, and says what to
           change. No findings about code outside the diff unless the diff breaks it.
        2. Do not repeat the same finding for every occurrence; report it once and say "also in ...".
        3. Do not request changes for missing features the issue did not ask for.
        4. Score is 0..100: start from 100, subtract 40 per BLOCKER, 15 per MAJOR, 5 per MINOR, 1 per NIT,
           never below 0. Verdict: REQUEST_CHANGES with any BLOCKER or more than two MAJORs, APPROVE
           when the score is at least 80, otherwise COMMENT.
    """.trimIndent()

    /** Merge request context of a CI-triggered run (no issue there). */
    val MERGE_REQUEST_SECTION = """
        {{#input.ci.mergeRequest.title}}
        ## Merge request
        {{input.ci.mergeRequest.title}}
        {{#input.ci.mergeRequest.description}}

        {{input.ci.mergeRequest.description}}
        {{/input.ci.mergeRequest.description}}
        {{/input.ci.mergeRequest.title}}
    """.trimIndent()

    /** Instructions first and the diff last: a prompt cut at maxInputChars loses diff lines, never the instructions. */
    val REVIEW_USER = """
        Review the diff at the end of this message with the rules of the system prompt and the security
        checklist, and return only the JSON described by the schema. The context comes first and the diff
        last; when the diff ends with "[truncated]", review the part you see and say in the summary that
        the diff was cut.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n" + MERGE_REQUEST_SECTION + "\n" + """
        {{#artifacts.spec}}
        ## Specification
        {{artifacts.spec}}
        {{/artifacts.spec}}

        ## Diff to review
        {{artifacts.diff}}
    """.trimIndent()

    val REVIEW_SCHEMA = """
        {
          "type": "object",
          "required": ["summary", "findings", "verdict", "score"],
          "properties": {
            "summary": { "type": "string" },
            "findings": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["severity", "file", "title", "detail"],
                "properties": {
                  "severity": { "type": "string", "enum": ["BLOCKER", "MAJOR", "MINOR", "NIT"] },
                  "file": { "type": "string" },
                  "line": { "type": ["integer", "null"] },
                  "title": { "type": "string" },
                  "detail": { "type": "string" },
                  "suggestion": { "type": ["string", "null"] }
                }
              }
            },
            "verdict": { "type": "string", "enum": ["APPROVE", "COMMENT", "REQUEST_CHANGES"] },
            "score": { "type": "integer", "minimum": 0, "maximum": 100 }
          }
        }
    """.trimIndent()

    val ANALYSE_SYSTEM = """
        You analyse an issue before any work starts so the owner can decide whether to run the pipeline
        on it. Be concrete: name the modules and files that will likely change, the interfaces that will
        be touched, and the risks that would make you stop and ask. Estimate size on a T-shirt scale
        (XS, S, M, L, XL) and say what drives the estimate.
    """.trimIndent()

    val ANALYSE_USER = """
        Analyse the issue below and return only the JSON described by the schema. When there is no issue,
        say so in the summary and leave the lists empty.

    """.trimIndent() + "\n" + ISSUE_SECTION

    val ANALYSE_SCHEMA = """
        {
          "type": "object",
          "required": ["summary", "scope", "risks", "questions", "size"],
          "properties": {
            "summary": { "type": "string" },
            "scope": { "type": "array", "items": { "type": "string" } },
            "risks": { "type": "array", "items": { "type": "string" } },
            "questions": { "type": "array", "items": { "type": "string" } },
            "size": { "type": "string", "enum": ["XS", "S", "M", "L", "XL"] }
          }
        }
    """.trimIndent()

    // ----- SDLC prompts (ADR 0019) ---------------------------------------------------------------------

    private const val OPEN_QUESTIONS_SCHEMA = """
            "openQuestions": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["question", "whyItMatters"],
                "properties": {
                  "question": { "type": "string" },
                  "owner": { "type": "string", "description": "Role or person that can answer; only when the input names one, else empty." },
                  "whyItMatters": { "type": "string" }
                }
              }
            }"""

    val WRITE_US_SYSTEM = """
        You are a business analyst writing one user story for the issue below. The story is a contract:
        a tester must be able to accept or reject the implementation from the acceptance criteria alone.

        Work in this order:
        1. Identify the specific role, the goal and the business benefit. If the issue covers more than
           one goal, write the story for the first one and list the others under open questions as
           proposed separate stories.
        2. Write the story in the user-story-format skill: story sentence, functional description,
           numbered acceptance criteria under "System:", constraints and assumptions, open questions.
           Keep the ID placeholder `US-<AREA>-<NN>` unless the issue gives the ID.
        3. Write criteria for both sides: what the system allows and what it requires, validates or
           blocks. Every criterion must be falsifiable by a counter-example.
        4. Check the Definition of Ready for a user story item by item before you answer.

        Never invent a business rule the issue does not state. Put every gap into openQuestions (name who
        can answer only when the issue says so) and mark it `[?]` in the story. Confidence (0..1) is 1.0
        only when no open question changes the scope; lower it by at least 0.1 for each such question.
    """.trimIndent()

    val WRITE_US_USER = """
        Write the user story for the issue below and return only the JSON described by the schema;
        `$ARTIFACT_USER_STORY` is the complete markdown document.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + FEEDBACK_SECTION

    val WRITE_US_SCHEMA = """
        {
          "type": "object",
          "required": ["$ARTIFACT_USER_STORY", "openQuestions", "confidence", "summary"],
          "properties": {
            "$ARTIFACT_USER_STORY": { "type": "string" },$OPEN_QUESTIONS_SCHEMA,
            "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
            "summary": { "type": "string" }
          }
        }
    """.trimIndent()

    val WRITE_UC_SYSTEM = """
        You are a business analyst expanding an accepted user story into a use case. The use case
        describes what the actors do and see, step by step; it adds flows, never scope, and it does not
        repeat the acceptance criteria.

        Follow the use-case-format skill: metadata with the linked story, actors, triggers and
        preconditions, main flow A, alternative flows A1, A2, ... with titles and self-contained steps,
        business rules RB-01..., postconditions and permissions stated positively. External systems
        appear as actors; their mechanisms (protocols, retries, tables) do not appear at all. Keep the ID
        placeholder `UC-<AREA>-<NN>` unless the story gives the ID.

        Before answering, check that main flow + alternatives + rules let a tester derive a test for
        every acceptance criterion of the story. A criterion you cannot cover is an open question, not
        a reason to extend the scope. Confidence (0..1) reflects how many behaviours you had to leave
        as `[?]`.
    """.trimIndent()

    val WRITE_UC_USER = """
        Expand the user story below into a use case and return only the JSON described by the schema;
        `$ARTIFACT_USE_CASE` is the complete markdown document.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + """
        ## User story
        {{artifacts.user-story}}

    """.trimIndent() + "\n" + FEEDBACK_SECTION

    val WRITE_UC_SCHEMA = """
        {
          "type": "object",
          "required": ["$ARTIFACT_USE_CASE", "openQuestions", "confidence", "summary"],
          "properties": {
            "$ARTIFACT_USE_CASE": { "type": "string" },$OPEN_QUESTIONS_SCHEMA,
            "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
            "summary": { "type": "string" }
          }
        }
    """.trimIndent()

    val WRITE_ADR_SYSTEM = """
        You are the architect drafting an Architecture Decision Record for the decision the issue asks
        for. Follow the adr-format skill: title with the number left as `NNNN`, status Proposed with the
        date left as `YYYY-MM-DD` (people assign both), context without the decision, at least two options
        (A, B, ...) with pros and cons each, the decision, the rationale by weighted criteria, positive and
        negative consequences and related ADRs.

        Ground every statement in the project constitution, the issue and the analysis when given. An
        option you cannot evaluate for lack of facts stays in the record with the missing facts listed
        as open questions. Never mark the ADR Accepted and never change any status: acceptance belongs to
        people.
    """.trimIndent()

    val WRITE_ADR_USER = """
        Draft the ADR for the issue below and return only the JSON described by the schema; `adr` is the
        complete markdown document.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + """
        {{#artifacts.analysis}}
        ## Analysis
        {{artifacts.analysis}}
        {{/artifacts.analysis}}

    """.trimIndent() + "\n" + FEEDBACK_SECTION

    val WRITE_ADR_SCHEMA = """
        {
          "type": "object",
          "required": ["adr", "options", "decision", "openQuestions", "confidence"],
          "properties": {
            "adr": { "type": "string" },
            "options": { "type": "array", "items": { "type": "string" } },
            "decision": { "type": "string" },$OPEN_QUESTIONS_SCHEMA,
            "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
          }
        }
    """.trimIndent()

    val IMPACT_SYSTEM = """
        You analyse a change request (CR) before anyone approves it. A CR is a change of agreed scope,
        not a bug. Your analysis lets the owner decide approve, reject or defer.

        Cover, in this order:
        1. Description: what the requester wants changed, in their language, two to five sentences.
        2. Business justification: why it is needed and what happens if it is not done.
        3. Impact on artefacts: every user story, use case, ADR or design section that changes, with
           the kind of change (modify criteria, add alternative flow, new endpoint, new column).
        4. Impact on components: backend, frontend and infrastructure parts that change; "none" is a
           valid answer and must be stated explicitly.
        5. New user stories or use cases the change needs, as one-line "As a ..." sentences.
        6. Effect on the specification: none, an annex, or a new version.
        7. Estimate in person-days for analysis, backend, frontend and tests, with what drives it.
        8. Options (at least one, usually two) with pros, cons and cost, and your recommendation.

        Name only artefacts and components you can see in the issue, the constitution or the
        repository description; everything else is an open question. Confidence (0..1) is how complete
        the impact list is.
    """.trimIndent()

    val IMPACT_USER = """
        Analyse the change request below and return only the JSON described by the schema.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + FEEDBACK_SECTION

    val IMPACT_SCHEMA = """
        {
          "type": "object",
          "required": ["description", "businessJustification", "impact", "estimate", "options", "recommendation", "openQuestions", "confidence"],
          "properties": {
            "description": { "type": "string" },
            "businessJustification": { "type": "string" },
            "impact": {
              "type": "object",
              "required": ["artefacts", "components", "newStories", "specification"],
              "properties": {
                "artefacts": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["artefact", "change"],
                    "properties": { "artefact": { "type": "string" }, "change": { "type": "string" } }
                  }
                },
                "components": { "type": "array", "items": { "type": "string" } },
                "newStories": { "type": "array", "items": { "type": "string" } },
                "specification": { "type": "string", "enum": ["NONE", "ANNEX", "NEW_VERSION"] }
              }
            },
            "estimate": {
              "type": "object",
              "required": ["analysisDays", "backendDays", "frontendDays", "testDays", "totalDays", "drivers"],
              "properties": {
                "analysisDays": { "type": "number", "minimum": 0 },
                "backendDays": { "type": "number", "minimum": 0 },
                "frontendDays": { "type": "number", "minimum": 0 },
                "testDays": { "type": "number", "minimum": 0 },
                "totalDays": { "type": "number", "minimum": 0 },
                "drivers": { "type": "string" }
              }
            },
            "options": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["name", "description", "pros", "cons", "cost"],
                "properties": {
                  "name": { "type": "string" },
                  "description": { "type": "string" },
                  "pros": { "type": "array", "items": { "type": "string" } },
                  "cons": { "type": "array", "items": { "type": "string" } },
                  "cost": { "type": "string" }
                }
              }
            },
            "recommendation": { "type": "string" },$OPEN_QUESTIONS_SCHEMA,
            "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
          }
        }
    """.trimIndent()

    val PLAN_TASKS_SYSTEM = """
        You turn the work below into a task list that an autonomous coding agent executes one task at
        a time, with a reviewer checking each task. Every task has:

        - `title`: short summary of the task; the work log and the review refer to it.
        - `description`: what to implement, with the files, classes or modules involved.
        - `verification`: the criteria the reviewer uses to judge the task, derived from the
          acceptance criteria, the plan or the approved impact analysis; observable and checkable.
        - `test_command`: one shell command run after the task; exit code 0 means pass. Keep it
          focused on the task (a single test class, a module build); it must at least compile the code
          when no test exists yet.
        - `completed`: always `false`.

        Rules:
        1. Order tasks so each one builds on the previous ones and leaves the build green.
        2. Tests come first: when a task adds behaviour, its description names the test to write
           before the code.
        3. One logical change per task; a task that touches unrelated areas is two tasks.
        4. The last task is always titled "$FINAL_TASK_TITLE": it reformats and lints the code and runs
           the entire test suite; its test_command runs the full suite. Set `finalTaskTitle` to exactly
           that title.
        5. Do not add tasks for work the issue, the plan and the impact analysis do not ask for.
        6. The agent works in a sandbox: no Docker daemon (Testcontainers and `docker` commands cannot
           run there), network only to package registries and the repository host. A `test_command` must
           run there; when a check needs Docker or the real CI (integration tests, image builds, the
           pipeline itself), give a sandbox-runnable command (unit tests, compile, lint) and name the
           CI-only part in `verification` as "confirmed by CI".
    """.trimIndent()

    /** The planner gets whatever the run has: the approved impact analysis (change requests), a plan, the user story. */
    val PLAN_TASKS_USER = """
        Break the work below into the task list and return only the JSON described by the schema.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + """
        {{#artifacts.impact-analysis}}
        ## Approved impact analysis
        {{artifacts.impact-analysis}}
        {{/artifacts.impact-analysis}}
        {{#artifacts.plan}}

        ## Plan
        {{artifacts.plan}}
        {{/artifacts.plan}}
        {{#artifacts.user-story}}

        ## User story
        {{artifacts.user-story}}
        {{/artifacts.user-story}}
        {{#feedback}}
        {{#artifacts.tasks}}

        ## Your previous task list (revise it: keep what the feedback does not question)
        {{artifacts.tasks}}
        {{/artifacts.tasks}}
        {{/feedback}}

    """.trimIndent() + "\n" + FEEDBACK_SECTION

    val PLAN_TASKS_SCHEMA = """
        {
          "type": "object",
          "required": ["tasks", "finalTaskTitle", "summary"],
          "properties": {
            "tasks": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["title", "description", "verification", "test_command", "completed"],
                "properties": {
                  "title": { "type": "string" },
                  "description": { "type": "string" },
                  "verification": { "type": "string" },
                  "test_command": { "type": "string" },
                  "completed": { "type": "boolean", "enum": [false] }
                }
              }
            },
            "finalTaskTitle": { "type": "string", "enum": ["$FINAL_TASK_TITLE"] },
            "summary": { "type": "string" }
          }
        }
    """.trimIndent()

    val IMPLEMENT_TASKS_USER = """
        {{#issue.key}}Implement the task list of {{issue.key}}: {{issue.summary}}{{/issue.key}}{{^issue.key}}Implement the task list{{/issue.key}}

        Execute the task list below in this repository, strictly in order, one task at a time.

        For every task:
        1. Read its description and verification criteria. Write the test that proves the task first
           and see it fail (TDD), then make the smallest change that makes it pass.
        2. Run the task's `test_command`. When it passes, record the task in the work log and continue
           with the next task. Do not commit: the runner commits the whole result once you finish.
        3. When `test_command` or the verification stays red after a reasonable fix attempt, STOP: do
           not continue with later tasks and do not disable, skip or delete tests. Record in the work log
           which task failed, the command, the tail of its output and what you tried.

        Work log: keep it in `$WORK_LOG_FILE` at the repository root; the runner collects it as the
        `$ARTIFACT_WORK_LOG` artefact and keeps it out of the commit. For every task append its title, what
        changed (files), the test_command and its result, and the decisions you made. Then add a section
        "Noticed out of scope": problems you saw outside the tasks (bugs, stale docs, risky code), with
        file paths; do NOT fix them. The last line of the work log is exactly `$WORK_LOG_GREEN` when every
        task passed, including the final "$FINAL_TASK_TITLE" task that runs the full suite;
        `$WORK_LOG_PARTIAL` when every check this sandbox can run passed and only checks that need what
        the sandbox does not have are left (it has no Docker daemon, so Testcontainers tests and image
        builds run only in CI): then list those checks under "Left to CI" with the commands CI runs; and
        `RESULT: STOPPED at <task title>` otherwise. Never use PARTIAL to hide a failure you could see
        here. This step's verifyCommand checks that line, so a STOP fails the step; after PARTIAL the
        workflow waits for the real pipeline and sends you its failures.

        When the latest review findings are given below, first fix every BLOCKER and MAJOR finding and
        every MISSING requirement (as extra tasks before the final one), then run the final task again;
        answer the other findings in the work log.

        Apply the skill $DEFINITION_OF_READY_DONE and follow your rules.

        ## Task list
        {{artifacts.tasks}}

    """.trimIndent() + "\n" + REVIEW_SECTION + "\n\n" + FEEDBACK_SECTION

    val REVIEW_SPEC_SYSTEM = """
        You review a diff against what was agreed: the user story (acceptance criteria), the
        specification and the task list. Your first question is "does the change do what was agreed,
        all of it and nothing else?", your second is "is it correct and safe?".

        1. For every acceptance criterion or verification item, add a row to `coverage` with MET,
           PARTIAL, MISSING or NOT_APPLICABLE and quote the file and line that proves it. A criterion
           without evidence in the diff is MISSING, not MET. The owner sees this matrix in the review.
        2. Report changes that no criterion or task asks for as MAJOR findings titled "Out of scope".
        3. Then review for defects with the severities of the code review: BLOCKER, MAJOR, MINOR, NIT.
           Every finding points at a file (and line when possible) and says what to change.
        4. A MISSING criterion is also a MAJOR finding titled "Missing: <criterion>"; a violated
           constitution rule or security checklist item is a BLOCKER.
        5. Score 0..100: start from 100, subtract 40 per BLOCKER, 15 per MAJOR, 5 per MINOR, 1 per NIT.
           Verdict: REQUEST_CHANGES with any BLOCKER or MISSING criterion, APPROVE when the score is at
           least 80, otherwise COMMENT. Back Office applies the same rule to your findings and coverage.
    """.trimIndent()

    val REVIEW_SPEC_USER = """
        Review the diff at the end of this message against the agreed requirements below and return only
        the JSON described by the schema. The diff comes last; when it ends with "[truncated]", review the
        part you see, mark criteria you could not check as PARTIAL and say in the summary that the diff
        was cut.

    """.trimIndent() + "\n" + ISSUE_SECTION + "\n\n" + """
        {{#artifacts.user-story}}
        ## User story
        {{artifacts.user-story}}
        {{/artifacts.user-story}}
        {{#artifacts.spec}}

        ## Specification
        {{artifacts.spec}}
        {{/artifacts.spec}}
        {{#artifacts.tasks}}

        ## Task list
        {{artifacts.tasks}}
        {{/artifacts.tasks}}

        ## Diff to review
        {{artifacts.diff}}
    """.trimIndent()

    val REVIEW_SPEC_SCHEMA = """
        {
          "type": "object",
          "required": ["summary", "coverage", "findings", "verdict", "score"],
          "properties": {
            "summary": { "type": "string" },
            "coverage": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["requirement", "status", "evidence"],
                "properties": {
                  "requirement": { "type": "string" },
                  "status": { "type": "string", "enum": ["MET", "PARTIAL", "MISSING", "NOT_APPLICABLE"] },
                  "evidence": { "type": "string" }
                }
              }
            },
            "findings": {
              "type": "array",
              "items": {
                "type": "object",
                "required": ["severity", "file", "title", "detail"],
                "properties": {
                  "severity": { "type": "string", "enum": ["BLOCKER", "MAJOR", "MINOR", "NIT"] },
                  "file": { "type": "string" },
                  "line": { "type": ["integer", "null"] },
                  "title": { "type": "string" },
                  "detail": { "type": "string" },
                  "suggestion": { "type": ["string", "null"] }
                }
              }
            },
            "verdict": { "type": "string", "enum": ["APPROVE", "COMMENT", "REQUEST_CHANGES"] },
            "score": { "type": "integer", "minimum": 0, "maximum": 100 }
          }
        }
    """.trimIndent()

    // ----- skills -------------------------------------------------------------------------------------

    fun skills(now: Instant): List<Skill> = listOf(
        skill(SPEC_WRITING, "Spec writing", SPEC_WRITING_DESCRIPTION, SPEC_WRITING_MD, listOf("spec"), now),
        skill(CONVENTIONAL_COMMITS, "Conventional commits", CONVENTIONAL_COMMITS_DESCRIPTION, CONVENTIONAL_COMMITS_MD, listOf("git"), now),
        skill(SECURITY_REVIEW_CHECKLIST, "Security review checklist", SECURITY_REVIEW_DESCRIPTION, SECURITY_REVIEW_MD, listOf("review", "security"), now),
        skill(DEFINITION_OF_READY_DONE, "Definition of Ready / Done", DEFINITION_OF_READY_DONE_DESCRIPTION, DEFINITION_OF_READY_DONE_MD, listOf("sdlc", "quality"), now),
        skill(USER_STORY_FORMAT, "User story format", USER_STORY_FORMAT_DESCRIPTION, USER_STORY_FORMAT_MD, listOf("sdlc", "requirements"), now),
        skill(USE_CASE_FORMAT, "Use case format", USE_CASE_FORMAT_DESCRIPTION, USE_CASE_FORMAT_MD, listOf("sdlc", "analysis"), now),
        skill(ADR_FORMAT, "ADR format", ADR_FORMAT_DESCRIPTION, ADR_FORMAT_MD, listOf("sdlc", "architecture"), now),
        skill(BUILD_GUARDRAILS, "Build guardrails", BUILD_GUARDRAILS_DESCRIPTION, BUILD_GUARDRAILS_MD, listOf("sdlc", "quality"), now),
    )

    private fun skill(key: String, name: String, description: String, content: String, tags: List<String>, now: Instant) =
        Skill.create(Skill.Content(key = key, name = name, description = description, content = content, tags = tags), now, builtIn = true, actor = Actor.SYSTEM)

    // ----- agents (ADR 0056, 0057) --------------------------------------------------------------------

    /**
     * ADR 0057 section 6: the built-in agents; no model, so steps use the defaults for their kind. Every one carries the
     * ground rules ([baseAgentRules]) and the skills of its role ([agentSkills]).
     */
    fun agents(now: Instant): List<Agent> = listOf(
        agent(now, AgentSnapshot.MODEL_ANSWER, "Model answer", null,
            "One model call without tools or repository: the answer (structured with an output schema) is the step's result."),
        agent(now, ANALYST, "Analyst", null,
            "Model answer agent for requirements work: user stories, use cases, ADRs, impact analyses and task lists, " +
                "with the formats and the Definition of Ready / Done as skills.",
            ANALYST_INSTRUCTIONS),
        agent(now, REVIEWER, "Reviewer", null,
            "Model answer agent that reviews changes against what was agreed, with the security checklist and the Definition of Ready / Done.",
            REVIEWER_INSTRUCTIONS),
        agent(now, AgentSnapshot.OPENCODE, "OpenCode", AgentHarness.OPENCODE,
            "OpenCode coding agent session in the runner sandbox with the step's workspace, its rules and skills."),
        agent(now, AgentSnapshot.CLAUDE_CODE, "Claude Code", AgentHarness.CLAUDE_CODE,
            "Claude Code coding agent session in the runner sandbox with the step's workspace, its rules and skills."),
    )

    private fun agent(now: Instant, key: String, name: String, harness: AgentHarness?, description: String, instructions: String = "") =
        Agent.create(
            Agent.Content(
                key = key, name = name, description = description, harness = harness, instructions = instructions,
                rules = baseAgentRules, skills = agentSkills[key].orEmpty(),
            ),
            now, builtIn = true, actor = Actor.SYSTEM,
        )

    val ANALYST_INSTRUCTIONS = """
        You are the analyst of this project. You turn requests into precise artefacts that people and other agents
        build from: user stories, use cases, ADRs, impact analyses and task lists. Every statement you write can be
        checked; every gap is an explicit open question, never a guess. Use the format skills for the structure and
        check the Definition of Ready before you answer.
    """.trimIndent()

    val REVIEWER_INSTRUCTIONS = """
        You are the reviewer of this project. You judge a change against what was agreed (issue, user story,
        specification, task list) and against the project's rules. You look for defects, not for things to say:
        every finding names a file and what to change, and an empty findings list is a valid result. Run the
        security checklist first.
    """.trimIndent()

    // ----- rules (ADR 0055) ---------------------------------------------------------------------------

    /**
     * Rules are always in the agent's context, so they carry no SKILL.md frontmatter. None is automatic: the ground
     * rules reach every step through the built-in agents (ADR 0057); the two stack rules are examples to copy into a
     * project and mark automatic there.
     */
    fun rules(now: Instant): List<Rule> = listOf(
        rule(AGENT_GROUND_RULES, "Agent ground rules", "Evidence, scope and safety rules of every agent step.", AGENT_GROUND_RULES_MD, listOf("sdlc", "base"), now),
        rule(ASK_DONT_GUESS, "Ask, don't guess", "What to do when information is missing, ambiguous or contradictory: source hierarchy, open-question markers, editing existing text.", ASK_DONT_GUESS_MD, listOf("sdlc", "base"), now),
        rule(KOTLIN_HEXAGONAL, "Kotlin hexagonal architecture", "Example for the Back Office stack: core and adapters modules with ArchUnit-enforced layers. Copy it into a project and adapt it.", KOTLIN_HEXAGONAL_MD, listOf("kotlin", "architecture", "example"), now),
        rule(ANGULAR_NG_ZORRO, "Angular with NG-ZORRO", "Example for the Back Office stack: standalone components, signals and NG-ZORRO. Copy it into a project and adapt it.", ANGULAR_NG_ZORRO_MD, listOf("angular", "frontend", "example"), now),
    )

    private fun rule(key: String, name: String, description: String, content: String, tags: List<String>, now: Instant) =
        Rule.create(Rule.Content(key = key, name = name, description = description, content = withoutFrontmatter(content), tags = tags), now, builtIn = true, actor = Actor.SYSTEM)

    /** The markdown after a leading `---` frontmatter block, or [md] unchanged. */
    fun withoutFrontmatter(md: String): String {
        val text = md.trimStart()
        if (!text.startsWith("---\n")) return md
        val end = text.indexOf("\n---\n", 4)
        return if (end < 0) md else text.substring(end + 5).trimStart('\n')
    }

    // Descriptions say when to use the skill (agents pick skills by them); no ": " so the SKILL.md frontmatter stays valid YAML.
    const val SPEC_WRITING_DESCRIPTION = "Use when you write or revise a feature specification (the Spec Kit specify phase). " +
        "The Spec Kit spec template decides the structure, these rules decide the content."
    const val KOTLIN_HEXAGONAL_DESCRIPTION = "Example for the Back Office stack. Use when you change a Kotlin service built as core and adapters " +
        "modules with ArchUnit-enforced layers; copy and adapt it for other projects."
    const val CONVENTIONAL_COMMITS_DESCRIPTION = "Use when you write a commit message, a merge request title or a branch name yourself. " +
        "In Back Office runner steps the runner commits and pushes, so agents never commit."
    const val SECURITY_REVIEW_DESCRIPTION = "Use when you review a diff or a merge request. The security checks to run before anything else; a hit is a BLOCKER."
    const val ANGULAR_NG_ZORRO_DESCRIPTION = "Example for the Back Office stack. Use when you change an Angular frontend with standalone components, " +
        "signals and NG-ZORRO; copy and adapt it for other projects."
    const val AGENT_GROUND_RULES_DESCRIPTION = "Use when you are about to change a file or claim something about code, requirements or test results. " +
        "Evidence, scope and safety rules of every agent step."
    const val ASK_DONT_GUESS_DESCRIPTION = "Use when information you need is missing, ambiguous or contradictory. Source hierarchy, the one " +
        "open-question marker per context and how to edit existing text."
    const val DEFINITION_OF_READY_DONE_DESCRIPTION = "Use when you check or hand over an artefact (user story, use case, ADR, implementation task, " +
        "release). Binary Definition of Ready and Done items and what to do when one fails."
    const val USER_STORY_FORMAT_DESCRIPTION = "Use when you write or review a user story. Structure, wording and falsifiable acceptance criteria."
    const val USE_CASE_FORMAT_DESCRIPTION = "Use when you expand a user story into a use case. Sections, flows and rules without implementation detail."
    const val ADR_FORMAT_DESCRIPTION = "Use when you draft or review an Architecture Decision Record. Sections, options, consequences and lifecycle."
    const val BUILD_GUARDRAILS_DESCRIPTION = "Use when a task adds or changes a rule a machine can check (architecture test, lint rule, CI check). " +
        "Identifiers, the enforcing build step and the negative test."

    val SPEC_WRITING_MD = """
        ---
        name: spec-writing
        description: $SPEC_WRITING_DESCRIPTION
        ---

        # Spec writing

        Follow the sections and numbering of the Spec Kit template (`spec-template.md`); where the template
        and these rules differ, the template wins. These rules are about the content.

        1. Describe what and why. The how belongs to the plan; if you catch yourself naming classes or
           tables, move that sentence out.
        2. Start with the user scenarios: who does what, what they see, what changes for them.
        3. Number functional requirements the way the template does (FR-001, FR-002, ...); each is
           testable, so a reviewer can say pass or fail without interpretation.
        4. Acceptance scenarios use Given / When / Then and cover the unhappy paths (invalid input,
           permissions, concurrency, empty states).
        5. Mark every unknown as `[NEEDS CLARIFICATION: <question>]`, the only open-question marker in a
           specification, instead of choosing silently. Prefer three precise markers over one vague paragraph.
        6. State what is out of scope explicitly, especially things a reader would assume are included.
        7. Name the data that is read or written in domain terms (issue, merge request, workflow run),
           not in storage terms.
        8. Keep non-functional requirements measurable: latency budgets, volumes, retention, audit needs.
        9. Never reference a previous conversation or attempt; the spec must stand on its own.
        10. Length: as short as possible while every FR is verifiable. Bullet lists over prose.
    """.trimIndent()

    val KOTLIN_HEXAGONAL_MD = """
        ---
        name: kotlin-hexagonal
        description: $KOTLIN_HEXAGONAL_DESCRIPTION
        ---

        # Kotlin hexagonal architecture (Back Office example)

        These are the rules of the Back Office codebase itself. For another project, copy the skill into the
        project and change what differs; the constitution of the project wins over this example.

        ## Layering
        - `domain.model`: immutable data classes; behaviour as methods returning new instances.
        - `domain.service`: pure services with no I/O; dependencies come in as function types or small interfaces.
        - `application.port.in`: one interface per use case; commands as nested data classes.
        - `application.port.out`: repositories and gateways the use cases need.
        - `application.service`: `*Service` classes implementing the use cases, plain Kotlin, no annotations.
        - `adapter.in.*` / `adapter.out.*`: Spring, JPA, HTTP clients; `adapter.config` wires services from ports.

        ## Rules
        1. Core modules have no Spring, JPA, Jackson or framework imports; the ArchUnit test fails otherwise.
        2. Domain never imports application; application never imports adapters.
        3. Errors: throw `NotFoundException`, `InvalidStateException`, `ValidationException` from the shared kernel; adapters map them to HTTP.
        4. Ids are `@JvmInline value class XId(val value: UUID)` with `new()` and `of(String)`.
        5. Time comes from the `Clock` port; never call `Instant.now()` in core.
        6. JSON columns are `jsonb` mapped through the adapter mapper, never through the domain.
        7. Tests: JUnit 5 + MockK for core (no Spring), `@SpringBootTest` + Testcontainers for adapters; test names read as sentences.
        8. A new use case = interface in `port.in`, service in `application.service`, bean in `adapter.config`, controller in `adapter.in.rest`.
        9. Prefer explicit mapping functions over reflection-based mappers.
        10. Flyway migrations are append-only; never edit an applied version.
    """.trimIndent()

    val CONVENTIONAL_COMMITS_MD = """
        ---
        name: conventional-commits
        description: $CONVENTIONAL_COMMITS_DESCRIPTION
        ---

        # Conventional commits

        In a Back Office runner step you do not commit: the runner commits your working tree once and takes
        the subject from the task. Use these rules when the task asks you to write a commit message, a merge
        request title or a branch name.

        1. Format: `type(scope): summary` in the imperative mood, at most 72 characters, no trailing period.
        2. Types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `perf`.
        3. Scope is the module or feature, e.g. `feat(tasks): add project-scoped template shadowing`.
        4. Body (optional) explains why, not what; wrap at 100 characters.
        5. Reference the issue in the footer: `Refs: PROJ-123`. Use `Closes: PROJ-123` only when the commit finishes the issue.
        6. Breaking changes: `!` after the scope and a `BREAKING CHANGE:` footer describing the migration.
        7. One logical change per commit; a commit must compile and pass tests on its own.
        8. Never commit generated files, secrets, IDE settings or build output.
        9. Do not amend or rewrite commits that were already pushed.
        10. Branch names follow the step's `branchTemplate` (default `feature/<issue-key-lowercase>`).
    """.trimIndent()

    val SECURITY_REVIEW_MD = """
        ---
        name: security-review-checklist
        description: $SECURITY_REVIEW_DESCRIPTION
        ---

        # Security review checklist

        Run through this list first; a hit here is a BLOCKER.

        1. Secrets: no tokens, passwords, private keys or connection strings in code, tests, configs or logs.
        2. Input handling: every value from HTTP, webhooks, files or the tracker is validated (type, length, pattern) before use.
        3. Injection: SQL through parameters only; shell commands built from user data are rejected, not escaped.
        4. Authorisation: new endpoints declare who may call them; project-scoped data is filtered by the caller's project.
        5. Deserialisation: no polymorphic or untyped deserialisation of external JSON; schemas are explicit.
        6. Path handling: file names from outside are normalised and confined to a base directory.
        7. Cryptography: standard library or well-known libraries only; no home-made hashing or random tokens from `Random`.
        8. Logging: no request bodies, prompts or personal data at INFO; error messages to clients carry no stack traces.
        9. Dependencies: a new dependency needs a reason in the description; pinned versions; no snapshot builds.
        10. Git and CI: no push to protected branches, no disabled tests, no `--no-verify`.
        11. Error paths: failures fail closed (deny, abort) rather than open.
        12. Data retention: new tables holding personal data name their retention or deletion path.
    """.trimIndent()

    val ANGULAR_NG_ZORRO_MD = """
        ---
        name: angular-ng-zorro
        description: $ANGULAR_NG_ZORRO_DESCRIPTION
        ---

        # Angular with NG-ZORRO (Back Office example)

        These are the rules of the Back Office frontend itself. For another project, copy the skill into the
        project and change what differs; the constitution of the project wins over this example.

        1. Standalone components only; no NgModules. Dependencies via `inject()`, never constructor injection.
        2. State is signals: `signal`, `computed`, `effect`; expose read-only signals from services.
        3. Feature folders under `src/app/features/<feature>/` with `pages/`, `components/`, `data/`.
        4. API types are generated from `docs/api/openapi.yaml`; never hand-write DTO interfaces that exist in `schema.d.ts`.
        5. Thin data services wrap `HttpClient`; components never call `HttpClient` directly.
        6. UI comes from NG-ZORRO (`nz-table`, `nz-form`, `nz-drawer`, `nz-tabs`); no other component library and no custom CSS frameworks.
        7. Forms: reactive forms with typed `FormGroup`; validation messages via `nzErrorTip`.
        8. Lists use `nz-table` with `nzData` bound to a signal; loading through `nzLoading`.
        9. Long-running states show progress (`nz-spin`, `nz-progress`) and an explicit empty state (`nz-empty`).
        10. Use `@if` / `@for` control flow with `track`; no `*ngIf` / `*ngFor`.
        11. Tests: component tests with the Angular testing harness; mock data services, not HTTP.
        12. Accessibility: every icon-only button has `nz-tooltip` and an `aria-label`.
    """.trimIndent()

    // ----- SDLC skills (ADR 0019, derived from a reference framework, rewritten in English) -----------

    val AGENT_GROUND_RULES_MD = """
        ---
        name: agent-ground-rules
        description: $AGENT_GROUND_RULES_DESCRIPTION
        ---

        # Agent ground rules

        ## Evidence
        1. Re-read a file right before you say anything about its current content; what you read earlier
           may be stale.
        2. Back every claim about the code with a path (and line); say what you checked and how.
        3. Report test results only for commands you ran in this step, with the command and its exit code.

        ## Scope
        4. The task sets the scope, not search results: a hit outside the named files or modules is
           information, not work.
        5. Make the narrowest edit that does the job: never rewrite a file to change a few lines and never
           reformat code you did not change.
        6. Problems you notice outside the task go to a "Noticed, not changed" list in your result.
        7. When the task contradicts the specification, the constitution or the code, report the
           contradiction with both sources quoted; do not pick a side silently.
        8. When a request is possible but breaks a documented rule, say so in your result.
        9. When your change alters documented architecture, configuration or behaviour, update that
           documentation in the same change.
        10. After your own mistake, revert it and report it; do not pile new changes on top.

        ## Safety
        11. Never print, log or commit secrets, tokens or credentials.
        12. Never run destructive commands and never disable, skip or delete a failing test to get a
            green build.
    """.trimIndent()

    val ASK_DONT_GUESS_MD = """
        ---
        name: ask-dont-guess
        description: $ASK_DONT_GUESS_DESCRIPTION
        ---

        # Ask, don't guess

        ## Source hierarchy (highest first)
        1. Decisions stated in the issue (summary, description, acceptance criteria) or in the owner's
           feedback on this run.
        2. Project rules: constitution, conventions, accepted ADRs.
        3. A style reference the owner pointed to explicitly.
        4. Old stories, legacy code, screenshots of other features: hypotheses to verify, never facts to
           copy. Do not close a gap by merging fragments of lower sources.

        ## When information is missing
        5. Do not fill a business gap by extrapolation, analogy with another feature or "common sense".
           An incomplete artefact with an explicit question beats a complete one built on a guess.
        6. One marker per context: `[NEEDS CLARIFICATION: <question>]` in a Spec Kit specification,
           `[?] <question>` everywhere else. Name an owner or a date only when the input gives them;
           never invent a person, a role or a deadline.
        7. `TODO: <what> - <why not now>` marks work left in an artefact and `TBD: <what> - <what decides
           it>` a section that cannot be written yet; a bare TODO or TBD is not allowed.
        8. Collect all markers in an "Open questions" section at the end, or in the open-question field
           of a JSON answer.
        9. When the step has a confidence field, lower it for every open question that changes the
           outcome; never report high confidence over unresolved markers.

        ## Editing existing text
        10. "Add" means append: insert a new item and renumber; do not reword existing items.
        11. Do not rewrite sentences written by a person unless the task names them; factual corrections only.
        12. Never change status fields (Status, Accepted, completed) of a document or task; they belong
            to the owner or the orchestrator.
        13. Keep answered questions in the list together with their answer.
    """.trimIndent()

    val DEFINITION_OF_READY_DONE_MD = """
        ---
        name: definition-of-ready-done
        description: $DEFINITION_OF_READY_DONE_DESCRIPTION
        ---

        # Definition of Ready / Definition of Done

        Every item is binary: it holds or it does not.

        ## Rules
        1. Check DoR before you start on an artefact and DoD before you hand it over; name every failed item.
        2. An artefact that fails DoR goes back to its author with the failed items; the next step waits.
        3. An artefact that fails DoD stays in progress: it is not closed and dependent work does not start.
        4. An exception is valid only with a recorded decision (ADR or issue comment) naming who decided,
           when and why. An unrecorded exception is untracked debt.
        5. Anti-pattern "DoD by vibe": merging because it looks finished. Tick each item explicitly.
        6. A project may tighten these lists, never loosen them.
        7. An agent checks the items and reports the failed ones; items people decide (approvals, status
           changes, numbers, dates) stay with people and an agent never sets them.

        ## User story (DoR)
        8. "As a <specific role>, I want <goal>, so that <benefit>"; functional description of one to three
           sentences; numbered criteria with at least one capability and one restriction, each falsifiable.

        ## Use case (DoR)
        9. Linked user story, main and alternative flows, feasibility confirmed by the architect,
           readiness confirmed by the analyst.

        ## ADR
        10. DoR: title states the decision, context, at least two options, decision, consequences, status
            Proposed; the number is assigned by a person (an agent's draft keeps `NNNN`).
        11. DoD (people only): accepted by the architect and one more reviewer, who set the status to
            Accepted; related documents linked.

        ## Implementation task
        12. DoR: linked story or use case, acceptance criteria rewritten as tests to write, approach agreed.
        13. DoD: planned tests written and green, CI green (lint, build, test), docs updated, work log
            filled in, no out-of-scope changes (found ones became separate tasks).

        ## Release
        14. DoR: every story and task meets DoD, regression green, release notes and rollback plan written.
        15. DoD: deployed by the pipeline, smoke tests green, monitoring confirms the non-functional
            criteria, version tagged.
    """.trimIndent()

    val USER_STORY_FORMAT_MD = """
        ---
        name: user-story-format
        description: $USER_STORY_FORMAT_DESCRIPTION
        ---

        # User story format

        Sections, in this order: title `US-<AREA>-<NN>: <short name>` (keep the placeholder ID unless the
        issue gives one), Metadata (author, phase, priority, status), User story, Functional description,
        Acceptance criteria, Examples (optional), Constraints and assumptions, Dependencies, Open questions.

        1. Story sentence: "**As a** <specific role>, **I want** <what becomes possible>, **so that**
           <business benefit>". Name the domain role (cashier, release manager), never just "user".
        2. Functional description: one to three sentences on goal, context and scope. Screen mechanics
           (drawers, buttons, messages, field limits) belong in the criteria, not here.
        3. Acceptance criteria are a numbered list under "**System:**", each item starting with a verb
           for observable behaviour, e.g. "System: 1. displays the list of open requests, 2. validates
           that the end date is not before the start date, 3. blocks saving without a reason".
        4. Each criterion is checkable by a counter-example: you can describe a situation in which the
           system breaks it. If you cannot, rewrite the criterion.
        5. Cover both sides: capabilities (what the system allows or does) and restrictions (what it
           requires, validates or blocks, and who may do it). No negative criteria = incomplete story.
        6. Describe the observable result, not the mechanism: no tables, columns, endpoints or codes.
        7. Criteria declare capabilities, not a flow; "first ... then ..." belongs in the use case.
        8. Describe messages by meaning ("informs that a reason is required"), not by literal wording,
           unless the owner gave the wording.
        9. Gherkin (Given / When / Then) is allowed in addition for complex rules; it never replaces the
           numbered list.
        10. Constraints and assumptions list only what the criteria do not: out-of-scope items and
            context delivered by other stories, as full sentences.
        11. Keep the story small (INVEST); propose a split when it covers more than one goal.
        12. Unknowns become `[?]` markers listed under Open questions; never invent a rule, a person or a date.
    """.trimIndent()

    val USE_CASE_FORMAT_MD = """
        ---
        name: use-case-format
        description: $USE_CASE_FORMAT_DESCRIPTION
        ---

        # Use case format

        ## Sections, in this order
        1. Metadata: ID `UC-<AREA>-<NN>` (keep the placeholder unless the input gives the ID), linked user
           story, author, phase, status.
        2. Actors: people, the system and external systems, each with its role in one line.
        3. Triggers and preconditions: the event that starts the use case and what must hold before it.
        4. Main flow A: the typical path to the actor's goal; one step = one action or reaction of one actor.
        5. Alternative flows A1, A2, ...: a title that states the situation and numbered, self-contained
           steps ending with an observable result (error shown, save blocked, exit without saving, or a
           successful variant). No "return to step 2.3" references.
        6. Business rules RB-01, RB-02, ...: limits, trimming, uniqueness and search rules; flows show
           only their observable effect.
        7. Postconditions: what is true after success and after each alternative.
        8. Permissions, stated positively: "a user with permission X can ...", never "without X cannot".

        ## Rules
        9. The use case realises its story: it adds flows, never scope, and does not copy the criteria.
        10. Describe what the user does and sees; no tables, columns, endpoints, classes or caches.
        11. External systems yes, mechanisms no: "the system fetches company data from the registry and
            displays it" is fine; protocols, retries and mappings are not.
        12. No hidden variants: "or", "either" or "may" in a step means a main step plus an alternative.
        13. A new flow never overwrites an existing one: insert it next to related flows and renumber.
        14. Main flow, alternatives and rules together must yield a test for every acceptance criterion.
        15. When the story is silent (message, target screen, validation), add a `[?]` marker; do not assume.
    """.trimIndent()

    val ADR_FORMAT_MD = """
        ---
        name: adr-format
        description: $ADR_FORMAT_DESCRIPTION
        ---

        # ADR format

        ## Sections
        1. Title `NNNN - <decision in a few words>`; people assign the sequential number (a draft keeps
           `NNNN`), and it is never changed or reused.
        2. Status: Proposed, Accepted, Deprecated or "Superseded by NNNN", with a date. A draft written by
           an agent is Proposed with the date `YYYY-MM-DD`; only people change the status.
        3. Context: the problem, constraints (technical, business, budget) and relevant non-functional
           requirements. No decision here.
        4. Options: at least two, labelled A, B, C, each with a description, pros and cons. Rejected
           options are described fairly.
        5. Decision: "We choose option X: <name>".
        6. Rationale: the criteria that decided, most important first.
        7. Consequences: positive; negative (costs, risks, limits and their mitigation); impact on code,
           infrastructure, team and cost.
        8. Related ADRs: extends, complements, supersedes.

        ## Rules
        9. One significant decision per ADR, written before the code that depends on it.
        10. Short: one to three pages; link to design documents instead of copying them.
        11. Append-only: never change the substance of an accepted ADR. A new ADR "Supersedes NNNN" and
            the old one gets status "Superseded by <new>".
        12. Be honest about costs; an ADR without negative consequences is incomplete.
        13. Link the ADR to the stories, use cases and open questions that forced it.
        14. Unknown facts are `[?]` markers listed as open questions, not assumptions.
    """.trimIndent()

    val BUILD_GUARDRAILS_MD = """
        ---
        name: build-guardrails
        description: $BUILD_GUARDRAILS_DESCRIPTION
        ---

        # Build guardrails

        A rule the build does not enforce does not exist. Every convention a machine can check is a
        guardrail with an ID, an enforcing build step and a negative test.

        ## Identifiers
        1. `GR-A<n>` architecture and boundaries: layers, module isolation, framework-free core, ports.
        2. `GR-S<n>` security: secret scanning, dependency vulnerabilities, authorisation checks.
        3. `GR-Q<n>` quality: formatter, linter with zero warnings, no stdout logging, deterministic tests.
        4. `GR-D<n>` contract and dependencies: API contract from one source, generated client types,
           versions in one catalogue.
        5. IDs are never reused; a retired rule keeps its ID with status "retired".

        ## Rules
        6. Each guardrail names its mechanism (ArchUnit test, detekt rule, lint script, CI job) and the
           exact command that runs it.
        7. Each guardrail has a negative test: a minimal violating example that makes the build fail. A
           rule without a proven failure is not trusted.
        8. A violation fails the build; a warning that does not fail is not a guardrail.
        9. Exceptions are local and explained: the suppression names the rule ID and the reason and, for
           security findings, an expiry date.
        10. A convention added to documentation gets its check in the same change, or is marked
            `TODO: not enforced yet - <reason>`.
        11. When a guardrail fails, fix the code, not the rule. Changing or disabling a rule needs the
            owner's decision recorded next to the rule.
        12. Fast checks run in the default build; heavy ones (containers, mutation tests) run in a
            separate CI step that is still mandatory.
    """.trimIndent()
}
