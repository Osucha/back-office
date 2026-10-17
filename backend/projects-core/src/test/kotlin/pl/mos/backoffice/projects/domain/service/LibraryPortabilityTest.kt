package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.GateSpec
import pl.mos.backoffice.projects.domain.model.Point
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.shared.tasks.TaskOverrides
import java.util.UUID

class LibraryPortabilityTest {

    private val user = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val connection = "6f1c1d0e-0000-4000-8000-000000000001"

    private fun task(config: Map<String, Any?>, x: Double = 0.0) =
        WorkflowNode.Task("notify", "Notify", Point(x, 0.0), task = TaskBinding("notify-jira", TaskOverrides(promptAppend = "be brief", config = config)))

    private fun gate(users: List<UUID>, x: Double = 0.0) =
        WorkflowNode.HumanGate("gate", "Approve", Point(x, 0.0), gate = GateSpec("Ok?", approvers = GateApprovers(projectRoles = listOf("owner"), users = users, accountable = "owner")))

    private val localConfig = mapOf(
        "messageTemplate" to "Run {{run.short}}",
        "repositories" to listOf(mapOf("key" to "api", "access" to "READ")),
        "jiraConnection" to connection,
        "approvers" to mapOf("projectRoles" to listOf("owner"), "users" to listOf(user.toString())),
    )

    @Test
    fun `publishing strips repositories, a pinned connection and named approver users but keeps the rest`() {
        val (t, g) = LibraryPortability.portable(listOf(task(localConfig), gate(listOf(user))))
        val config = (t as WorkflowNode.Task).task.overrides.config
        assertThat(config).containsOnlyKeys("messageTemplate", "approvers")
        assertThat(config["approvers"]).isEqualTo(mapOf("projectRoles" to listOf("owner"), "users" to emptyList<Any>()))
        assertThat(t.task.overrides.promptAppend).isEqualTo("be brief")
        assertThat((g as WorkflowNode.HumanGate).gate.approvers).isEqualTo(GateApprovers(listOf("owner"), emptyList(), "owner"))
    }

    @Test
    fun `library sources of a step are shared and stay in the published graph and in updates (ADR 0043)`() {
        val shared = mapOf("key" to "standards", "access" to "READ", "library" to true)
        val own = mapOf("key" to "api", "access" to "READ")
        val published = (LibraryPortability.portable(listOf(task(mapOf("repositories" to listOf(own, shared))))).single() as WorkflowNode.Task).task.overrides.config
        assertThat(published["repositories"]).isEqualTo(listOf(shared))

        val updated = LibraryPortability.withLocalSettings(listOf(task(published)), listOf(task(mapOf("repositories" to listOf(own)))))
        assertThat((updated.single() as WorkflowNode.Task).task.overrides.config["repositories"]).isEqualTo(listOf(own, shared))
    }

    @Test
    fun `portable connection modes stay in the published graph`() {
        listOf("SYSTEM", "acting_user").forEach { mode ->
            val t = LibraryPortability.portable(listOf(task(mapOf("jiraConnection" to mode)))).single() as WorkflowNode.Task
            assertThat(t.task.overrides.config).containsEntry("jiraConnection", mode)
        }
    }

    @Test
    fun `a new library version gets the local settings of nodes with the same id and kind`() {
        val library = listOf(task(mapOf("messageTemplate" to "v2", "approvers" to mapOf("projectRoles" to listOf("lead"), "users" to emptyList<Any>()))), gate(emptyList()))
        val local = listOf(task(localConfig), gate(listOf(user)))
        val (t, g) = LibraryPortability.withLocalSettings(library, local)
        val config = (t as WorkflowNode.Task).task.overrides.config
        assertThat(config["messageTemplate"]).isEqualTo("v2")
        assertThat(config["repositories"]).isEqualTo(localConfig["repositories"])
        assertThat(config["jiraConnection"]).isEqualTo(connection)
        assertThat(config["approvers"]).isEqualTo(mapOf("projectRoles" to listOf("lead"), "users" to listOf(user.toString())))
        assertThat((g as WorkflowNode.HumanGate).gate.approvers.users).containsExactly(user)
    }

    @Test
    fun `nodes without a local counterpart of the same kind stay as published`() {
        val library = listOf(task(mapOf("messageTemplate" to "v2")))
        val other = WorkflowNode.End("notify", "Done", end = EndSpec(RunOutcome.SUCCESS))
        assertThat(LibraryPortability.withLocalSettings(library, listOf(other))).isEqualTo(library)
    }

    @Test
    fun `graphs are equal regardless of positions, local settings and edge ids`() {
        val edges = listOf(WorkflowEdge("e1", "gate", "approve", "notify"))
        val published = LibraryPortability.portable(listOf(task(localConfig), gate(listOf(user))))
        val project = listOf(task(localConfig, x = 300.0), gate(listOf(user), x = 40.0))
        assertThat(LibraryPortability.sameGraph(project, listOf(WorkflowEdge("other-id", "gate", "APPROVE", "notify")), published, edges)).isTrue()
    }

    @Test
    fun `a changed prompt, config value or edge makes the graph differ`() {
        val edges = listOf(WorkflowEdge("e1", "gate", "approve", "notify"))
        val published = listOf(task(mapOf("messageTemplate" to "a")), gate(emptyList()))
        assertThat(LibraryPortability.sameGraph(listOf(task(mapOf("messageTemplate" to "b")), gate(emptyList())), edges, published, edges)).isFalse()
        assertThat(LibraryPortability.sameGraph(published, edges + WorkflowEdge("e2", "gate", "revise", "notify"), published, edges)).isFalse()
        assertThat(LibraryPortability.sameGraph(published.take(1), edges, published, edges)).isFalse()
    }
}
