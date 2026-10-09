package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures

class WorkflowGraphTest {

    private val definition = Fixtures.issueWorkflow(Fixtures.project())
    private val graph = WorkflowGraph(definition)

    @Test
    fun `successors are resolved by port case-insensitively`() {
        assertThat(graph.successor("start", "out")).isEqualTo("fetch-issue")
        assertThat(graph.successor("confident", "YES")).isEqualTo("plan")
        assertThat(graph.successor("confident", "no")).isEqualTo("spec-gate")
        assertThat(graph.successor("implement", "failure")).isNull()
        assertThat(graph.successors("ci-tests")).containsExactlyInAnyOrder("code-review", "tests-failed")
    }

    @Test
    fun `every node of the seeded workflow is reachable from START`() {
        assertThat(graph.reachable()).containsExactlyInAnyOrderElementsOf(definition.nodes.map { it.id })
        assertThat(graph.pathExists("implement", "merge-gate")).isTrue()
        assertThat(graph.pathExists("done", "start")).isFalse()
    }

    @Test
    fun `nodes on cycles are the revise and rework loops`() {
        assertThat(graph.nodesOnCycles()).contains("specify", "clarify", "confident", "spec-gate", "implement", "ci-tests", "code-review", "review-ok", "merge-gate")
        assertThat(graph.nodesOnCycles()).doesNotContain("start", "fetch-issue", "done", "transition")
    }

    @Test
    fun `routing through unconnected ports fails or cancels the run`() {
        assertThat(graph.route("implement", "success")).isEqualTo(WorkflowGraph.Route.Next("ci-tests"))
        assertThat(graph.route("implement", "failure")).isInstanceOf(WorkflowGraph.Route.Fail::class.java)
        assertThat(graph.route("spec-gate", "reject")).isInstanceOf(WorkflowGraph.Route.Cancel::class.java)
        assertThat(graph.route("ci-tests", "timeout")).isInstanceOf(WorkflowGraph.Route.Fail::class.java)
        assertThat((graph.route("plan", "weird") as WorkflowGraph.Route.Fail).reason).contains("not connected")
    }
}
