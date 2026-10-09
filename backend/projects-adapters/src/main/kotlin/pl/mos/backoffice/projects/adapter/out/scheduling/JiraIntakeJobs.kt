package pl.mos.backoffice.projects.adapter.out.scheduling

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.PollJiraIntake
import pl.mos.backoffice.projects.application.port.`in`.RemindExpiringConnections
import pl.mos.backoffice.projects.application.service.JiraIntakeService
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Duration

/**
 * Jira intake scheduler (ADR 0033): every `backoffice.jira-intake.scan-delay` (default 30 s) one worker replica
 * (ShedLock `jira-intake-scan`) enqueues a `JIRA_INTAKE_POLL` work item for every watcher that is due; the dedupe key
 * `JIRA_INTAKE_POLL:<projectId>` keeps one live item per project, so a slow Jira never piles up polls.
 */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class JiraIntakeScheduler(
    private val poll: PollJiraIntake,
    private val queue: WorkQueue,
    private val gate: AuthorizationGate,
    private val lock: ObjectProvider<ClusterLock>,
) {
    private val log = LoggerFactory.getLogger(JiraIntakeScheduler::class.java)

    @Scheduled(fixedDelayString = "\${backoffice.jira-intake.scan-delay:30000}", initialDelayString = "\${backoffice.jira-intake.scan-delay:30000}")
    fun scan() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively(LOCK, Duration.ofMinutes(2), Duration.ofSeconds(10)) {
                val due = gate.asSystem("jira intake scan") { poll.due() }
                due.forEach { projectId ->
                    queue.enqueue(
                        WorkRequest(WorkKinds.JIRA_INTAKE_POLL, mapOf(JiraIntakeService.PROJECT_ID to projectId.toString()), dedupeKey = JiraIntakeService.dedupeKey(projectId), maxAttempts = 1),
                    )
                }
                if (due.isNotEmpty()) log.debug("jira intake scan enqueued polls count={}", due.size)
            }
        } catch (e: RuntimeException) {
            log.warn("jira intake scan failed error={}", e.message)
        }
    }

    private companion object {
        const val LOCK = "jira-intake-scan"
    }
}

/** [WorkHandler] of `JIRA_INTAKE_POLL`: one poll of one project's watcher on the worker holding the item. */
@Component
class JiraIntakePollHandler(private val poll: PollJiraIntake, private val gate: AuthorizationGate) : WorkHandler {
    private val log = LoggerFactory.getLogger(JiraIntakePollHandler::class.java)

    override val kind: String = WorkKinds.JIRA_INTAKE_POLL

    override fun handle(item: WorkItem): WorkOutcome {
        val projectId = item.payload[JiraIntakeService.PROJECT_ID]?.toString()?.let { runCatching { ProjectId.of(it) }.getOrNull() }
            ?: return WorkOutcome.Dead("JIRA_INTAKE_POLL without a valid projectId")
        val started = gate.asSystem("jira intake poll") { poll.poll(projectId) }
        if (started > 0) log.info("jira intake started runs project={} count={}", projectId, started)
        return WorkOutcome.Done
    }
}

/**
 * Daily (`backoffice.connections.expiry-reminder-cron`, default 07:00 UTC) on one worker replica (ShedLock
 * `connection-expiry-reminder`): reminds of connection tokens expiring within 14 days (ADR 0030 follow-up).
 */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class ConnectionExpiryReminderJob(
    private val reminders: RemindExpiringConnections,
    private val gate: AuthorizationGate,
    private val lock: ObjectProvider<ClusterLock>,
) {
    private val log = LoggerFactory.getLogger(ConnectionExpiryReminderJob::class.java)

    @Scheduled(cron = "\${backoffice.connections.expiry-reminder-cron:0 0 7 * * *}", zone = "UTC")
    fun remind() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively("connection-expiry-reminder", Duration.ofMinutes(10), Duration.ofMinutes(1)) {
                val count = gate.asSystem("connection expiry reminder") { reminders.remindExpiring() }
                if (count > 0) log.info("connection expiry reminders sent count={}", count)
            }
        } catch (e: RuntimeException) {
            log.warn("connection expiry reminder failed error={}", e.message)
        }
    }
}
