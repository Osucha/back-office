package pl.mos.backoffice.projects.adapter.out.scheduling

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import pl.mos.backoffice.projects.application.port.`in`.system.ExpireWaits
import pl.mos.backoffice.projects.application.port.`in`.system.PollJiraIntake
import pl.mos.backoffice.projects.application.port.`in`.system.PollPersonalIntake
import pl.mos.backoffice.projects.application.port.`in`.system.RemindExpiringConnections
import pl.mos.backoffice.projects.application.port.`in`.system.StartScheduledRuns
import pl.mos.backoffice.projects.application.port.`in`.system.WatchPipelines
import pl.mos.backoffice.projects.application.service.JiraIntakeService
import pl.mos.backoffice.projects.application.service.PersonalIntakeService
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.testing.FakeAccessGuard
import java.util.UUID

/**
 * ADR 0067: the schedulers and work-queue handlers of the Projects context call system ports, which refuse to run
 * outside `asSystem`. Each adapter enters `asSystem` itself, on the thread it runs on, and leaves no principal behind.
 * The fake ports call `requireSystem()` like the real services; the adapters catch and log failures, so a missing
 * `asSystem` shows up as a port that was never reached.
 */
class SchedulingAdaptersTest {

    private val guard = FakeAccessGuard.denyAll()
    private val lock = StaticListableBeanFactory().getBeanProvider(ClusterLock::class.java)
    private val reached = mutableListOf<String>()
    private val enqueued = mutableListOf<WorkRequest>()

    private fun system(what: String) {
        guard.requireSystem()
        reached += what
    }

    private val projectId = ProjectId.new()
    private val ruleId = IntakeRuleId.new()

    private val jira = object : PollJiraIntake {
        override fun due(): List<ProjectId> = listOf(projectId).also { system("jira due") }
        override fun poll(projectId: ProjectId): Int = 1.also { system("jira poll") }
    }
    private val personal = object : PollPersonalIntake {
        override fun due(): List<IntakeRuleId> = listOf(ruleId).also { system("personal due") }
        override fun poll(id: IntakeRuleId): Int = 1.also { system("personal poll") }
    }

    @Test
    fun `the sweeps and the expiry reminder run their system port as the system`() {
        WaitTimeoutSweep(object : ExpireWaits { override fun sweep() = 0.also { system("wait timeout") } }, guard, lock).sweep()
        ScheduledRunSweep(object : StartScheduledRuns { override fun sweep() = 0.also { system("schedules") } }, guard, lock).sweep()
        PipelineWatchSweep(object : WatchPipelines { override fun sweep() = 0.also { system("pipelines") } }, guard, lock).sweep()
        ConnectionExpiryReminderJob(RemindExpiringConnections { 0.also { system("expiry reminder") } }, guard, lock).remind()

        assertThat(reached).containsExactly("wait timeout", "schedules", "pipelines", "expiry reminder")
        assertThat(guard.currentUser().kind).isEqualTo(PrincipalKind.USER)
    }

    @Test
    fun `the intake scans enqueue polls and the poll handlers run them as the system`() {
        JiraIntakeScheduler(jira, { enqueued += it }, guard, lock).scan()
        PersonalIntakeScheduler(personal, { enqueued += it }, guard, lock).scan()

        assertThat(enqueued.map { it.kind }).containsExactly(WorkKinds.JIRA_INTAKE_POLL, WorkKinds.PERSONAL_INTAKE_POLL)

        val jiraItem = WorkItem(UUID.randomUUID(), WorkKinds.JIRA_INTAKE_POLL, mapOf(JiraIntakeService.PROJECT_ID to projectId.toString()), 1, "jira")
        val personalItem = WorkItem(UUID.randomUUID(), WorkKinds.PERSONAL_INTAKE_POLL, mapOf(PersonalIntakeService.RULE_ID to ruleId.toString()), 1, "personal")
        assertThat(JiraIntakePollHandler(jira, guard).handle(jiraItem)).isEqualTo(WorkOutcome.Done)
        assertThat(PersonalIntakePollHandler(personal, guard).handle(personalItem)).isEqualTo(WorkOutcome.Done)

        assertThat(reached).containsExactly("jira due", "personal due", "jira poll", "personal poll")
        assertThat(guard.currentUser().kind).isEqualTo(PrincipalKind.USER)
    }
}
