package pl.mos.backoffice.projects.adapter.out.langgraph

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.adapter.config.PipelinesProperties
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.shared.platform.ClusterLock
import pl.mos.backoffice.shared.platform.WorkHandler
import pl.mos.backoffice.shared.platform.WorkItem
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkOutcome
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.time.Duration

/** [WorkHandler] of `RUN_ADVANCE`: drives one workflow run on the worker holding the item's lease. */
@Component
class RunAdvanceHandler(private val engine: LangGraphWorkflowEngine) : WorkHandler {
    override val kind: String = WorkKinds.RUN_ADVANCE

    override fun handle(item: WorkItem): WorkOutcome = engine.advance(item)
}

/**
 * Recovery scan (worker role, ShedLock `workflow-recovery`): at startup and every
 * `backoffice.pipelines.recovery-scan-delay` it enqueues a `RECOVER` advance for every `RUNNING` run. The
 * durable queue normally makes this a no-op: the enqueue merges into a live item without touching its payload
 * (`_merge=keep`), a run waiting for a runner job stays waiting, and a run whose previous holder died is
 * continued from its checkpoint. It covers runs left without an item (e.g. in flight during the upgrade to the
 * queue, or after a DEAD item was purged). `WAITING` runs need nothing: their decision or signal resumes them.
 */
@Component
@ConditionalOnProperty(prefix = "backoffice.platform", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
class RunRecovery(
    private val runs: WorkflowRunRepository,
    private val engine: WorkflowEngine,
    private val props: PipelinesProperties,
    private val gate: AuthorizationGate,
    private val lock: ObjectProvider<ClusterLock>,
) {
    private val log = LoggerFactory.getLogger(RunRecovery::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun recoverInFlightRuns() {
        if (!props.recoverOnStartup) {
            log.info("workflow recovery disabled")
            return
        }
        scan()
    }

    @Scheduled(fixedDelayString = "\${backoffice.pipelines.recovery-scan-delay:600000}", initialDelayString = "\${backoffice.pipelines.recovery-scan-delay:600000}")
    fun periodicScan() {
        if (props.recoverOnStartup) scan()
    }

    fun scan() {
        try {
            lock.getIfAvailable { ClusterLock.LOCAL }.runExclusively("workflow-recovery", Duration.ofMinutes(5), Duration.ofSeconds(30)) {
                gate.asSystem("workflow recovery") {
                    val inFlight = runs.findByStatus(setOf(RunStatus.RUNNING))
                    if (inFlight.isNotEmpty()) log.info("recovery scan: re-driving RUNNING workflow runs count={}", inFlight.size)
                    engine.recoverAll(inFlight)
                }
            }
        } catch (e: RuntimeException) {
            log.error("recovery failed error={}", e.message)
        }
    }
}
