package pl.mos.backoffice.projects.adapter.out.scheduling

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.ExpireWaits
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Duration

/**
 * Every minute on one worker replica (ShedLock `wait-timeout-sweep`): resume `WAITING` runs whose gate / CI wait
 * exceeded its `timeoutSeconds` through the `timeout` port (the resume is a `RUN_ADVANCE` work item).
 */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class WaitTimeoutSweep(private val expireWaits: ExpireWaits, private val gate: AuthorizationGate, private val lock: ObjectProvider<ClusterLock>) {

    private val log = LoggerFactory.getLogger(WaitTimeoutSweep::class.java)

    @Scheduled(fixedDelayString = "\${backoffice.pipelines.wait-sweep-delay:60000}", initialDelayString = "\${backoffice.pipelines.wait-sweep-delay:60000}")
    fun sweep() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively("wait-timeout-sweep", Duration.ofMinutes(5), Duration.ofSeconds(20)) {
                val resumed = gate.asSystem("wait timeout sweep") { expireWaits.sweep() }
                if (resumed > 0) log.info("wait timeout sweep resumed runs count={}", resumed)
            }
        } catch (e: RuntimeException) {
            log.warn("wait timeout sweep failed error={}", e.message)
        }
    }
}
