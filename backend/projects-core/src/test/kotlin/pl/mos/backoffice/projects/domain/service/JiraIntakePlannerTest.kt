package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.IntakeMode
import pl.mos.backoffice.projects.domain.model.JiraIntake
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.StatusChange
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.security.Actor
import java.time.Duration
import java.time.Instant

/** ADR 0033: what the intake watcher asks Jira and which issues start a run. */
class JiraIntakePlannerTest {

    private val planner = JiraIntakePlanner()
    private val now = Instant.parse("2026-10-08T10:00:00Z")

    @Test
    fun `the JQL is the project key, the project filter and a relative window, oldest first`() {
        assertThat(planner.jql("SHOP", "labels = ai ORDER BY rank", "Ready for AI", 7))
            .isEqualTo("project = \"SHOP\" AND (labels = ai) AND status CHANGED TO \"Ready for AI\" AFTER -7m ORDER BY updated ASC")
        assertThat(planner.jql("SHOP", null, null, 12)).isEqualTo("project = \"SHOP\" AND created >= -12m ORDER BY updated ASC")
        assertThat(planner.jql("SHOP", " ", "Say \"hi\"", 2)).contains("status CHANGED TO \"Say \\\"hi\\\"\" AFTER -2m")
        assertThat(planner.mode("In Progress")).isEqualTo(IntakeMode.TRANSITION)
        assertThat(planner.mode("")).isEqualTo(IntakeMode.NEW_ISSUE)
    }

    @Test
    fun `the window covers the time since the cursor plus an overlap, bounded`() {
        assertThat(planner.windowMinutes(now.minusSeconds(300), now)).isEqualTo(7)
        assertThat(planner.windowMinutes(now.minusSeconds(61), now)).isEqualTo(4)
        assertThat(planner.windowMinutes(now, now)).isEqualTo(2)
        assertThat(planner.windowMinutes(now.minus(Duration.ofDays(30)), now)).isEqualTo(Duration.ofDays(7).toMinutes())
    }

    @Test
    fun `an issue that entered the status starts once per status change, by changelog id`() {
        val entered = WatchedIssue(
            "SHOP-42", "Ready for AI", now,
            statusChanges = listOf(StatusChange("100", now.minusSeconds(600), "Ready for AI"), StatusChange("120", now.minusSeconds(60), "Ready for AI"), StatusChange("110", now.minusSeconds(300), "Blocked")),
        )
        val movedOn = WatchedIssue("SHOP-43", "In Progress", now, statusChanges = listOf(StatusChange("130", now, "Ready for AI")))
        val noChangelog = WatchedIssue("SHOP-44", "ready for ai", now)

        val events = planner.events(listOf(entered, movedOn, noChangelog, entered), "Ready for AI")

        assertThat(events.map { it.issueKey to it.dedupeKey }).containsExactly(
            "SHOP-42" to "T:SHOP-42:120",
            "SHOP-44" to "T:SHOP-44:ready for ai:${now.toEpochMilli()}",
        )
    }

    @Test
    fun `new issues start once each`() {
        val events = planner.events(listOf(WatchedIssue("SHOP-1", "To Do", now), WatchedIssue("SHOP-2", null, now)), null)
        assertThat(events.map { it.dedupeKey }).containsExactly("N:SHOP-1", "N:SHOP-2")
    }

    @Test
    fun `the watcher backs off exponentially and honours Retry-After, and a new configuration takes a fresh baseline`() {
        val intake = JiraIntake(ProjectId.new(), enabled = true, pollIntervalMinutes = 5, workflowId = WorkflowDefinitionId.new(), cursor = now.minusSeconds(600))
        val once = intake.failed(now, "HTTP 503")
        assertThat(once.nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(5)))
        val twice = once.failed(now, "HTTP 503")
        assertThat(twice.nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(10)))
        assertThat(twice.consecutiveFailures).isEqualTo(2)
        assertThat(intake.failed(now, "HTTP 429", retryAfter = Duration.ofMinutes(30)).nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(30)))
        var many = intake
        repeat(12) { many = many.failed(now, "down") }
        assertThat(many.nextPollAt).isEqualTo(now.plus(JiraIntake.MAX_BACKOFF))

        val ok = twice.succeeded(now, started = 2, advanceCursor = true, connection = "reader", warning = null)
        assertThat(ok.consecutiveFailures).isZero()
        assertThat(ok.cursor).isEqualTo(now)
        assertThat(ok.issuesStarted).isEqualTo(2)
        assertThat(ok.lastError).isNull()

        val disabled = ok.configure(false, 5, ok.workflowId, Actor.SYSTEM, now)
        assertThat(disabled.cursor).isEqualTo(now)
        val reEnabled = disabled.configure(true, 5, ok.workflowId, Actor.SYSTEM, now)
        assertThat(reEnabled.cursor).isNull()
        assertThat(reEnabled.due(now)).isTrue()
    }
}
