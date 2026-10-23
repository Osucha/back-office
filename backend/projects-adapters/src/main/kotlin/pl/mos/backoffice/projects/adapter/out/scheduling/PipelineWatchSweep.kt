package pl.mos.backoffice.projects.adapter.out.scheduling

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.WatchPipelines
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Duration

/**
 * ADR 0052: every minute on one worker replica (ShedLock `pipeline-watch-sweep`): runs waiting for the pipeline of
 * their branch are resumed once it finished (the resume is a `RUN_ADVANCE` work item).
 */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class PipelineWatchSweep(private val watch: WatchPipelines, private val gate: AuthorizationGate, private val lock: ObjectProvider<ClusterLock>) {

    private val log = LoggerFactory.getLogger(PipelineWatchSweep::class.java)

    @Scheduled(fixedDelayString = "\${backoffice.pipelines.watch-sweep-delay:60000}", initialDelayString = "\${backoffice.pipelines.watch-sweep-delay:60000}")
    fun sweep() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively("pipeline-watch-sweep", Duration.ofMinutes(5), Duration.ofSeconds(20)) {
                val resumed = gate.asSystem("pipeline watch sweep") { watch.sweep() }
                if (resumed > 0) log.info("pipeline watch resumed runs count={}", resumed)
            }
        } catch (e: RuntimeException) {
            log.warn("pipeline watch sweep failed error={}", e.message)
        }
    }
}
