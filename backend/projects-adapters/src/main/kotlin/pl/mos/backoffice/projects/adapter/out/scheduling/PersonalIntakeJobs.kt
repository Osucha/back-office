package pl.mos.backoffice.projects.adapter.out.scheduling

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.PollPersonalIntake
import pl.mos.backoffice.projects.application.service.PersonalIntakeService
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
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
 * Personal intake scheduler (ADR 0038): every `backoffice.personal-intake.scan-delay` (default 30 s) one worker replica
 * (ShedLock `personal-intake-scan`) enqueues a `PERSONAL_INTAKE_POLL` work item for every rule that is due; the dedupe
 * key `PERSONAL_INTAKE_POLL:<ruleId>` keeps one live item per rule.
 */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class PersonalIntakeScheduler(
    private val poll: PollPersonalIntake,
    private val queue: WorkQueue,
    private val gate: AuthorizationGate,
    private val lock: ObjectProvider<ClusterLock>,
) {
    private val log = LoggerFactory.getLogger(PersonalIntakeScheduler::class.java)

    @Scheduled(fixedDelayString = "\${backoffice.personal-intake.scan-delay:30000}", initialDelayString = "\${backoffice.personal-intake.scan-delay:30000}")
    fun scan() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively(LOCK, Duration.ofMinutes(2), Duration.ofSeconds(10)) {
                val due = gate.asSystem("personal intake scan") { poll.due() }
                due.forEach { id ->
                    queue.enqueue(
                        WorkRequest(WorkKinds.PERSONAL_INTAKE_POLL, mapOf(PersonalIntakeService.RULE_ID to id.toString()), dedupeKey = PersonalIntakeService.dedupeKey(id), maxAttempts = 1),
                    )
                }
                if (due.isNotEmpty()) log.debug("personal intake scan enqueued polls count={}", due.size)
            }
        } catch (e: RuntimeException) {
            log.warn("personal intake scan failed error={}", e.message)
        }
    }

    private companion object {
        const val LOCK = "personal-intake-scan"
    }
}

/** [WorkHandler] of `PERSONAL_INTAKE_POLL`: one poll of one personal intake rule. */
@Component
class PersonalIntakePollHandler(private val poll: PollPersonalIntake, private val gate: AuthorizationGate) : WorkHandler {
    private val log = LoggerFactory.getLogger(PersonalIntakePollHandler::class.java)

    override val kind: String = WorkKinds.PERSONAL_INTAKE_POLL

    override fun handle(item: WorkItem): WorkOutcome {
        val id = item.payload[PersonalIntakeService.RULE_ID]?.toString()?.let { runCatching { IntakeRuleId.of(it) }.getOrNull() }
            ?: return WorkOutcome.Dead("PERSONAL_INTAKE_POLL without a valid ruleId")
        val found = gate.asSystem("personal intake poll") { poll.poll(id) }
        if (found > 0) log.info("personal intake found issues rule={} count={}", id, found)
        return WorkOutcome.Done
    }
}
