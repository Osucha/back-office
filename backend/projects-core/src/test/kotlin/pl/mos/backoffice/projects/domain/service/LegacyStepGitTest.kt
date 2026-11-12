package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.application.port.`in`.TaskOverridesJson
import pl.mos.backoffice.projects.application.port.`in`.TaskSectionJson
import pl.mos.backoffice.projects.application.port.`in`.WorkflowDocumentJson
import pl.mos.backoffice.projects.application.port.`in`.WorkflowEdgeJson
import pl.mos.backoffice.projects.application.port.`in`.WorkflowJson
import pl.mos.backoffice.projects.application.port.`in`.WorkflowNodeJson
import pl.mos.backoffice.projects.application.port.`in`.PointJson
import pl.mos.backoffice.projects.application.port.`in`.WorkflowSourceJson
import pl.mos.backoffice.projects.domain.model.ActionType
import pl.mos.backoffice.projects.domain.model.Point
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.shared.tasks.TaskOverrides

/** ADR 0063 read-time compatibility: imports and MCP clients that still send the former git keys of coding steps. */
class LegacyStepGitTest {

    private fun step(id: String, key: String, config: Map<String, Any?>, x: Double = 100.0) =
        WorkflowNode.Task(id, id, Point(x, 40.0), maxVisits = 4, task = TaskBinding(key, TaskOverrides(agentKey = "claude-code", config = config)))

    private val edges = listOf(
        WorkflowEdge("e1", "start", "out", "impl"),
        WorkflowEdge("e2", "impl", "success", "review"),
        WorkflowEdge("e3", "impl", "failure", "failed"),
    )

    @Test
    fun `a step with push becomes the step and a delivery carrying its git settings`() {
        val nodes = listOf(
            WorkflowNode.Start("start"),
            step(
                "impl", "agent-task",
                mapOf(
                    "push" to true, "branchTemplate" to "bo/{{run.short}}", "mergeRequest" to true, "commitSubjectTemplate" to "{{issue.key}}",
                    "sessionRole" to "implementer", "repositories" to listOf(mapOf("key" to "docs", "access" to "PUSH_MR"), mapOf("key" to "std", "library" to true)),
                    "setupCommand" to "npm ci",
                ),
            ),
        )
        val result = LegacyStepGit.normalise(nodes, edges, emptyList(), "make deps")
        assertThat(result.nodes.map { it.id }).containsExactly("start", "impl", "impl-deliver")
        val impl = result.nodes[1] as WorkflowNode.Task
        assertThat(impl.task.overrides.config).isEqualTo(mapOf("sessionRole" to "implementer"))
        assertThat(impl.task.overrides.agentKey).isEqualTo("claude-code")
        val deliver = result.nodes[2] as WorkflowNode.Action
        assertThat(deliver.action.type).isEqualTo(ActionType.DELIVER_CHANGES)
        assertThat(deliver.action.config).isEqualTo(
            mapOf("branchTemplate" to "bo/{{run.short}}", "commitSubjectTemplate" to "{{issue.key}}", "mergeRequest" to true, "repositories" to listOf("docs")),
        )
        assertThat(deliver.position).isEqualTo(Point(268.0, 200.0))
        assertThat(deliver.maxVisits).isEqualTo(4)
        assertThat(result.edges.map { listOf(it.id, it.from, it.fromPort, it.to) }).containsExactly(
            listOf("e1", "start", "out", "impl"),
            listOf("e2", "impl-deliver", "success", "review"),
            listOf("e3", "impl", "failure", "failed"),
            listOf("impl-deliver-in", "impl", "success", "impl-deliver"),
            listOf("impl-deliver-failure", "impl-deliver", "failure", "failed"),
        )
        assertThat(result.sources).containsExactly(WorkflowSource("docs"), WorkflowSource("std", library = true))
        assertThat(result.setupCommand).isEqualTo("make deps && npm ci")
    }

    @Test
    fun `Spec Kit steps name the replacements, steps without push get no delivery, the rest stays`() {
        val nodes = listOf(
            WorkflowNode.Start("start"),
            step("spec", "spec-kit-specify", emptyMap()),
            step("plan", "my-template", mapOf("phase" to "PLAN", "setupCommand" to "make deps", "push" to "false")),
            step("check", "my-template", mapOf("phase" to "ANALYZE", "collectArtifacts" to listOf("x.md"))),
        )
        val result = LegacyStepGit.normalise(nodes, emptyList(), listOf(WorkflowSource("app")), "make deps")
        assertThat(result.nodes.map { (it as? WorkflowNode.Task)?.task?.templateKey }).containsExactly(null, "write-spec", "write-plan", "my-template")
        assertThat((result.nodes[3] as WorkflowNode.Task).task.overrides.config).isEqualTo(mapOf("collectArtifacts" to listOf("x.md")))
        assertThat(result.sources).containsExactly(WorkflowSource("app"))
        assertThat(result.setupCommand).isEqualTo("make deps")
        assertThat(result.edges).isEmpty()
    }

    @Test
    fun `a Spec Kit step that pushed delivers without a merge request, a taken delivery id gets a suffix`() {
        val nodes = listOf(step("impl", "x", mapOf("push" to "true", "phase" to "SPECIFY")), WorkflowNode.Note("impl-deliver"))
        val result = LegacyStepGit.normalise(nodes, emptyList(), emptyList(), null)
        val deliver = result.nodes.filterIsInstance<WorkflowNode.Action>().single()
        assertThat(deliver.id).isEqualTo("impl-deliver-2")
        assertThat(deliver.action.config).isEqualTo(mapOf("mergeRequest" to false))
        assertThat(result.edges.single().let { it.from to it.to }).isEqualTo("impl" to "impl-deliver-2")
    }

    @Test
    fun `workflows without legacy keys are returned as they are`() {
        val nodes = listOf(WorkflowNode.Start("start"), step("impl", "implement-opencode", mapOf("verifyCommand" to "make test")))
        assertThat(LegacyStepGit.needed(nodes)).isFalse()
        val result = LegacyStepGit.normalise(nodes, edges, emptyList(), null)
        assertThat(result.nodes).isSameAs(nodes)
        assertThat(result.edges).isSameAs(edges)
    }

    @Test
    fun `MCP and REST documents with push read as a delivery, sources round-trip`() {
        val document = WorkflowDocumentJson(
            name = "w",
            nodes = listOf(
                WorkflowNodeJson("start", "START", "Start"),
                WorkflowNodeJson("impl", "TASK", "Impl", PointJson(10.0, 10.0), task = TaskSectionJson("implement-opencode", TaskOverridesJson(config = mapOf("push" to true)))),
            ),
            edges = listOf(WorkflowEdgeJson("e1", "start", "out", "impl")),
            sources = listOf(WorkflowSourceJson("app"), WorkflowSourceJson(" "), WorkflowSourceJson("std", true), WorkflowSourceJson("app")),
        )
        val command = WorkflowJson.toCommand(document)
        assertThat(command.nodes.map { it.kind.name }).containsExactly("START", "TASK", "ACTION")
        assertThat(command.sources).containsExactly(WorkflowSource("app"), WorkflowSource("std", library = true))
        val definition = pl.mos.backoffice.projects.domain.model.WorkflowDefinition(
            id = pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId.new(), projectId = pl.mos.backoffice.projects.domain.model.ProjectId.new(),
            name = "w", nodes = command.nodes, edges = command.edges, sources = command.sources,
            createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        assertThat(WorkflowJson.toDocument(definition).sources).containsExactly(WorkflowSourceJson("app"), WorkflowSourceJson("std", true))
        assertThat(WorkflowJson.normalised(definition)).isSameAs(definition)
        val legacy = definition.copy(nodes = listOf(WorkflowNode.Start("start"), step("impl", "implement-opencode", mapOf("push" to true))), edges = emptyList())
        assertThat(WorkflowJson.normalised(legacy).nodes.map { it.id }).containsExactly("start", "impl", "impl-deliver")
    }
}
