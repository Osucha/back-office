package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.ChecklistItem
import pl.mos.backoffice.projects.domain.model.CiWaitSpec
import pl.mos.backoffice.projects.domain.model.DecisionBranch
import pl.mos.backoffice.projects.domain.model.DecisionSpec
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.Point
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import java.time.Instant

/**
 * Factories for the three workflows seeded into every new project
 * (docs/architecture/03-pipelines-and-agents.md, ADR 0019). Template keys refer to the seeded Task Library.
 * Gates carry Definition-of-Ready/Done checklists and approver rules with one accountable role (RACI).
 * Node positions are the designer's layered layout (main path on the top row, branches below, cards
 * 240 px wide with 96 px gaps); frontend `seeded-workflows.fixture.ts` checks they stay in sync.
 * These factories run when a project is created: changing them changes only new projects, existing projects keep
 * the workflows they stored.
 */
object DefaultWorkflows {

    const val ISSUE_TO_REVIEWED_MR = "Issue to reviewed MR"
    const val CI_CODE_REVIEW = "CI code review"
    const val CHANGE_REQUEST = "Change request (delta)"

    /** Starter of a project without repositories (ADR 0034): one LLM task reviewed by a person. */
    const val LLM_TASK_WITH_REVIEW = "LLM task with review"

    /** The single accountable project role of the seeded gates. */
    const val ACCOUNTABLE_ROLE = "owner"

    const val TEMPLATE_FETCH_ISSUE = "fetch-issue"
    const val TEMPLATE_SPECIFY = "spec-kit-specify"
    const val TEMPLATE_CLARIFY = "clarify-spec"
    const val TEMPLATE_PLAN = "spec-kit-plan"
    const val TEMPLATE_TASKS = "spec-kit-tasks"
    const val TEMPLATE_IMPLEMENT = "implement-opencode"
    const val TEMPLATE_CODE_REVIEW = "code-review"
    const val TEMPLATE_TRANSITION = "transition-issue"
    const val TEMPLATE_IMPACT_ANALYSIS = "impact-analysis"
    const val TEMPLATE_WRITE_USER_STORY = "write-user-story"
    const val TEMPLATE_PLAN_TASK_LIST = "plan-task-list"
    const val TEMPLATE_IMPLEMENT_TASK_LIST = "implement-task-list"
    const val TEMPLATE_LLM_TASK = "llm-task"

    // Node ids shared by the seeded workflows (node ids are workflow-local; the same name keeps runs comparable).
    private const val NODE_FETCH_ISSUE = "fetch-issue"
    private const val NODE_SPEC_GATE = "spec-gate"
    private const val NODE_CI_TESTS = "ci-tests"
    private const val NODE_TESTS_FAILED = "tests-failed"
    private const val NODE_CODE_REVIEW = "code-review"
    private const val NODE_REVIEW_OK = "review-ok"
    private const val NODE_MERGE_GATE = "merge-gate"
    private const val NODE_IMPACT_ANALYSIS = "impact-analysis"
    private const val NODE_IMPACT_GATE = "impact-gate"
    private const val NODE_USER_STORY = "user-story"
    private const val NODE_PLAN_TASKS = "plan-tasks"
    private const val NODE_TASK_LIST_GATE = "task-list-gate"

    private const val LABEL_CODE_REVIEW = "AI code review"
    private const val LABEL_REVIEW_OK = "Review ok?"

    /** Artifacts are named like the task template that writes them. */
    private const val ARTIFACT_IMPACT_ANALYSIS = TEMPLATE_IMPACT_ANALYSIS
    private const val ARTIFACT_USER_STORY = "user-story"

    /** The task list artefact of `plan-task-list` and the Spec Kit tasks phase. */
    private const val ARTIFACT_TASKS = "tasks"

    /** Branch of the review decisions: taken unless the AI review requested changes. */
    private val REVIEW_APPROVED = DecisionBranch("approved", "no changes requested", "review.verdict != 'REQUEST_CHANGES'")

    /** Definition of Ready of a specification before planning starts. */
    val SPEC_READY = listOf(
        ChecklistItem("acceptance-criteria", "Acceptance criteria are testable"),
        ChecklistItem("open-questions", "Open questions are answered or explicitly deferred with an owner"),
        ChecklistItem("scope-stated", "Scope and out-of-scope are stated"),
    )

    /** Definition of Done of a merge request before it is merged. */
    val MERGE_DONE = listOf(
        ChecklistItem("tests-green", "Tests are green in CI"),
        ChecklistItem("review-findings-addressed", "Review findings are addressed or answered"),
        ChecklistItem("spec-updated", "Specification and docs are updated"),
    )

    /** Merge gates: the owner is accountable and nobody decides the gate of a run they started (four-eyes). */
    val MERGE_APPROVERS = GateApprovers(accountable = ACCOUNTABLE_ROLE, forbidSelfApproval = true)

    /** The SDLC workflows of a project with at least one repository. */
    fun all(projectId: ProjectId, now: Instant): List<WorkflowDefinition> =
        listOf(issueToReviewedMr(projectId, now), ciCodeReview(projectId, now), changeRequest(projectId, now))

    /** The Spec Kit templates of the seeded workflows (ADR 0036). */
    val SPEC_KIT_TEMPLATES = setOf(TEMPLATE_SPECIFY, TEMPLATE_PLAN, TEMPLATE_TASKS)

    /** A seeded workflow that needs Spec Kit (ADR 0036): seeded only into projects with Spec Kit on. */
    fun usesSpecKit(definition: WorkflowDefinition): Boolean =
        definition.nodes.filterIsInstance<WorkflowNode.Task>().any { it.task.templateKey in SPEC_KIT_TEMPLATES }

    /** The SDLC workflows for a project with ([specKit]) or without Spec Kit (ADR 0036). */
    fun sdlc(projectId: ProjectId, now: Instant, specKit: Boolean): List<WorkflowDefinition> =
        all(projectId, now).filter { specKit || !usesSpecKit(it) }

    /**
     * Starter of a project without repositories (ADR 0034): the `llm-task` template (its prompt is edited in the designer,
     * the material comes from the run parameter `input`) and a human gate whose `revise` sends the comment back as
     * feedback. Needs no repository, no Jira and no CI.
     */
    fun llmTaskWithReview(projectId: ProjectId, now: Instant): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId.new(),
        projectId = projectId,
        name = LLM_TASK_WITH_REVIEW,
        description = "Starter for any LLM work: an LLM task (edit its prompt in the designer; pass material as run parameter input) -> review by a person (approve, revise with a comment, reject).",
        version = 1,
        nodes = listOf(
            WorkflowNode.Start("start", "Start", Point(40.0, 40.0)),
            WorkflowNode.Task("llm-task", "LLM task", Point(316.0, 40.0), maxVisits = 5, task = TaskBinding(TEMPLATE_LLM_TASK)),
            WorkflowNode.HumanGate(
                "review", "Result ok?", Point(652.0, 40.0),
                gate = GateSpec(
                    question = "Is the result of the LLM task ok? Approve, ask for a revision (your comment goes back to the model) or reject.",
                    showArtifacts = listOf("result"),
                    approvers = GateApprovers(accountable = ACCOUNTABLE_ROLE),
                ),
            ),
            WorkflowNode.End("done", "Done", Point(988.0, 40.0), end = EndSpec(RunOutcome.SUCCESS)),
            WorkflowNode.End("rejected", "Rejected", Point(988.0, 244.0), end = EndSpec(RunOutcome.FAILURE)),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", WorkflowNode.PORT_OUT, "llm-task"),
            WorkflowEdge("e2", "llm-task", WorkflowNode.PORT_SUCCESS, "review"),
            WorkflowEdge("e3", "review", GateSpec.APPROVE, "done"),
            WorkflowEdge("e4", "review", GateSpec.REVISE, "llm-task", "revise"),
            WorkflowEdge("e5", "review", GateSpec.REJECT, "rejected"),
        ),
        triggers = listOf(Trigger(TriggerType.MANUAL), Trigger(TriggerType.API)),
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Jira issue -> Spec Kit -> implementation -> CI -> AI review -> merge gate. The `changes` branch of the review
     * returns to `implement`, whose prompt carries the latest `review` artefact (findings and verdict), so the rework
     * is not a blind re-run; a gate's `revise` comment reaches only the step it sends the run back to.
     */
    fun issueToReviewedMr(projectId: ProjectId, now: Instant): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId.new(),
        projectId = projectId,
        name = ISSUE_TO_REVIEWED_MR,
        description = "Jira issue -> Spec Kit specification -> clarification -> plan -> tasks -> implementation (MR via push options) -> CI tests -> AI review -> merge gate -> issue transition.",
        version = 1,
        nodes = listOf(
            WorkflowNode.Start("start", "Start", Point(40.0, 40.0)),
            WorkflowNode.Task(NODE_FETCH_ISSUE, "Fetch issue", Point(316.0, 40.0), task = TaskBinding(TEMPLATE_FETCH_ISSUE)),
            WorkflowNode.Task("specify", "Specify", Point(652.0, 40.0), task = TaskBinding(TEMPLATE_SPECIFY)),
            WorkflowNode.Task("clarify", "Clarify spec", Point(988.0, 40.0), task = TaskBinding(TEMPLATE_CLARIFY)),
            WorkflowNode.Decision(
                "confident", "Spec confident?", Point(1324.0, 40.0),
                decision = DecisionSpec(
                    branches = listOf(DecisionBranch("yes", "confidence >= 0.8", "confidence >= 0.8")),
                    defaultBranch = "no",
                ),
            ),
            WorkflowNode.HumanGate(
                NODE_SPEC_GATE, "Spec ok?", Point(1660.0, 262.0),
                gate = GateSpec(
                    question = "Spec for {{issue.key}} has open questions (confidence {{confidence}}). Approve, revise or reject?",
                    showArtifacts = listOf("spec", "clarifications"),
                    approvers = GateApprovers(accountable = ACCOUNTABLE_ROLE),
                    checklist = SPEC_READY,
                ),
            ),
            WorkflowNode.Task("plan", "Plan", Point(1996.0, 40.0), task = TaskBinding(TEMPLATE_PLAN)),
            WorkflowNode.Task("tasks", "Tasks", Point(2332.0, 40.0), task = TaskBinding(TEMPLATE_TASKS)),
            WorkflowNode.Task("implement", "Implement", Point(2668.0, 40.0), maxVisits = 3, task = TaskBinding(TEMPLATE_IMPLEMENT)),
            WorkflowNode.WaitForCi(NODE_CI_TESTS, "Wait for CI tests", Point(3004.0, 40.0), ci = CiWaitSpec(signal = "tests", timeoutSeconds = 7200)),
            WorkflowNode.End(NODE_TESTS_FAILED, "Tests failed", Point(3370.0, 262.0), end = EndSpec(RunOutcome.FAILURE)),
            WorkflowNode.Task(NODE_CODE_REVIEW, LABEL_CODE_REVIEW, Point(3340.0, 40.0), maxVisits = 3, task = TaskBinding(TEMPLATE_CODE_REVIEW)),
            WorkflowNode.Decision(
                NODE_REVIEW_OK, LABEL_REVIEW_OK, Point(3676.0, 40.0),
                decision = DecisionSpec(
                    branches = listOf(REVIEW_APPROVED),
                    defaultBranch = "changes",
                ),
            ),
            WorkflowNode.HumanGate(
                NODE_MERGE_GATE, "Merge?", Point(4012.0, 40.0),
                gate = GateSpec(
                    question = "{{issue.key}} passed AI review (verdict {{review.verdict}}, score {{review.score}}). Merge request: {{mr.url}}. Merge?",
                    showArtifacts = listOf("review", "diff"),
                    approvers = MERGE_APPROVERS,
                    checklist = MERGE_DONE,
                ),
            ),
            WorkflowNode.Task("transition", "Move issue to In Review", Point(4348.0, 40.0), task = TaskBinding(TEMPLATE_TRANSITION)),
            WorkflowNode.End("done", "Done", Point(4684.0, 40.0), end = EndSpec(RunOutcome.SUCCESS)),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", WorkflowNode.PORT_OUT, NODE_FETCH_ISSUE),
            WorkflowEdge("e2", NODE_FETCH_ISSUE, WorkflowNode.PORT_SUCCESS, "specify"),
            WorkflowEdge("e3", "specify", WorkflowNode.PORT_SUCCESS, "clarify"),
            WorkflowEdge("e4", "clarify", WorkflowNode.PORT_SUCCESS, "confident"),
            WorkflowEdge("e5", "confident", "yes", "plan", "confident"),
            WorkflowEdge("e6", "confident", "no", NODE_SPEC_GATE, "needs a look"),
            WorkflowEdge("e7", NODE_SPEC_GATE, GateSpec.APPROVE, "plan"),
            WorkflowEdge("e8", NODE_SPEC_GATE, GateSpec.REVISE, "specify", "revise"),
            WorkflowEdge("e9", "plan", WorkflowNode.PORT_SUCCESS, "tasks"),
            WorkflowEdge("e10", "tasks", WorkflowNode.PORT_SUCCESS, "implement"),
            WorkflowEdge("e11", "implement", WorkflowNode.PORT_SUCCESS, NODE_CI_TESTS),
            WorkflowEdge("e12", NODE_CI_TESTS, WorkflowNode.PORT_SUCCESS, NODE_CODE_REVIEW),
            WorkflowEdge("e13", NODE_CI_TESTS, WorkflowNode.PORT_FAIL, NODE_TESTS_FAILED),
            WorkflowEdge("e14", NODE_CODE_REVIEW, WorkflowNode.PORT_SUCCESS, NODE_REVIEW_OK),
            WorkflowEdge("e15", NODE_REVIEW_OK, "approved", NODE_MERGE_GATE),
            WorkflowEdge("e16", NODE_REVIEW_OK, "changes", "implement", "rework"),
            WorkflowEdge("e17", NODE_MERGE_GATE, GateSpec.APPROVE, "transition"),
            WorkflowEdge("e18", NODE_MERGE_GATE, GateSpec.REVISE, "implement", "revise"),
            WorkflowEdge("e19", "transition", WorkflowNode.PORT_SUCCESS, "done"),
        ),
        triggers = listOf(Trigger(TriggerType.MANUAL), Trigger(TriggerType.API)),
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )

    fun ciCodeReview(projectId: ProjectId, now: Instant): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId.new(),
        projectId = projectId,
        name = CI_CODE_REVIEW,
        description = "Reviews the diff uploaded by the CI job; the END outcome decides the CI gate result. Registered as entrypoint code-review.",
        version = 1,
        nodes = listOf(
            WorkflowNode.Start("start", "Start", Point(40.0, 40.0)),
            WorkflowNode.Task(NODE_CODE_REVIEW, LABEL_CODE_REVIEW, Point(316.0, 40.0), task = TaskBinding(TEMPLATE_CODE_REVIEW)),
            WorkflowNode.Decision(
                "verdict", LABEL_REVIEW_OK, Point(652.0, 40.0),
                decision = DecisionSpec(
                    branches = listOf(REVIEW_APPROVED),
                    defaultBranch = "changes",
                ),
            ),
            WorkflowNode.End("passed", "Passed", Point(988.0, 40.0), end = EndSpec(RunOutcome.SUCCESS)),
            WorkflowNode.End("failed", "Changes requested", Point(988.0, 244.0), end = EndSpec(RunOutcome.FAILURE)),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", WorkflowNode.PORT_OUT, NODE_CODE_REVIEW),
            WorkflowEdge("e2", NODE_CODE_REVIEW, WorkflowNode.PORT_SUCCESS, "verdict"),
            WorkflowEdge("e3", "verdict", "approved", "passed"),
            WorkflowEdge("e4", "verdict", "changes", "failed"),
        ),
        triggers = listOf(Trigger(TriggerType.CI), Trigger(TriggerType.MANUAL)),
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Change request against an existing system (ADR 0019): fetch issue -> impact analysis -> gate -> user story ->
     * task list (planned from the approved impact analysis) -> gate -> implementation (TDD) -> CI tests -> AI review
     * (its findings reach the rework through `{{artifacts.review}}`) -> merge gate. Only new projects get this
     * definition; existing projects keep the workflows stored when they were created.
     */
    fun changeRequest(projectId: ProjectId, now: Instant): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId.new(),
        projectId = projectId,
        name = CHANGE_REQUEST,
        description = "Delta change: Jira issue -> impact analysis (approved by the owner) -> user story -> task list with verification per task (approved) -> implementation -> CI tests -> AI review -> merge gate (four-eyes).",
        version = 1,
        nodes = listOf(
            WorkflowNode.Start("start", "Start", Point(40.0, 40.0)),
            WorkflowNode.Task(NODE_FETCH_ISSUE, "Fetch issue", Point(316.0, 40.0), task = TaskBinding(TEMPLATE_FETCH_ISSUE)),
            WorkflowNode.Task(NODE_IMPACT_ANALYSIS, "Impact analysis", Point(652.0, 40.0), task = TaskBinding(TEMPLATE_IMPACT_ANALYSIS)),
            WorkflowNode.HumanGate(
                NODE_IMPACT_GATE, "Approve impact analysis?", Point(988.0, 40.0),
                gate = GateSpec(
                    question = "Impact analysis for {{issue.key}} is ready. Approve the analysis, ask for a revision or reject the change?",
                    showArtifacts = listOf(ARTIFACT_IMPACT_ANALYSIS),
                    approvers = GateApprovers(accountable = ACCOUNTABLE_ROLE),
                    checklist = listOf(
                        ChecklistItem("affected-artefacts", "Affected artefacts are listed"),
                        ChecklistItem("estimate-given", "An estimate is given"),
                        ChecklistItem("options-compared", "Options are compared"),
                    ),
                ),
            ),
            WorkflowNode.Task(NODE_USER_STORY, "Write user story", Point(1324.0, 40.0), task = TaskBinding(TEMPLATE_WRITE_USER_STORY)),
            WorkflowNode.Task(NODE_PLAN_TASKS, "Plan task list", Point(1660.0, 40.0), task = TaskBinding(TEMPLATE_PLAN_TASK_LIST)),
            WorkflowNode.HumanGate(
                NODE_TASK_LIST_GATE, "Approve task list?", Point(1996.0, 40.0),
                gate = GateSpec(
                    question = "Task list for {{issue.key}} is ready. Approve, revise or reject?",
                    showArtifacts = listOf(ARTIFACT_USER_STORY, ARTIFACT_TASKS),
                    approvers = GateApprovers(accountable = ACCOUNTABLE_ROLE),
                    checklist = listOf(
                        ChecklistItem("task-verification", "Every task has verification and test_command"),
                        ChecklistItem("final-quality-task", "The last task is reformat, lint, and test"),
                    ),
                ),
            ),
            WorkflowNode.Task("implement", "Implement task list", Point(2332.0, 40.0), maxVisits = 3, task = TaskBinding(TEMPLATE_IMPLEMENT_TASK_LIST)),
            WorkflowNode.WaitForCi(NODE_CI_TESTS, "Wait for CI tests", Point(2668.0, 40.0), ci = CiWaitSpec(signal = "tests", timeoutSeconds = 7200)),
            WorkflowNode.End(NODE_TESTS_FAILED, "Tests failed", Point(3034.0, 262.0), end = EndSpec(RunOutcome.FAILURE)),
            WorkflowNode.Task(NODE_CODE_REVIEW, LABEL_CODE_REVIEW, Point(3004.0, 40.0), maxVisits = 3, task = TaskBinding(TEMPLATE_CODE_REVIEW)),
            WorkflowNode.Decision(
                "verdict", LABEL_REVIEW_OK, Point(3340.0, 40.0),
                decision = DecisionSpec(
                    branches = listOf(REVIEW_APPROVED),
                    defaultBranch = "changes",
                ),
            ),
            WorkflowNode.HumanGate(
                NODE_MERGE_GATE, "Merge?", Point(3676.0, 40.0),
                gate = GateSpec(
                    question = "{{issue.key}} passed AI review (verdict {{review.verdict}}, score {{review.score}}). Merge request: {{mr.url}}. Merge?",
                    showArtifacts = listOf("review", "diff"),
                    approvers = MERGE_APPROVERS,
                    checklist = MERGE_DONE,
                ),
            ),
            WorkflowNode.End("done", "Done", Point(4012.0, 40.0), end = EndSpec(RunOutcome.SUCCESS)),
        ),
        edges = listOf(
            WorkflowEdge("e1", "start", WorkflowNode.PORT_OUT, NODE_FETCH_ISSUE),
            WorkflowEdge("e2", NODE_FETCH_ISSUE, WorkflowNode.PORT_SUCCESS, NODE_IMPACT_ANALYSIS),
            WorkflowEdge("e3", NODE_IMPACT_ANALYSIS, WorkflowNode.PORT_SUCCESS, NODE_IMPACT_GATE),
            WorkflowEdge("e4", NODE_IMPACT_GATE, GateSpec.APPROVE, NODE_USER_STORY),
            WorkflowEdge("e5", NODE_IMPACT_GATE, GateSpec.REVISE, NODE_IMPACT_ANALYSIS, "revise"),
            WorkflowEdge("e6", NODE_USER_STORY, WorkflowNode.PORT_SUCCESS, NODE_PLAN_TASKS),
            WorkflowEdge("e7", NODE_PLAN_TASKS, WorkflowNode.PORT_SUCCESS, NODE_TASK_LIST_GATE),
            WorkflowEdge("e8", NODE_TASK_LIST_GATE, GateSpec.APPROVE, "implement"),
            WorkflowEdge("e9", NODE_TASK_LIST_GATE, GateSpec.REVISE, NODE_PLAN_TASKS, "revise"),
            WorkflowEdge("e10", "implement", WorkflowNode.PORT_SUCCESS, NODE_CI_TESTS),
            WorkflowEdge("e11", NODE_CI_TESTS, WorkflowNode.PORT_SUCCESS, NODE_CODE_REVIEW),
            WorkflowEdge("e12", NODE_CI_TESTS, WorkflowNode.PORT_FAIL, NODE_TESTS_FAILED),
            WorkflowEdge("e13", NODE_CODE_REVIEW, WorkflowNode.PORT_SUCCESS, "verdict"),
            WorkflowEdge("e14", "verdict", "approved", NODE_MERGE_GATE),
            WorkflowEdge("e15", "verdict", "changes", "implement", "rework"),
            WorkflowEdge("e16", NODE_MERGE_GATE, GateSpec.APPROVE, "done"),
            WorkflowEdge("e17", NODE_MERGE_GATE, GateSpec.REVISE, "implement", "revise"),
        ),
        triggers = listOf(Trigger(TriggerType.MANUAL), Trigger(TriggerType.API)),
        enabled = true,
        createdAt = now,
        updatedAt = now,
    )
}
