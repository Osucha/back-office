package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.ChecklistItem
import pl.mos.backoffice.projects.domain.model.CiWaitSpec
import pl.mos.backoffice.projects.domain.model.DecisionBranch
import pl.mos.backoffice.projects.domain.model.DecisionSpec
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.Trigger
import pl.mos.backoffice.projects.domain.model.TriggerType
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskOverrides

class WorkflowValidatorTest {

    private val validator = WorkflowValidator()

    private fun start() = WorkflowNode.Start("start")
    private fun end(id: String = "done") = WorkflowNode.End(id, "Done", end = EndSpec(RunOutcome.SUCCESS))
    private fun task(id: String, key: String = "code-review") = WorkflowNode.Task(id, id, task = TaskBinding(key))
    private fun edge(from: String, port: String, to: String) = WorkflowEdge("$from-$port-$to", from, port, to)

    private fun errorsOf(result: WorkflowValidationResult) = result.errors.map { listOfNotNull(it.nodeId ?: it.edgeId, it.field).joinToString(".") + ": " + it.message }

    @Test
    fun `the seeded workflows are valid`() {
        val project = Fixtures.project()
        val seeded = DefaultWorkflows.all(project.id, Fixtures.now)
        assertThat(seeded.map { it.name }).containsExactly(DefaultWorkflows.ISSUE_TO_REVIEWED_MR, DefaultWorkflows.CI_CODE_REVIEW, DefaultWorkflows.CHANGE_REQUEST)
        seeded.forEach { wf ->
            val result = validator.validate(wf.name, wf.nodes, wf.edges, triggers = wf.triggers)
            assertThat(errorsOf(result)).describedAs(wf.name).isEmpty()
            // The designer opens them clean: no warnings either (the CI gate runs unattended by design).
            assertThat(result.warnings.map { it.message }).describedAs(wf.name).isEmpty()
        }
    }

    @Test
    fun `seeded node cards do not overlap in the designer`() {
        // Designer cards: 240 px wide (START/END 180), at least 76 px tall; the designer lays out
        // graphs whose cards overlap, so the seed must keep them apart (layered layout positions).
        val project = Fixtures.project()
        DefaultWorkflows.all(project.id, Fixtures.now).forEach { wf ->
            val cards = wf.nodes.map { n ->
                val w = if (n.kind == NodeKind.START || n.kind == NodeKind.END) 180.0 else 240.0
                Triple(n.id, n.position, w)
            }
            cards.forEach { (a, pa, wa) ->
                cards.filter { it.first != a }.forEach { (b, pb, wb) ->
                    val apartX = pa.x + wa + 80 <= pb.x || pb.x + wb + 80 <= pa.x
                    val apartY = kotlin.math.abs(pa.y - pb.y) >= 200
                    assertThat(apartX || apartY).describedAs("${wf.name}: $a and $b overlap").isTrue()
                }
            }
        }
    }

    @Test
    fun `a workflow without a human gate is flagged unless CI triggers it`() {
        val nodes = listOf(start(), task("a"), end())
        val edges = listOf(edge("start", "out", "a"), edge("a", "success", "done"))
        val manual = validator.validate("x", nodes, edges, triggers = listOf(Trigger(TriggerType.MANUAL)))
        assertThat(manual.warnings.map { it.message }).contains("workflow has no human gate; it will run fully unattended")
        val ci = validator.validate("x", nodes, edges, triggers = listOf(Trigger(TriggerType.CI)))
        assertThat(ci.warnings.map { it.message }).doesNotContain("workflow has no human gate; it will run fully unattended")
    }

    @Test
    fun `seeded gates carry checklists and exactly one accountable role, merge gates forbid self-approval`() {
        val project = Fixtures.project()
        val gates = DefaultWorkflows.all(project.id, Fixtures.now).flatMap { wf -> wf.nodes.filterIsInstance<WorkflowNode.HumanGate>().map { wf.name to it } }
        assertThat(gates).isNotEmpty
        gates.forEach { (_, gate) ->
            assertThat(gate.gate.approvers.accountable).isEqualTo("owner")
            assertThat(gate.gate.checklist).isNotEmpty
        }
        val merges = gates.filter { it.second.id == "merge-gate" }
        assertThat(merges.map { it.first }).containsExactlyInAnyOrder(DefaultWorkflows.ISSUE_TO_REVIEWED_MR, DefaultWorkflows.CHANGE_REQUEST)
        merges.forEach { (_, gate) ->
            assertThat(gate.gate.approvers.forbidSelfApproval).isTrue()
            assertThat(gate.gate.checklist.map { it.id }).containsExactly("tests-green", "review-findings-addressed", "spec-updated")
        }
        val change = DefaultWorkflows.changeRequest(project.id, Fixtures.now)
        assertThat(change.nodes.filterIsInstance<WorkflowNode.Task>().map { it.task.templateKey })
            .containsExactly("fetch-issue", "impact-analysis", "write-user-story", "plan-task-list", "implement-task-list", "code-review")
        assertThat((change.requireNode("task-list-gate") as WorkflowNode.HumanGate).gate.checklist.map { it.text })
            .containsExactly("Every task has verification and test_command", "The last task is reformat, lint, and test")
    }

    @Test
    fun `the change request starts by fetching the issue, its task-list gate shows the tasks artefact and the review loops back to implement`() {
        val change = DefaultWorkflows.changeRequest(Fixtures.project().id, Fixtures.now)
        val graph = WorkflowGraph(change.nodes, change.edges)
        assertThat(change.edges.single { it.from == "start" }.to).isEqualTo("fetch-issue")
        assertThat(change.edges.single { it.from == "fetch-issue" && it.fromPort == WorkflowNode.PORT_SUCCESS }.to).isEqualTo("impact-analysis")
        assertThat((change.requireNode("task-list-gate") as WorkflowNode.HumanGate).gate.showArtifacts).containsExactly("user-story", "tasks")
        assertThat(change.edges.single { it.from == "verdict" && it.fromPort == "changes" }.to).isEqualTo("implement")
        assertThat(change.edges.map { it.id }).doesNotHaveDuplicates()
        assertThat(graph.nodesOnCycles()).contains("implement", "code-review", "verdict")
        assertThat(errorsOf(validator.validate(change.name, change.nodes, change.edges, triggers = change.triggers))).isEmpty()
    }

    @Test
    fun `gate approver roles must be slugs, the accountable role one of them and checklist ids unique`() {
        fun gate(approvers: GateApprovers, checklist: List<ChecklistItem> = emptyList()) = validator.validate(
            "x",
            listOf(start(), WorkflowNode.HumanGate("g", "G", gate = GateSpec("ok?", approvers = approvers, checklist = checklist)), end()),
            listOf(edge("start", "out", "g"), edge("g", "approve", "done")),
        )
        assertThat(errorsOf(gate(GateApprovers(projectRoles = listOf("owner", "approver"), accountable = "owner")))).isEmpty()
        assertThat(errorsOf(gate(GateApprovers(accountable = "owner")))).isEmpty()
        assertThat(errorsOf(gate(GateApprovers(projectRoles = listOf("Owner Role"))))).anyMatch { it.startsWith("g.gate.approvers.projectRoles") }
        assertThat(errorsOf(gate(GateApprovers(projectRoles = listOf("approver"), accountable = "owner")))).anyMatch { it.startsWith("g.gate.approvers.accountable") && it.contains("must be one of") }
        assertThat(errorsOf(gate(GateApprovers(accountable = "Bad!")))).anyMatch { it.startsWith("g.gate.approvers.accountable") }
        assertThat(errorsOf(gate(GateApprovers(), listOf(ChecklistItem("a", "A"), ChecklistItem("a", "again"))))).anyMatch { it == "g.gate.checklist.a: checklist id is not unique" }
        assertThat(errorsOf(gate(GateApprovers(), listOf(ChecklistItem("Bad Id", "A"))))).anyMatch { it.startsWith("g.gate.checklist.Bad Id") }
        assertThat(errorsOf(gate(GateApprovers(), listOf(ChecklistItem("a", " "))))).anyMatch { it == "g.gate.checklist.a: text must not be blank" }
    }

    @Test
    fun `exactly one START and at least one END are required`() {
        val none = validator.validate("x", listOf(task("a"), end()), listOf(edge("a", "success", "done")))
        assertThat(errorsOf(none)).anyMatch { it.contains("exactly one START") }

        val two = validator.validate("x", listOf(start(), WorkflowNode.Start("start2"), end()), listOf(edge("start", "out", "done"), edge("start2", "out", "done")))
        assertThat(errorsOf(two)).anyMatch { it.contains("exactly one START") && it.contains("found 2") }

        val noEnd = validator.validate("x", listOf(start(), task("a")), listOf(edge("start", "out", "a")))
        assertThat(errorsOf(noEnd)).anyMatch { it.contains("at least one END") }
    }

    @Test
    fun `node ids must be unique slugs and names non-blank`() {
        val result = validator.validate(
            "x",
            listOf(start(), task("Bad Id"), WorkflowNode.Task("a", " ", task = TaskBinding("x")), task("a"), end()),
            listOf(edge("start", "out", "a")),
        )
        assertThat(errorsOf(result)).anyMatch { it.startsWith("Bad Id.id") }
        assertThat(errorsOf(result)).anyMatch { it == "a.id: node id is not unique" }
        assertThat(errorsOf(result)).anyMatch { it == "a.name: must not be blank" }
    }

    @Test
    fun `edge endpoints and ports must exist and be unique per port`() {
        val nodes = listOf(start(), task("a"), end())
        val result = validator.validate(
            "x", nodes,
            listOf(
                edge("start", "out", "a"), edge("a", "success", "done"), edge("a", "success", "start"),
                edge("a", "bogus", "done"), edge("ghost", "out", "done"), edge("a", "failure", "ghost"), edge("done", "out", "a"),
            ),
        )
        val errors = errorsOf(result)
        assertThat(errors).anyMatch { it.contains("port success has more than one outgoing edge") }
        assertThat(errors).anyMatch { it.contains("no port bogus") }
        assertThat(errors).anyMatch { it.contains("node ghost does not exist") }
        assertThat(errors).anyMatch { it.contains("END nodes have no output ports") }
        assertThat(errors).anyMatch { it.contains("START nodes cannot be targets") }
    }

    @Test
    fun `TASK ports come from the task ports provider and unknown or abstract templates are reported`() {
        val nodes = listOf(start(), task("review", "code-review"), task("ghost", "nope"), task("base", "base-agent"), end())
        val edges = listOf(edge("start", "out", "review"), edge("review", "approved", "base"), edge("base", "success", "ghost"), edge("ghost", "success", "done"))
        val provider = TaskPortsProvider { key ->
            when (key) {
                "code-review" -> TaskTemplateInfo(listOf("approved", "changes"))
                "base-agent" -> TaskTemplateInfo(listOf("success", "failure"), abstract = true)
                else -> null
            }
        }
        val result = validator.validate("x", nodes, edges, provider)
        val errors = errorsOf(result)
        assertThat(errors).anyMatch { it == "ghost.task.templateKey: unknown task template nope" }
        assertThat(errors).noneMatch { it.contains("no port approved") }
        assertThat(errors).noneMatch { it.contains("ports.success") && it.startsWith("review") }
        assertThat(result.warnings.map { it.message }).anyMatch { it.contains("abstract") }
    }

    @Test
    fun `required ports of reachable nodes must be connected and unreachable nodes only warn`() {
        val nodes = listOf(start(), task("a"), task("orphan"), WorkflowNode.WaitForCi("ci", "CI", ci = CiWaitSpec("tests")), end())
        val edges = listOf(edge("start", "out", "a"), edge("a", "success", "ci"), edge("ci", "fail", "done"))
        val result = validator.validate("x", nodes, edges)
        assertThat(errorsOf(result)).containsExactly("ci.ports.success: port success must be connected")
        assertThat(result.warnings.map { it.nodeId to it.message }).contains("orphan" to "node is never reached from START")
    }

    @Test
    fun `decision branches need ids, expressions that parse and a non-empty list`() {
        val nodes = listOf(
            start(),
            WorkflowNode.Decision("d", "D", decision = DecisionSpec(branches = listOf(DecisionBranch("Yes", "yes", "confidence >= 0.8"), DecisionBranch("b", "b", "")), defaultBranch = "no")),
            WorkflowNode.Decision("empty", "E", decision = DecisionSpec(branches = emptyList())),
            end(),
        )
        val edges = listOf(edge("start", "out", "d"), edge("d", "Yes", "empty"), edge("d", "b", "done"), edge("d", "no", "done"))
        val result = validator.validate("x", nodes, edges, expressions = ExpressionValidator { if (it.contains(">=")) null else "bad" })
        val errors = errorsOf(result)
        assertThat(errors).anyMatch { it.startsWith("d.decision.branches.Yes:") && it.contains("branch id must match") }
        assertThat(errors).anyMatch { it == "d.decision.branches.b.expression: expression must not be blank" }
        assertThat(errors).anyMatch { it == "empty.decision.branches: at least one branch is required" }
    }

    @Test
    fun `gate options and signal names are checked`() {
        val nodes = listOf(
            start(),
            WorkflowNode.HumanGate("g", "G", gate = GateSpec(question = " ", options = emptyList())),
            WorkflowNode.WaitForCi("ci", "CI", ci = CiWaitSpec("Bad Signal", timeoutSeconds = 0)),
            end(),
        )
        val edges = listOf(edge("start", "out", "g"), edge("g", "approve", "ci"), edge("ci", "success", "done"))
        val errors = errorsOf(validator.validate("x", nodes, edges))
        assertThat(errors).contains("g.gate.question: must not be blank", "g.gate.options: must not be empty")
        assertThat(errors).anyMatch { it.startsWith("ci.ci.signal: must match") }
        assertThat(errors).contains("ci.ci.timeoutSeconds: must be positive")
    }

    @Test
    fun `an unattended workflow gets a warning and a blank name an error`() {
        val result = validator.validate(" ", listOf(start(), end()), listOf(edge("start", "out", "done")))
        assertThat(errorsOf(result)).containsExactly("name: must not be blank")
        assertThat(result.warnings.map { it.message }).anyMatch { it.contains("no human gate") }
    }

    @Test
    fun `loops are allowed and unconnected failure ports do not fail validation`() {
        val nodes = listOf(start(), task("impl"), task("review"), end())
        val edges = listOf(edge("start", "out", "impl"), edge("impl", "success", "review"), edge("review", "success", "done"), edge("review", "failure", "impl"))
        val result = validator.validate("loop", nodes, edges)
        assertThat(result.valid).isTrue()
        assertThat(WorkflowGraph(nodes, edges).nodesOnCycles()).containsExactlyInAnyOrder("impl", "review")
    }

    // ---- prompt injection (ADR 0029): routing on model output

    private val pushing = TaskPortsProvider { key ->
        TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, pushCapable = key in setOf("spec-kit-plan", "spec-kit-tasks", "implement-opencode", "implement-task-list"))
    }

    @Test
    fun `a decision on model output that reaches a push without a human gate is a warning`() {
        val nodes = listOf(
            start(), task("clarify", "clarify-spec"),
            WorkflowNode.Decision("ok", "ok?", decision = DecisionSpec(listOf(DecisionBranch("yes", "confident", "confidence >= 0.8 and review.score > 50")), defaultBranch = "no")),
            task("impl", "implement-opencode"), end(), end("stop"),
        )
        val edges = listOf(
            edge("start", "out", "clarify"), edge("clarify", "success", "ok"), edge("ok", "yes", "impl"), edge("ok", "no", "stop"), edge("impl", "success", "done"),
        )
        val warning = validator.validate("wf", nodes, edges, pushing).warnings.single { it.nodeId == "ok" }
        assertThat(warning.field).isEqualTo("decision.branches.yes.expression")
        assertThat(warning.message).contains("confidence, review").contains("impl").contains("no human gate follows")
    }

    @Test
    fun `gated routes, rework loops and deterministic expressions are not warned about`() {
        val project = Fixtures.project()
        // seeded workflows: the review verdict leads to the merge gate; the rework branch loops back
        val change = DefaultWorkflows.changeRequest(project.id, Fixtures.now)
        assertThat(validator.validate(change.name, change.nodes, change.edges, pushing, triggers = change.triggers).warnings).isEmpty()
        val ci = DefaultWorkflows.ciCodeReview(project.id, Fixtures.now)
        assertThat(validator.validate(ci.name, ci.nodes, ci.edges, pushing, triggers = ci.triggers).warnings).isEmpty()
        // the issue workflow routes on spec confidence into Spec Kit and implementation: warned, the merge gate still follows
        val issue = DefaultWorkflows.issueToReviewedMr(project.id, Fixtures.now)
        val warnings = validator.validate(issue.name, issue.nodes, issue.edges, pushing, triggers = issue.triggers).warnings
        assertThat(warnings.map { it.nodeId }).containsExactly("confident")
        assertThat(warnings.single().message).contains("later human gate")
        // a deterministic signal (CI) is fine
        assertThat(WorkflowValidator.expressionRoots("ci.status == 'SUCCESS' and issue.priority != 'review'")).containsExactlyInAnyOrder("ci", "issue")
        assertThat(WorkflowValidator.expressionRoots("review.verdict != 'REQUEST_CHANGES'")).containsExactly("review")
    }

    // ---- run inputs (ADR 0053)

    private val issueField = RunInputField("issueKey", "Jira issue", RunInputType.ISSUE_KEY, required = true)
    private val inputs = TaskPortsProvider { key ->
        when (key) {
            "fetch-issue" -> TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, runInputs = listOf(issueField))
            "fetch-from-param" -> TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, runInputs = listOf(issueField), issueKeyFrom = "story")
            "story-writer" -> TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, runInputs = listOf(RunInputField("story", "Story key", RunInputType.ISSUE_KEY)))
            "notes" -> TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, runInputs = listOf(RunInputField("input", "Notes", RunInputType.MULTILINE)))
            "material" -> TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS, runInputs = listOf(RunInputField("input", "Input material", RunInputType.MULTILINE)))
            else -> TaskTemplateInfo(WorkflowNode.DEFAULT_TASK_PORTS)
        }
    }

    private fun chain(vararg tasks: WorkflowNode.Task): Pair<List<WorkflowNode>, List<WorkflowEdge>> {
        val ids = listOf("start") + tasks.map { it.id } + "done"
        return (listOf(start()) + tasks + end()) to ids.zipWithNext().map { (a, b) -> edge(a, if (a == "start") "out" else "success", b) }
    }

    @Test
    fun `a Jira step reading the issue key from an undeclared run parameter is a warning, not an error`() {
        val fromOverride = WorkflowNode.Task("fetch", "Fetch", task = TaskBinding("fetch-issue", TaskOverrides(config = mapOf("issueKeyFrom" to "ticket"))))
        val (nodes, edges) = chain(fromOverride)

        val result = validator.validate("wf", nodes, edges, inputs)

        assertThat(result.valid).isTrue()
        assertThat(result.warnings.single { it.nodeId == "fetch" }).satisfies({
            assertThat(it.field).isEqualTo("task.config.issueKeyFrom")
            assertThat(it.message).contains("ticket").contains("no task of this workflow declares")
        })
    }

    @Test
    fun `issueKeyFrom input, a declared parameter or the template default declared elsewhere do not warn`() {
        val (plain, plainEdges) = chain(task("fetch", "fetch-issue"))
        assertThat(validator.validate("wf", plain, plainEdges, inputs).warnings.filter { it.field == "task.config.issueKeyFrom" }).isEmpty()

        val (declared, declaredEdges) = chain(task("write", "story-writer"), task("fetch", "fetch-from-param"))
        assertThat(validator.validate("wf", declared, declaredEdges, inputs).warnings.filter { it.field == "task.config.issueKeyFrom" }).isEmpty()

        val (undeclared, undeclaredEdges) = chain(task("fetch", "fetch-from-param"))
        assertThat(validator.validate("wf", undeclared, undeclaredEdges, inputs).warnings.map { it.nodeId to it.field })
            .contains("fetch" to "task.config.issueKeyFrom")
    }

    @Test
    fun `two tasks declaring the same run input with different labels is a warning naming both`() {
        val (nodes, edges) = chain(task("a", "material"), task("b", "notes"), task("c", "material"))

        val result = validator.validate("wf", nodes, edges, inputs)

        assertThat(result.valid).isTrue()
        val warning = result.warnings.single { it.field == "runInputs.input" }
        assertThat(warning.nodeId).isEqualTo("b")
        assertThat(warning.message).contains("Input material").contains("Notes").contains("first label wins")
    }
}
