package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.ConnectionFakes.WRITE
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.tasks.TaskExecutor

/** ADR 0030 at run time: the step gets a client limited to what it declared, and the run log says which connection. */
class JiraConnectionAccessTest {

    private val project = Fixtures.project()
    private val connections = ConnectionFakes.InMemoryConnections()
    private val trackers = ConnectionFakes.FakeTrackers()
    private val access = JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), trackers)
    private val infos = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    private fun context(): NodeContext {
        val node = WorkflowNode.Start("start")
        val run = Fixtures.runningRun(project).nodeStarted(node, Fixtures.now)
        return NodeContext(run, project, node, run.nodes.last(), emptyMap(), { warnings += it }) { infos += it }
    }

    @Test
    fun `a fetch step through a writer connection reads, warns, and cannot write`() {
        connections.save(ConnectionFakes.connection("writer", WRITE))
        val tracker = access.open(context(), TaskExecutor.FETCH_ISSUE, emptyMap(), "SHOP-1")

        assertThat(tracker.fetch("SHOP-1").key).isEqualTo("SHOP-1")
        assertThat(infos).anyMatch { it.contains("Jira connection: writer (platform)") }
        assertThat(warnings.single()).contains("more rights than needed")
        assertThatThrownBy { tracker.comment("SHOP-1", "hi") }.isInstanceOf(IntegrationException::class.java).hasMessageContaining("may not use ISSUE_COMMENT")
        assertThatThrownBy { tracker.transition("SHOP-1", "Done") }.isInstanceOf(IntegrationException::class.java)
        assertThat(trackers.calls).containsExactly("fetch SHOP-1")
    }

    @Test
    fun `the allow-list of the connection is enforced per issue`() {
        connections.save(ConnectionFakes.connection("shop reader", READ, allowed = listOf("SHOP")))
        val tracker = access.open(context(), TaskExecutor.FETCH_ISSUE, emptyMap(), "SHOP-1")

        assertThat(warnings).isEmpty()
        assertThatThrownBy { tracker.fetch("OPS-7") }.isInstanceOf(IntegrationException::class.java).hasMessageContaining("limited to the Jira projects SHOP")
        // no connection may touch OPS issues at all
        assertThatThrownBy { access.open(context(), TaskExecutor.FETCH_ISSUE, emptyMap(), "OPS-7") }
            .isInstanceOf(IntegrationException::class.java).hasMessageContaining("no Jira connection").hasMessageContaining("OPS")
    }

    @Test
    fun `no connection fails the step with a pointer to the setup guide`() {
        connections.save(ConnectionFakes.connection("reader", READ))
        assertThatThrownBy { access.open(context(), TaskExecutor.NOTIFY, emptyMap(), "SHOP-1") }
            .isInstanceOf(IntegrationException::class.java)
            .hasMessageContaining("[ISSUE_COMMENT]")
            .hasMessageContaining("integrations-jira#jira-connections")
        assertThat(trackers.opened).isEmpty()
    }

    @Test
    fun `a pinned connection is used and an invalid pin is a validation error`() {
        val writer = connections.save(ConnectionFakes.connection("writer", WRITE))
        connections.save(ConnectionFakes.connection("reader", READ))
        access.open(context(), TaskExecutor.FETCH_ISSUE, mapOf(JiraConnectionAccess.CONFIG_KEY to writer.id.toString()), "SHOP-1")
        assertThat(trackers.opened.single().id).isEqualTo(writer.id)
        assertThat(infos).anyMatch { it.contains("pinned") }

        assertThatThrownBy { JiraConnectionAccess.pinnedConnection(mapOf(JiraConnectionAccess.CONFIG_KEY to "not-a-uuid")) }.isInstanceOf(ValidationException::class.java)
        assertThat(JiraConnectionAccess.pinnedConnection(mapOf(JiraConnectionAccess.CONFIG_KEY to " "))).isNull()
    }

    @Test
    fun `searches are restricted to the allowed projects`() {
        assertThat(ScopedIssueTracker.restrictJql("status = Open ORDER BY created DESC", listOf("SHOP", "OPS")))
            .isEqualTo("project in (SHOP, OPS) AND (status = Open) ORDER BY created DESC")
        assertThat(ScopedIssueTracker.restrictJql("", listOf("SHOP"))).isEqualTo("project in (SHOP)")
        assertThat(ScopedIssueTracker.restrictJql("order by rank", listOf("SHOP"))).isEqualTo("project in (SHOP) order by rank")
        assertThat(ScopedIssueTracker.restrictJql("status = Open", emptyList())).isEqualTo("status = Open")

        connections.save(ConnectionFakes.connection("shop reader", setOf(ConnectionCapability.ISSUE_READ), allowed = listOf("SHOP")))
        access.open(context(), TaskExecutor.FETCH_ISSUE, emptyMap(), "SHOP-1").search("labels = ai", 10)
        assertThat(trackers.calls).containsExactly("search project in (SHOP) AND (labels = ai)")
    }
}
