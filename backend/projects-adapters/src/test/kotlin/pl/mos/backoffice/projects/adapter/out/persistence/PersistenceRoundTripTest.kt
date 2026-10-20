package pl.mos.backoffice.projects.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.LogLevel
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunFilter
import pl.mos.backoffice.projects.application.port.out.RunLogLine
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.ApprovalContext
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.CiExecution
import pl.mos.backoffice.projects.domain.model.CiExecutionId
import pl.mos.backoffice.projects.domain.model.CiMergeRequest
import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.CiProvider
import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.GateResult
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.MergeRequestSnapshot
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.RunFailure
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.Verdict
import pl.mos.backoffice.projects.domain.model.WaitKind
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import pl.mos.backoffice.projects.domain.model.ChecklistItem
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.projects.domain.model.ProjectWorkspace
import pl.mos.backoffice.projects.domain.model.ProjectWorkspaceId
import pl.mos.backoffice.projects.application.port.out.ProjectWorkspaceRepository
import pl.mos.backoffice.projects.domain.model.WorkspaceSource
import pl.mos.backoffice.projects.domain.model.CiExecutionStatus
import pl.mos.backoffice.projects.application.port.`in`.CiExecutionQueries
import pl.mos.backoffice.shared.usage.SortSpec

@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class PersistenceRoundTripTest {

    @Autowired lateinit var projects: ProjectRepository
    @Autowired lateinit var tokens: ProjectCiTokenRepository
    @Autowired lateinit var workflows: WorkflowDefinitionRepository
    @Autowired lateinit var runs: WorkflowRunRepository
    @Autowired lateinit var approvals: ApprovalRequestRepository
    @Autowired lateinit var executions: CiExecutionRepository
    @Autowired lateinit var logs: RunLogRepository
    @Autowired lateinit var workspaces: ProjectWorkspaceRepository

    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun project(key: String) = projects.save(
        Project(
            id = ProjectId.new(), key = key, name = "Shop $key", description = "desc",
            repositories = listOf(GitRepository.legacy("https://git.example.com/group/shop.git", "main")),
            constitution = "# Constitution\nBe nice.",
            defaults = ProjectDefaults(modelProfileId = ModelProfileId.new(), reviewModelProfileId = ModelProfileId.new()),
            createdAt = now, updatedAt = now, createdBy = alice,
        ),
    )

    private val alice = Actor(PrincipalKind.USER, UUID.fromString("00000000-0000-0000-0000-00000000a11c"), "Alice")
    private val ciActor = Actor(PrincipalKind.CI_TOKEN, null, "CI token gitlab")

    @Test
    fun `project with entrypoints, tokens and the seeded workflows round-trip including the JSONB graph`() {
        var project = project("RT1")
        val seeded = DefaultWorkflows.all(project.id, now).map { workflows.save(it.copy(updatedBy = alice)) }
        val review = seeded.first { it.name == DefaultWorkflows.CI_CODE_REVIEW }
        project = projects.save(project.withEntrypoints(listOf(CiEntrypoint("code-review", review.id, CiMode.ADVISORY, "desc", uploadDiff = false)), now))

        val loadedProject = projects.findByKey("RT1")!!
        assertThat(loadedProject).isEqualTo(project)
        assertThat(loadedProject.entrypoint("code-review")?.workflowId).isEqualTo(review.id)

        seeded.forEach { definition ->
            val loaded = workflows.findById(definition.id)!!
            assertThat(loaded).isEqualTo(definition)
        }
        val issueToMr = workflows.findById(seeded.first { it.name == DefaultWorkflows.ISSUE_TO_REVIEWED_MR }.id)!!
        assertThat((issueToMr.requireNode("confident") as WorkflowNode.Decision).decision.branches.single().expression).isEqualTo("confidence >= 0.8")
        assertThat((issueToMr.requireNode("ci-tests") as WorkflowNode.WaitForCi).ci.timeoutSeconds).isEqualTo(7200)
        assertThat((issueToMr.requireNode("spec-gate") as WorkflowNode.HumanGate).gate.showArtifacts).containsExactly("spec", "clarifications")
        assertThat(issueToMr.requireNode("implement").maxVisits).isEqualTo(3)
        assertThat(workflows.countByProjectId(project.id)).isEqualTo(3)
        assertThat(loadedProject.createdBy).isEqualTo(alice)
        assertThat(issueToMr.updatedBy).isEqualTo(alice)
        val mergeGate = (issueToMr.requireNode("merge-gate") as WorkflowNode.HumanGate).gate
        assertThat(mergeGate.approvers).isEqualTo(GateApprovers(accountable = "owner", forbidSelfApproval = true))
        assertThat(mergeGate.checklist.map { it.id }).containsExactly("tests-green", "review-findings-addressed", "spec-updated")

        val token = tokens.save(ProjectCiToken(ProjectCiTokenId.new(), project.id, "gitlab", "hash-1", "bo_ci_abcdef", now, createdBy = alice))
        assertThat(tokens.findByHash("hash-1")).isEqualTo(token)
        assertThat(tokens.save(token.revoke(now)).revokedAt).isEqualTo(now)
        assertThat(tokens.findByProjectId(project.id).single().revoked).isTrue()
    }

    @Test
    fun `project workspaces round-trip, unique per project and gone with the project (ADR 0044)`() {
        val project = project("RTW")
        val main = ProjectWorkspace(
            ProjectWorkspaceId.new(), project.id, "main", "Main workspace", "shared clones",
            listOf(WorkspaceSource("main"), WorkspaceSource("standards", library = true)), "npm ci", listOf("implementer", "reviewer"), now, now, alice,
        )
        workspaces.save(main)
        workspaces.save(ProjectWorkspace(ProjectWorkspaceId.new(), project.id, "docs", createdAt = now, updatedAt = now))
        assertThat(workspaces.findById(main.id)).isEqualTo(main)
        assertThat(workspaces.findByProjectId(project.id).map { it.key }).containsExactly("docs", "main")
        workspaces.save(main.copy(name = "Renamed", roles = emptyList()))
        assertThat(workspaces.findById(main.id)!!.name).isEqualTo("Renamed")
        workspaces.delete(main.id)
        assertThat(workspaces.findByProjectId(project.id).map { it.key }).containsExactly("docs")
        projects.delete(project.id)
        assertThat(workspaces.findByProjectId(project.id)).isEmpty()
    }

    @Test
    fun `run with node executions, artefacts, review, merge request, signal and failure round-trips and is queryable`() {
        val project = project("RT2")
        val definition = workflows.save(DefaultWorkflows.issueToReviewedMr(project.id, now))
        val ci = CiContext(CiProvider.GITLAB, "g/shop", "1", "2", ref = "feature/x", commitSha = "abc", mergeRequest = CiMergeRequest(iid = "7", sourceBranch = "feature/x", targetBranch = "main"))
        var run = WorkflowRun.create(project, definition, RunTrigger(TriggerSource.CI, CiExecutionId.new()), RunInput("RT2-1", mapOf("priority" to "high"), ci), now, startedBy = ciActor).start(now)
        run = run.nodeStarted(definition.requireNode("start"), now).nodeFinished("start", "out", now)
        run = run.nodeStarted(definition.requireNode("fetch-issue"), now).nodeFinished("fetch-issue", "success", now, summary = "RT2-1: hello", cost = Money.usd(0.1), usage = TokenUsage(10, 5))
        run = run.addArtifact(Artifact(ArtifactId.new(), "specify", "spec", ArtifactKind.SPEC, content = "# Spec\n\nBig text", createdAt = now))
        run = run.withMergeRequest(MergeRequestSnapshot("https://gl/mr/7", "feature/rt2-1", "main"))
        run = run.withReview(ReviewReport(Verdict.COMMENT, 70, "meh", listOf(Finding(Severity.MAJOR, "a.kt", 3, "NPE", "null deref", "check null")), ModelRef(Provider.ANTHROPIC, "claude-opus-5"), TokenUsage(500, 50), Money.usd(0.01)))
        run = run.copy(lastSignal = CiSignal("tests", CiSignalStatus.FAIL, "3 failed", ci, now))
        run = run.nodeStarted(definition.requireNode("specify"), now).nodeFailed("specify", "runner exploded", now, port = "failure")
        run = run.failed(RunFailure("specify", "runner exploded", true), now)
        runs.save(run)

        val loaded = runs.findById(run.id)!!
        assertThat(loaded.status).isEqualTo(RunStatus.FAILED)
        assertThat(loaded.outcome).isEqualTo(RunOutcome.FAILURE)
        assertThat(loaded.trigger).isEqualTo(run.trigger)
        assertThat(loaded.input).isEqualTo(run.input)
        assertThat(loaded.nodes).isEqualTo(run.nodes)
        assertThat(loaded.artifacts.single().content).isEqualTo("# Spec\n\nBig text")
        assertThat(loaded.mergeRequest).isEqualTo(run.mergeRequest)
        assertThat(loaded.review).isEqualTo(run.review)
        assertThat(loaded.lastSignal).isEqualTo(run.lastSignal)
        assertThat(loaded.failure?.nodeId).isEqualTo("specify")
        assertThat(loaded.definitionSnapshot).isEqualTo(definition)
        assertThat(loaded.startedBy).isEqualTo(ciActor)

        assertThat(runs.find(RunFilter(project.id, statuses = setOf(RunStatus.FAILED)), ProjectFilter.All, 0, 10).totalElements).isEqualTo(1)
        assertThat(runs.find(RunFilter(workflowId = definition.id), ProjectFilter.All, 0, 10).totalElements).isEqualTo(1)
        assertThat(runs.find(RunFilter(project.id, statuses = setOf(RunStatus.RUNNING)), ProjectFilter.All, 0, 10).totalElements).isEqualTo(0)
        assertThat(runs.countFinishedSince(RunStatus.FAILED, now.minusSeconds(60), ProjectFilter.All)).isGreaterThanOrEqualTo(1)
        assertThat(runs.existsActiveByWorkflow(definition.id)).isFalse()

        runs.save(loaded.copy(status = RunStatus.RUNNING).replaceArtifactContent(loaded.artifacts.single().id, "edited"))
        assertThat(runs.findById(run.id)!!.artifacts.single().content).isEqualTo("edited")

        logs.append(RunLogLine(run.id, "fetch-issue", now, LogLevel.INFO, "hello"))
        logs.append(RunLogLine(run.id, "specify", now.plusSeconds(1), LogLevel.ERROR, "boom"))
        assertThat(logs.find(run.id, null, null)).hasSize(2)
        assertThat(logs.find(run.id, "specify", null).single().level).isEqualTo(LogLevel.ERROR)
        assertThat(logs.find(run.id, null, now)).hasSize(1)
    }

    @Test
    fun `waiting runs, approvals and CI executions round-trip`() {
        val project = project("RT3")
        val definition = workflows.save(DefaultWorkflows.issueToReviewedMr(project.id, now))
        val waitingCi = runs.save(
            WorkflowRun.create(project, definition, RunTrigger(TriggerSource.MANUAL), RunInput("RT3-1"), now).start(now)
                .withMergeRequest(MergeRequestSnapshot(null, "feature/rt3-1", "main"))
                .waitingForCi(definition.requireNode("ci-tests"), "tests", now),
        )
        val approvalId = ApprovalRequestId.new()
        val waitingHuman = runs.save(
            WorkflowRun.create(project, definition, RunTrigger(TriggerSource.API), RunInput("RT3-2"), now).start(now)
                .waitingForHuman(definition.requireNode("spec-gate"), approvalId, now),
        )
        val waiting = runs.findWaiting(project.id)
        assertThat(waiting.map { it.id }).containsExactlyInAnyOrder(waitingCi.id, waitingHuman.id)
        assertThat(waiting.first { it.id == waitingCi.id }.waitingOn?.signal).isEqualTo("tests")
        assertThat(waiting.first { it.id == waitingHuman.id }.waitingOn).isEqualTo(pl.mos.backoffice.projects.domain.model.WaitingOn(WaitKind.HUMAN_GATE, "spec-gate", approvalId = approvalId))
        assertThat(runs.countWaitingForCi(ProjectFilter.All)).isGreaterThanOrEqualTo(1)
        assertThat(runs.existsActiveByWorkflow(definition.id)).isTrue()

        val artifactId = ArtifactId.new()
        val approval = approvals.save(
            ApprovalRequest(
                id = approvalId, runId = waitingHuman.id, nodeId = "spec-gate", projectId = project.id, projectKey = project.key,
                workflowName = definition.name, issueKey = "RT3-2", question = "ok?",
                context = ApprovalContext("summary", 0.5, listOf(artifactId), "https://gl/mr/1"), createdAt = now,
                approvers = GateApprovers(projectRoles = listOf("owner", "approver"), users = listOf(alice.userId!!), accountable = "owner", forbidSelfApproval = true),
                checklist = listOf(ChecklistItem("tests-green", "Tests green"), ChecklistItem("docs", "Docs", required = false)),
                startedBy = alice,
            ),
        )
        assertThat(approvals.findById(approvalId)).isEqualTo(approval)
        assertThat(approvals.findOpenByRunId(waitingHuman.id)).isEqualTo(approval)
        assertThat(approvals.countOpenByProject(project.id)).isEqualTo(1)
        // run-time assignment (V106, ADR 0028) round-trips and can be cleared
        val bobId = java.util.UUID.randomUUID()
        val assigned = approvals.save(approval.assign(listOf(bobId, bobId), alice, now, " on leave "))
        assertThat(approvals.findById(approvalId)).isEqualTo(assigned)
        assertThat(assigned.assignees).containsExactly(bobId)
        assertThat(assigned.assignment!!.comment).isEqualTo("on leave")
        assertThat(approvals.save(assigned.assign(emptyList(), alice, now)).assignment).isNull()
        assertThat(approvals.findById(approvalId)).isEqualTo(approval)
        val decided = approvals.save(approval.decide(Decision("revise", "tighten", decidedAt = now, checkedItems = listOf("docs"), decidedBy = alice)))
        assertThat(approvals.findById(approval.id)!!.decision).isEqualTo(decided.decision)
        assertThat(approvals.findById(approval.id)!!.decision?.decidedBy).isEqualTo(alice)
        assertThat(approvals.findByStatus(ApprovalStatus.DECIDED, ProjectFilter.All)).contains(decided)
        assertThat(approvals.findOpenByRunId(waitingHuman.id)).isNull()

        val context = CiContext(CiProvider.GITLAB, commitSha = "sha1", mergeRequest = CiMergeRequest(iid = "42"))
        val execution = executions.save(
            CiExecution(CiExecutionId.new(), project.id, project.key, "code-review", CiMode.BLOCKING, context = context, variables = mapOf("force" to "false"), changedFiles = listOf("a.kt"), createdAt = now)
                .running(waitingCi.id, artifactId),
        )
        assertThat(executions.findById(execution.id)).isEqualTo(execution)
        assertThat(executions.findByRunId(waitingCi.id)?.id).isEqualTo(execution.id)
        assertThat(executions.findRecentByKey(execution.idempotencyKey, now.minusSeconds(60))?.id).isEqualTo(execution.id)
        assertThat(executions.findRecentByKey(execution.idempotencyKey, now.plusSeconds(60))).isNull()
        val finished = executions.save(execution.finishedWith(waitingCi.copy(review = ReviewReport(Verdict.APPROVE, 95, "fine")).succeeded(RunOutcome.SUCCESS, now), now))
        assertThat(executions.findById(execution.id)!!.gateResult).isEqualTo(GateResult.PASS)
        assertThat(executions.findById(execution.id)!!.report?.verdict).isEqualTo(Verdict.APPROVE)
        assertThat(executions.find(project.id, ProjectFilter.All, 0, 10).content).containsExactly(finished)
        assertThat(executions.countByProjectSince(project.id, now.minusSeconds(60))).isEqualTo(1)
    }

    @Test
    fun `list queries are restricted to the projects of the filter`() {
        val a = project("RT4")
        val b = project("RT5")
        val defA = workflows.save(DefaultWorkflows.ciCodeReview(a.id, now))
        val defB = workflows.save(DefaultWorkflows.ciCodeReview(b.id, now))
        val runA = runs.save(WorkflowRun.create(a, defA, RunTrigger(TriggerSource.MANUAL), RunInput(), now).start(now))
        val runB = runs.save(WorkflowRun.create(b, defB, RunTrigger(TriggerSource.MANUAL), RunInput(), now).start(now))
        val context = CiContext(CiProvider.GITLAB, commitSha = "sha")
        executions.save(CiExecution(CiExecutionId.new(), a.id, a.key, "code-review", CiMode.BLOCKING, context = context, createdAt = now))
        executions.save(CiExecution(CiExecutionId.new(), b.id, b.key, "code-review", CiMode.BLOCKING, context = context, createdAt = now))
        listOf(a to runA, b to runB).forEach { (project, run) ->
            approvals.save(ApprovalRequest(ApprovalRequestId.new(), run.id, "gate", project.id, project.key, "wf", null, "ok?", createdAt = now))
        }
        val onlyA = ProjectFilter.Only(setOf(a.id.value))
        val none = ProjectFilter.Only(emptySet())

        assertThat(projects.findAll(onlyA).map { it.id }).containsExactly(a.id)
        assertThat(projects.findAll(none)).isEmpty()
        assertThat(projects.findAll(ProjectFilter.All).map { it.id }).contains(a.id, b.id)

        assertThat(runs.find(RunFilter(), onlyA, 0, 50).content.map { it.id }).containsExactly(runA.id)
        assertThat(runs.find(RunFilter(projectId = b.id), onlyA, 0, 50).totalElements).isZero()
        assertThat(runs.find(RunFilter(), none, 0, 50).totalElements).isZero()
        assertThat(runs.findRecent(50, onlyA).map { it.id }).containsExactly(runA.id)
        assertThat(runs.countByStatus(setOf(RunStatus.RUNNING), onlyA)).isEqualTo(1)
        assertThat(runs.countByStatus(setOf(RunStatus.RUNNING), none)).isZero()
        assertThat(runs.countByStatus(setOf(RunStatus.RUNNING), ProjectFilter.Only(setOf(a.id.value, b.id.value)))).isEqualTo(2)

        assertThat(executions.find(null, onlyA, 0, 50).content.map { it.projectId }).containsOnly(a.id)
        assertThat(executions.findRecent(50, onlyA).map { it.projectId }).containsOnly(a.id)
        assertThat(executions.find(null, none, 0, 50).totalElements).isZero()

        assertThat(approvals.findByStatus(ApprovalStatus.OPEN, onlyA).map { it.projectId }).containsOnly(a.id)
        assertThat(approvals.findByStatus(ApprovalStatus.OPEN, onlyA, 1)).hasSize(1)
        assertThat(approvals.countOpen(onlyA)).isEqualTo(1)
        assertThat(approvals.countOpen(none)).isZero()
    }

    @Test
    fun `run listings sort by the asked column with empty values last and keep newest first without a sort (ADR 0046)`() {
        val a = project("RS1")
        val b = project("RS2")
        val both = ProjectFilter.Only(setOf(a.id.value, b.id.value))
        val review = workflows.save(DefaultWorkflows.ciCodeReview(a.id, now))
        val issue = workflows.save(DefaultWorkflows.issueToReviewedMr(b.id, now))
        val bob = Actor(PrincipalKind.USER, UUID.randomUUID(), "bob")
        val r1 = runs.save(WorkflowRun.create(a, review, RunTrigger(TriggerSource.MANUAL), RunInput(), now.minusSeconds(30), startedBy = bob).start(now).copy(cost = Money.usd(0.3)))
        val r2 = runs.save(WorkflowRun.create(b, issue, RunTrigger(TriggerSource.MANUAL), RunInput(), now.minusSeconds(20), startedBy = alice).copy(cost = Money.usd(0.1)))
        val r3 = runs.save(WorkflowRun.create(a, review, RunTrigger(TriggerSource.API), RunInput(), now.minusSeconds(10)).copy(cost = Money.usd(0.2)))
        fun ids(sort: String?) = runs.find(RunFilter(sort = SortSpec.parse(sort, RunFilter.SORT_FIELDS)), both, 0, 10).content.map { it.id }

        assertThat(ids(null)).containsExactly(r3.id, r2.id, r1.id)
        assertThat(ids("createdAt.asc")).containsExactly(r1.id, r2.id, r3.id)
        assertThat(ids("cost.desc")).containsExactly(r1.id, r3.id, r2.id)
        assertThat(ids("status.desc")).containsExactly(r1.id, r3.id, r2.id)
        assertThat(ids("workflow.desc")).containsExactly(r2.id, r3.id, r1.id)
        assertThat(ids("project.asc")).containsExactly(r3.id, r1.id, r2.id)
        // the starter's display name from the JSON column; a run without a starter comes last in both directions
        assertThat(ids("startedBy.asc")).containsExactly(r2.id, r1.id, r3.id)
        assertThat(ids("startedBy.desc")).containsExactly(r1.id, r2.id, r3.id)
        // paging follows the sort and the count ignores it
        val second = runs.find(RunFilter(sort = SortSpec("cost", true)), both, 1, 2)
        assertThat(second.content.map { it.id }).containsExactly(r2.id)
        assertThat(second.totalElements).isEqualTo(3)
    }

    @Test
    fun `CI execution listings sort by the asked column with empty values last and keep newest first without a sort (ADR 0046)`() {
        val a = project("RS3")
        val b = project("RS4")
        val both = ProjectFilter.Only(setOf(a.id.value, b.id.value))
        fun execution(project: Project, entrypoint: String, ref: String?, ageSeconds: Long, cost: Double, status: CiExecutionStatus = CiExecutionStatus.QUEUED) = executions.save(
            CiExecution(
                CiExecutionId.new(), project.id, project.key, entrypoint, CiMode.BLOCKING, status = status,
                context = CiContext(CiProvider.GITLAB, ref = ref, commitSha = "sha"), cost = Money.usd(cost), createdAt = now.minusSeconds(ageSeconds),
            ),
        )
        val e1 = execution(a, "security", "main", 30, 0.5)
        val e2 = execution(b, "code-review", null, 20, 0.1, CiExecutionStatus.RUNNING)
        val e3 = execution(a, "Lint", "feature/a", 10, 0.0)
        fun ids(sort: String?) = executions.find(null, both, 0, 10, SortSpec.parse(sort, CiExecutionQueries.SORT_FIELDS)).content.map { it.id }

        assertThat(ids(null)).containsExactly(e3.id, e2.id, e1.id)
        assertThat(ids("createdAt.asc")).containsExactly(e1.id, e2.id, e3.id)
        assertThat(ids("entrypoint.asc")).containsExactly(e2.id, e3.id, e1.id)
        assertThat(ids("project.desc")).containsExactly(e2.id, e3.id, e1.id)
        assertThat(ids("cost.asc")).containsExactly(e3.id, e2.id, e1.id)
        assertThat(ids("status.desc")).containsExactly(e2.id, e3.id, e1.id)
        // the ref of the stored CI context; an execution without a ref comes last in both directions
        assertThat(ids("ref.asc")).containsExactly(e3.id, e1.id, e2.id)
        assertThat(ids("ref.desc")).containsExactly(e1.id, e3.id, e2.id)
    }
}
