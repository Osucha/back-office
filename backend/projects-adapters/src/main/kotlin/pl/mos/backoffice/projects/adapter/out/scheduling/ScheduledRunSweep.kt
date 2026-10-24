package pl.mos.backoffice.projects.adapter.out.scheduling

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.StartScheduledRuns
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Duration

/** ADR 0052: every minute on one worker replica (ShedLock `scheduled-run-sweep`): start the workflows whose schedule is due. */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class ScheduledRunSweep(private val scheduled: StartScheduledRuns, private val gate: AuthorizationGate, private val lock: ObjectProvider<ClusterLock>) {

    private val log = LoggerFactory.getLogger(ScheduledRunSweep::class.java)

    @Scheduled(fixedDelayString = "\${backoffice.pipelines.schedule-sweep-delay:60000}", initialDelayString = "\${backoffice.pipelines.schedule-sweep-delay:60000}")
    fun sweep() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively("scheduled-run-sweep", Duration.ofMinutes(5), Duration.ofSeconds(20)) {
                val started = gate.asSystem("scheduled workflow starts") { scheduled.sweep() }
                if (started > 0) log.info("scheduled workflows started runs count={}", started)
            }
        } catch (e: RuntimeException) {
            log.warn("scheduled workflow sweep failed error={}", e.message)
        }
    }
}
