package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_COMMENT
import pl.mos.backoffice.projects.domain.model.ConnectionMode
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.StepConnectionConfig
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WriteIdentity
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.SelectionStatus
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.testing.RecordingAuditPort

/**
 * ADR 0035: steps acting as a person. A personal connection is used only with that person's action (their approval of
 * the write, or the run they started); never by the intake watcher, never in another user's run without their approval.
 */
class ActingUserConnectionTest {

    private val connections = ConnectionFakes.InMemoryConnections()
    private val trackers = ConnectionFakes.FakeTrackers()
    private val access = JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), trackers)
    private val audit = RecordingAuditPort()
    private val notify = NotifyTaskExecutor(access, TemplateRenderer(), TemplateModelBuilder(), audit)
    private val json = object : pl.mos.backoffice.projects.application.port.out.JsonCodec {
        override fun toJson(value: Any?): String = value.toString()
        override fun parseObject(json: String): Map<String, Any?> = emptyMap()
    }
    private val fetch = FetchIssueTaskExecutor(access, json)

    private val bob = Fixtures.bob // started the run
    private val alice = Fixtures.alice // approves
    private val bobsJira = ConnectionFakes.personal("Bob's Jira", ConnectionFakes.WRITE, bob.userId!!, "Bob Other")
    private val alicesJira = ConnectionFakes.personal("Alice's Jira", setOf(ISSUE_COMMENT), alice.userId!!, "Alice Test")
    private val bot = ConnectionFakes.connection("bot", ConnectionFakes.WRITE)

    private fun project(): Project = Fixtures.project().copy(jiraConnections = ProjectJiraConnections(bot.id))

    private fun task(executor: TaskExecutor, config: Map<String, Any?>) =
        ResolvedTask("t", "t", executor, null, TaskPrompt(null, null), emptyList(), config, null, null, emptyList(), emptyList(), emptyList(), listOf("t"), listOf("success", "failure"))

    private fun context(startedBy: Actor?, state: Map<String, Any?> = emptyMap(), logs: MutableList<String> = mutableListOf()): NodeContext {
        val project = project()
        val node = WorkflowNode.Task("step", "Step", task = TaskBinding("t"))
        val run = Fixtures.runningRun(project, startedBy = startedBy).nodeStarted(node, Fixtures.now)
        return NodeContext(run, project, node, run.nodes.last(), state, log = { logs += it })
    }

    private val acting = mapOf<String, Any?>("jiraConnection" to "ACTING_USER")

    @Test
    fun `a read step acting as the person who started the run reads through their personal connection`() {
        listOf(bot, bobsJira, alicesJira).forEach(connections::save)
        val logs = mutableListOf<String>()

        fetch.execute(context(bob, logs = logs), task(TaskExecutor.FETCH_ISSUE, acting))

        assertThat(trackers.opened.last()).isEqualTo(bobsJira)
        assertThat(logs).anyMatch { it.contains("Jira connection: Bob's Jira (personal connection of Bob Other, they started the run)") }
    }

    @Test
    fun `runs nobody started (intake watcher, CI) never use a personal connection`() {
        listOf(bot, bobsJira).forEach(connections::save)
        val watcher = Actor(PrincipalKind.SYSTEM, null, "Jira intake watcher")

        assertThatThrownBy { fetch.execute(context(watcher), task(TaskExecutor.FETCH_ISSUE, acting)) }
            .isInstanceOf(IntegrationException::class.java).hasMessageContaining("not started by a person")
        val ci = Actor(PrincipalKind.CI_TOKEN, null, "ci:SHOP")
        assertThatThrownBy { fetch.execute(context(ci), task(TaskExecutor.FETCH_ISSUE, acting)) }.isInstanceOf(IntegrationException::class.java)
        assertThat(trackers.opened).doesNotContain(bobsJira)

        // fallbackToSystem: the watcher's run reads through the system connection instead
        fetch.execute(context(watcher), task(TaskExecutor.FETCH_ISSUE, acting + ("fallbackToSystem" to true)))
        assertThat(trackers.opened.last()).isEqualTo(bot)
    }

    @Test
    fun `the person's own connection only, never another user's`() {
        listOf(bot, alicesJira).forEach(connections::save)
        // Bob started the run, only Alice has a personal connection: Bob's run does not borrow it
        val choice = access.resolve(project(), setOf(ISSUE_COMMENT), StepConnectionConfig.of(acting), "SHOP", ActingUser.startedBy(context(bob).run))
        assertThat(choice.status).isEqualTo(SelectionStatus.NO_PERSONAL_CONNECTION)
        assertThat(choice.connection).isNull()
    }

    @Test
    fun `SYSTEM steps never use a personal connection unless the approver explicitly chooses it on a step that allows it`() {
        listOf(bot, bobsJira).forEach(connections::save)
        val starter = ActingUser.startedBy(context(bob).run)
        val system = access.resolve(project(), setOf(ISSUE_COMMENT), StepConnectionConfig(), "SHOP", starter)
        assertThat(system.connection).isEqualTo(bot)
        val refused = access.resolve(project(), setOf(ISSUE_COMMENT), StepConnectionConfig(), "SHOP", starter, WriteIdentity.PERSONAL)
        assertThat(refused.connection).isNull()
        val allowed = access.resolve(project(), setOf(ISSUE_COMMENT), StepConnectionConfig(allowActingUser = true), "SHOP", starter, WriteIdentity.PERSONAL)
        assertThat(allowed.connection).isEqualTo(bobsJira)
    }

    @Test
    fun `the intake watcher never reads through a personal connection, even when it is the only reader`() {
        connections.save(ConnectionFakes.personal("Bob reader", READ, bob.userId!!, "Bob Other"))
        val (choice, client) = access.openReader(Fixtures.project())
        assertThat(client).isNull()
        assertThat(choice.status).isEqualTo(SelectionStatus.NONE)
    }

    @Test
    fun `an ACTING_USER write waits for approval and is written through the approver's own connection`() {
        listOf(bot, bobsJira, alicesJira).forEach(connections::save)
        val logs = mutableListOf<String>()
        val config = acting + ("messageTemplate" to "Done")
        val write = notify.execute(context(bob, logs = logs), task(TaskExecutor.NOTIFY, config)).pendingWrite!!

        assertThat(write.connectionMode).isEqualTo(ConnectionMode.ACTING_USER)
        assertThat(write.personalAllowed).isTrue()
        assertThat(write.systemAllowed).isFalse() // no fallbackToSystem
        assertThat(write.defaultIdentity).isEqualTo(WriteIdentity.PERSONAL)
        assertThat(trackers.opened).isEmpty() // nothing opened before the approval

        val decision = mapOf("option" to "approve", "decidedBy" to "Alice Test", "decidedByUserId" to alice.userId.toString(), "writeAs" to "PERSONAL")
        val result = notify.commit(context(bob, mapOf(RunStateKeys.DECISION to decision)), task(TaskExecutor.NOTIFY, config), write)

        assertThat(result.failed).isFalse()
        assertThat(trackers.opened.single()).isEqualTo(alicesJira) // the approver's, not the starter's
        val entry = audit.entries.single()
        assertThat(entry.details).containsEntry("identity", "PERSONAL").containsEntry("via", "personal connection of Alice Test (\"Alice's Jira\")")
            .containsEntry("approvedBy", "Alice Test").containsEntry("connection", "Alice's Jira")
    }

    @Test
    fun `an approver without a personal connection writes nothing, and the system identity is refused without fallback`() {
        listOf(bot, bobsJira).forEach(connections::save)
        val config = acting + ("messageTemplate" to "Done")
        val write = notify.execute(context(bob), task(TaskExecutor.NOTIFY, config)).pendingWrite!!

        val personal = mapOf("option" to "approve", "decidedBy" to "Alice Test", "decidedByUserId" to alice.userId.toString())
        val failed = notify.commit(context(bob, mapOf(RunStateKeys.DECISION to personal)), task(TaskExecutor.NOTIFY, config), write)
        assertThat(failed.failed).isTrue()
        assertThat(failed.error).contains("nothing was written")

        val system = personal + ("writeAs" to "SYSTEM")
        val refused = notify.commit(context(bob, mapOf(RunStateKeys.DECISION to system)), task(TaskExecutor.NOTIFY, config), write)
        assertThat(refused.failed).isTrue()
        assertThat(trackers.comments).isEmpty()
    }

    @Test
    fun `with fallbackToSystem the approver may write through the system connection`() {
        listOf(bot).forEach(connections::save)
        val config = acting + ("messageTemplate" to "Done") + ("fallbackToSystem" to true)
        val write = notify.execute(context(bob), task(TaskExecutor.NOTIFY, config)).pendingWrite!!
        assertThat(write.systemAllowed).isTrue()
        assertThat(write.connectionName).isEqualTo("bot")

        val decision = mapOf("option" to "approve", "decidedBy" to "Alice Test", "decidedByUserId" to alice.userId.toString(), "writeAs" to "SYSTEM")
        notify.commit(context(bob, mapOf(RunStateKeys.DECISION to decision)), task(TaskExecutor.NOTIFY, config), write)
        assertThat(trackers.opened.single()).isEqualTo(bot)
        assertThat(audit.entries.single().details).containsEntry("identity", "SYSTEM")
    }

    @Test
    fun `a write without approval acting as a person uses the starter's connection (their run start is the consent)`() {
        listOf(bot, bobsJira).forEach(connections::save)
        notify.execute(context(bob), task(TaskExecutor.NOTIFY, acting + ("messageTemplate" to "x") + ("approval" to "NOT_REQUIRED")))
        assertThat(trackers.opened.single()).isEqualTo(bobsJira)
        assertThat(audit.entries.single().details).containsEntry("approval", "NOT_REQUIRED").containsEntry("identity", "PERSONAL")
    }
}
