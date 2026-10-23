package pl.mos.backoffice.projects.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money

class WorkflowRunTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val fetch = definition.requireNode("fetch-issue")
    private val specify = definition.requireNode("specify")
    private val gate = definition.requireNode("spec-gate")
    private val ciWait = definition.requireNode("ci-tests")

    private fun running() = Fixtures.runningRun(project, definition)

    @Test
    fun `run starts pending then running and pins the workflow version`() {
        val run = WorkflowRun.create(project, definition, RunTrigger(TriggerSource.API), RunInput("SHOP-1"), now)
        assertThat(run.status).isEqualTo(RunStatus.PENDING)
        assertThat(run.workflowVersion).isEqualTo(definition.version)
        assertThat(run.start(now).status).isEqualTo(RunStatus.RUNNING)
        assertThatThrownBy { run.start(now).start(now) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `only one node runs at a time and restarting the same node re-attaches`() {
        val run = running().nodeStarted(fetch, now)
        assertThat(run.runningNode()?.nodeId).isEqualTo("fetch-issue")
        assertThatThrownBy { run.nodeStarted(specify, now) }.isInstanceOf(InvalidStateException::class.java)
        assertThat(run.nodeStarted(fetch, now).nodes).hasSize(1)
    }

    @Test
    fun `finished nodes record the port taken, attempts count per node and cost rolls up`() {
        var run = running().nodeStarted(fetch, now).nodeFinished("fetch-issue", "success", now, cost = Money.usd(0.5))
        assertThat(run.nodes.single().port).isEqualTo("success")
        run = run.nodeStarted(specify, now).nodeFailed("specify", "boom", now, port = "failure", cost = Money.usd(0.25))
        run = run.nodeStarted(specify, now)
        assertThat(run.nodes.last().attempt).isEqualTo(2)
        assertThat(run.visits("specify")).isEqualTo(2)
        assertThat(run.cost).isEqualTo(Money.usd(0.75))
        // ADR 0042: without an API-equivalent cost a step counts its cost; a subscription step adds only to apiCost
        assertThat(run.apiCost).isEqualTo(Money.usd(0.75))
        run = run.nodeFinished("specify", "success", now, cost = Money.ZERO, apiCost = Money.usd(1.0))
        assertThat(run.cost).isEqualTo(Money.usd(0.75))
        assertThat(run.apiCost).isEqualTo(Money.usd(1.75))
    }

    @Test
    fun `maxVisits is enforced per node`() {
        val loop = WorkflowNode.Task("impl", "Impl", maxVisits = 2, task = TaskBinding("implement-opencode"))
        var run = running()
        repeat(2) { run = run.nodeStarted(loop, now).nodeFinished("impl", "success", now) }
        assertThatThrownBy { run.nodeStarted(loop, now) }.isInstanceOf(InvalidStateException::class.java).hasMessageContaining("maxVisits")
    }

    @Test
    fun `waiting for human links the approval and a decision resumes through the option port`() {
        val approvalId = ApprovalRequestId.new()
        val waiting = running().waitingForHuman(gate, approvalId, now)
        assertThat(waiting.status).isEqualTo(RunStatus.WAITING)
        assertThat(waiting.waitingOn).isEqualTo(WaitingOn(WaitKind.HUMAN_GATE, "spec-gate", approvalId = approvalId))
        assertThat(waiting.openApprovalId).isEqualTo(approvalId)
        assertThat(waiting.nodes.single().status).isEqualTo(NodeExecutionStatus.WAITING)
        assertThatThrownBy { waiting.resumedWithSignal(CiSignal("tests", CiSignalStatus.SUCCESS, receivedAt = now), now) }.isInstanceOf(InvalidStateException::class.java)

        val resumed = waiting.resumedWithDecision(Decision("REVISE", "tighten", decidedAt = now), now)
        assertThat(resumed.status).isEqualTo(RunStatus.RUNNING)
        assertThat(resumed.waitingOn).isNull()
        assertThat(resumed.nodes.single().port).isEqualTo("revise")
        assertThat(resumed.nodes.single().status).isEqualTo(NodeExecutionStatus.SUCCEEDED)
    }

    @Test
    fun `waiting for CI resumes through success or fail and rejects a foreign signal`() {
        val waiting = running().waitingForCi(ciWait, "tests", now)
        assertThat(waiting.waitingOn).isEqualTo(WaitingOn(WaitKind.WAIT_FOR_CI, "ci-tests", signal = "tests"))
        assertThatThrownBy { waiting.resumedWithSignal(CiSignal("lint", CiSignalStatus.SUCCESS, receivedAt = now), now) }.isInstanceOf(InvalidStateException::class.java)
        val failed = waiting.resumedWithSignal(CiSignal("tests", CiSignalStatus.FAIL, "3 tests failed", receivedAt = now), now)
        assertThat(failed.nodes.single().port).isEqualTo("fail")
        assertThat(failed.lastSignal?.message).isEqualTo("3 tests failed")
        assertThat(waiting.resumedByTimeout(now).nodes.single().port).isEqualTo("timeout")
    }

    @Test
    fun `terminal transitions set the outcome and freeze the run`() {
        val succeeded = running().nodeStarted(fetch, now).succeeded(RunOutcome.FAILURE, now)
        assertThat(succeeded.status).isEqualTo(RunStatus.SUCCEEDED)
        assertThat(succeeded.outcome).isEqualTo(RunOutcome.FAILURE)
        assertThat(succeeded.nodes.single().status).isEqualTo(NodeExecutionStatus.SKIPPED)
        assertThatThrownBy { succeeded.nodeStarted(fetch, now) }.isInstanceOf(InvalidStateException::class.java)

        val failed = running().nodeStarted(fetch, now).failed(RunFailure("fetch-issue", "jira down"), now)
        assertThat(failed.outcome).isEqualTo(RunOutcome.FAILURE)
        assertThat(failed.nodes.single().status).isEqualTo(NodeExecutionStatus.FAILED)
        assertThat(running().cancelled(now).status).isEqualTo(RunStatus.CANCELLED)
    }

    @Test
    fun `retry re-opens a failed run from the failing node`() {
        val failed = running().nodeStarted(fetch, now).nodeFinished("fetch-issue", "success", now)
            .nodeStarted(specify, now).nodeFailed("specify", "runner exploded", now)
            .failed(RunFailure("specify", "runner exploded"), now)
        assertThatThrownBy { running().retry(now) }.isInstanceOf(InvalidStateException::class.java)
        val retried = failed.retry(now)
        assertThat(retried.status).isEqualTo(RunStatus.RUNNING)
        assertThat(retried.failure).isNull()
        assertThat(retried.outcome).isNull()
        assertThat(retried.retryNodeId()).isEqualTo("specify")
        assertThat(retried.nodeStarted(specify, now).nodes.last().attempt).isEqualTo(2)
        assertThat(retried.extraVisits).isEmpty() // attempts left: nothing granted
    }

    @Test
    fun `a person retrying a node that used up maxVisits grants it that many attempts more`() {
        var run = running()
        repeat(specify.maxVisits) { run = run.nodeStarted(specify, now).nodeFailed("specify", "agent stopped", now) }
        assertThatThrownBy { run.nodeStarted(specify, now) }.hasMessageContaining("maxVisits")
        val retried = run.failed(RunFailure("specify", "node specify exceeded maxVisits=${specify.maxVisits}"), now).retry(now)
        assertThat(retried.extraVisits).containsEntry("specify", specify.maxVisits)
        assertThat(retried.visitLimit(specify)).isEqualTo(2 * specify.maxVisits)
        assertThat(retried.nodeStarted(specify, now).nodes.last().attempt).isEqualTo(specify.maxVisits + 1)
    }

    @Test
    fun `artefacts are attached, replaced by name lookup and size-limited`() {
        val artifact = Artifact(ArtifactId.new(), "specify", "spec", ArtifactKind.SPEC, content = "# Spec", createdAt = now)
        val run = running().addArtifact(artifact).replaceArtifactContent(artifact.id, "# Better spec")
        assertThat(run.artifactNamed("spec")?.content).isEqualTo("# Better spec")
        val huge = artifact.copy(id = ArtifactId.new(), content = "x".repeat(Artifact.MAX_CONTENT_BYTES + 1))
        assertThatThrownBy { run.addArtifact(huge) }.isInstanceOf(InvalidStateException::class.java)
    }
}
