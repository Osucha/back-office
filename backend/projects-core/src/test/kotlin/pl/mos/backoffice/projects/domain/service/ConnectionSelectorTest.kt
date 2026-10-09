package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes.ALL
import pl.mos.backoffice.projects.ConnectionFakes.READ
import pl.mos.backoffice.projects.ConnectionFakes.WRITE
import pl.mos.backoffice.projects.ConnectionFakes.connection
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_COMMENT
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_READ
import pl.mos.backoffice.projects.domain.model.ConnectionCapability.ISSUE_TRANSITION
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionVerification
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.projects.domain.model.ConnectionCapability

/** ADR 0030: least-privilege selection of the connection a Jira step uses. */
class ConnectionSelectorTest {

    private val selector = ConnectionSelector()
    private val project = ProjectId.new()

    @Test
    fun `the connection with the fewest extra capabilities wins`() {
        val writer = connection("writer", WRITE)
        val reader = connection("reader", READ)
        val choice = selector.select(setOf(ISSUE_READ), emptyList(), listOf(writer, reader), "SHOP")

        assertThat(choice.status).isEqualTo(SelectionStatus.OK)
        assertThat(choice.connection).isEqualTo(reader)
        assertThat(choice.excess).isEmpty()
    }

    @Test
    fun `an over-privileged connection is allowed but reported`() {
        val writer = connection("writer", WRITE)
        val choice = selector.select(setOf(ISSUE_READ), emptyList(), listOf(writer), "SHOP")

        assertThat(choice.status).isEqualTo(SelectionStatus.OVER_PRIVILEGED)
        assertThat(choice.connection).isEqualTo(writer)
        assertThat(choice.excess).containsExactlyInAnyOrder(ISSUE_COMMENT, ISSUE_TRANSITION)
        assertThat(choice.message).contains("more rights than needed").contains("ISSUE_COMMENT, ISSUE_TRANSITION").contains("only ISSUE_READ")
    }

    @Test
    fun `no qualifying connection fails with a pointer to the guide`() {
        val reader = connection("reader", READ)
        val choice = selector.select(setOf(ISSUE_COMMENT), emptyList(), listOf(reader), "SHOP")

        assertThat(choice.status).isEqualTo(SelectionStatus.NONE)
        assertThat(choice.usable).isFalse()
        assertThat(choice.message).contains("[ISSUE_COMMENT]").contains("SHOP").contains("integrations-jira#jira-connections")
    }

    @Test
    fun `least privilege spans both tiers - a tight platform reader beats a project writer`() {
        val projectWriter = connection("project writer", WRITE, projectId = project)
        val platformReader = connection("platform reader", READ)
        val choice = selector.select(setOf(ISSUE_READ), listOf(projectWriter), listOf(platformReader), "SHOP")

        assertThat(choice.connection).isEqualTo(platformReader)
        assertThat(choice.status).isEqualTo(SelectionStatus.OK)
    }

    @Test
    fun `on a tie the project connection wins over a platform one, before verification and name`() {
        val projectReader = connection("z project reader", READ, projectId = project)
        val platformReader = connection("a platform reader", READ)
            .verified(ConnectionVerification(VerificationStatus.OK, Fixtures.now, null, emptyList(), emptyList()))
        val choice = selector.select(setOf(ISSUE_READ), listOf(projectReader), listOf(platformReader), "SHOP")

        assertThat(choice.connection).isEqualTo(projectReader)
        assertThat(choice.status).isEqualTo(SelectionStatus.OK)
    }

    @Test
    fun `an over-privileged project connection is used when nothing tighter exists`() {
        val projectWriter = connection("project writer", WRITE, projectId = project)
        val legacy = connection("Jira (environment variables)", ALL, legacy = true)
        val choice = selector.select(setOf(ISSUE_READ), listOf(projectWriter), listOf(legacy), "SHOP")

        assertThat(choice.connection).isEqualTo(projectWriter)
        assertThat(choice.status).isEqualTo(SelectionStatus.OVER_PRIVILEGED)
    }

    @Test
    fun `platform connections are the fallback when no project connection qualifies`() {
        val projectReader = connection("project reader", READ, projectId = project)
        val platformCommenter = connection("platform commenter", setOf(ISSUE_COMMENT))
        val choice = selector.select(setOf(ISSUE_COMMENT), listOf(projectReader), listOf(platformCommenter), "SHOP")

        assertThat(choice.connection).isEqualTo(platformCommenter)
        assertThat(choice.status).isEqualTo(SelectionStatus.OK)
    }

    @Test
    fun `the allow-list excludes connections limited to other Jira projects`() {
        val otherProjects = connection("ops reader", READ, allowed = listOf("OPS"))
        val writer = connection("writer", WRITE)
        val choice = selector.select(setOf(ISSUE_READ), emptyList(), listOf(otherProjects, writer), "SHOP")

        assertThat(choice.connection).isEqualTo(writer)
        assertThat(selector.select(setOf(ISSUE_READ), emptyList(), listOf(otherProjects, writer), "OPS").connection).isEqualTo(otherProjects)
    }

    @Test
    fun `the legacy environment credential is chosen last and reported as over-privileged`() {
        val legacy = connection("Jira (environment variables)", ALL, legacy = true)
        val writer = connection("writer", WRITE)
        assertThat(selector.select(WRITE, emptyList(), listOf(legacy, writer), "SHOP").connection).isEqualTo(writer)

        val only = selector.select(setOf(ISSUE_READ), emptyList(), listOf(legacy), "SHOP")
        assertThat(only.connection).isEqualTo(legacy)
        assertThat(only.status).isEqualTo(SelectionStatus.OVER_PRIVILEGED)
        assertThat(only.message).contains("legacy environment credential")
    }

    @Test
    fun `ties prefer a successful verification`() {
        val unverified = connection("a reader", READ)
        val verified = connection("b reader", READ).verified(ConnectionVerification(VerificationStatus.OK, Fixtures.now, null, emptyList(), emptyList()))
        val failed = connection("0 reader", READ).verified(ConnectionVerification(VerificationStatus.FAILED, Fixtures.now, null, emptyList(), emptyList()))

        assertThat(selector.select(READ, emptyList(), listOf(failed, unverified, verified), null).connection).isEqualTo(verified)
    }

    @Test
    fun `a pinned connection is used as is, or the choice fails`() {
        val writer = connection("writer", WRITE)
        val reader = connection("reader", READ)
        val pinned = selector.select(setOf(ISSUE_READ), emptyList(), listOf(writer, reader), "SHOP", pinned = writer.id)
        assertThat(pinned.connection).isEqualTo(writer)
        assertThat(pinned.pinned).isTrue()
        assertThat(pinned.status).isEqualTo(SelectionStatus.OVER_PRIVILEGED)

        val insufficient = selector.select(setOf(ISSUE_COMMENT), emptyList(), listOf(writer, reader), "SHOP", pinned = reader.id)
        assertThat(insufficient.status).isEqualTo(SelectionStatus.PINNED_INSUFFICIENT)
        assertThat(insufficient.message).contains("lacks the capabilities ISSUE_COMMENT")

        val missing = selector.select(setOf(ISSUE_READ), emptyList(), listOf(writer), "SHOP", pinned = ConnectionId.new())
        assertThat(missing.status).isEqualTo(SelectionStatus.PINNED_NOT_FOUND)

        // a connection of another project is not a candidate, so pinning it fails too
        val foreign = connection("foreign", READ, projectId = ProjectId.new())
        assertThat(selector.select(READ, emptyList(), listOf(reader), "SHOP", pinned = foreign.id).status).isEqualTo(SelectionStatus.PINNED_NOT_FOUND)
    }

    @Test
    fun `task executors declare the capabilities they need`() {
        assertThat(ConnectionCapability.requiredBy(TaskExecutor.FETCH_ISSUE)).containsExactly(ISSUE_READ)
        assertThat(ConnectionCapability.requiredBy(TaskExecutor.NOTIFY)).containsExactly(ISSUE_COMMENT)
        assertThat(ConnectionCapability.requiredBy(TaskExecutor.TRANSITION_ISSUE)).containsExactlyInAnyOrder(ISSUE_TRANSITION, ISSUE_READ)
        assertThat(ConnectionCapability.requiredBy(TaskExecutor.LLM)).isEmpty()
        assertThat(ConnectionCapability.entries.filter { it.write }).containsExactlyInAnyOrder(ISSUE_COMMENT, ISSUE_TRANSITION)
    }
}
