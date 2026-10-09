package pl.mos.backoffice.runnercontroller.sandbox

import pl.mos.backoffice.runnercontroller.protocol.AgentJobStatus
import java.time.Duration
import java.time.Instant

/** Terminal outcome of a sandbox as the controller reports it (`JobCompletion.status/exitCode/reason`). */
data class SandboxOutcome(val status: AgentJobStatus, val exitCode: Int?, val reason: String?)

/**
 * Maps what a backend observed to a terminal job status (doc 10, "Completion"). The agent's own verdict
 * (SUCCEEDED/FAILED of the work) arrives separately in the `RunnerResult` the runner posts with the job token;
 * this is the platform's view: did the sandbox run to completion, and if not, why.
 */
object CompletionMapper {
    const val REASON_POLICY_VIOLATION = "policy-violation"
    const val REASON_DEADLINE = "DeadlineExceeded"
    const val REASON_OOM = "OOMKilled"
    const val REASON_IMAGE_PULL = "ImagePullBackOff"
    const val REASON_LOST = "SandboxLost"
    const val REASON_START_TIMEOUT = "StartTimeout"
    const val REASON_RESULT_NOT_DELIVERED = "ResultNotDelivered"

    private val IMAGE_ERRORS = setOf("ImagePullBackOff", "ErrImagePull", "InvalidImageName", "ErrImageNeverPull", "RegistryUnavailable")
    private val CONFIG_ERRORS = setOf("CreateContainerConfigError", "CreateContainerError", "RunContainerError")

    /**
     * @return the outcome when the sandbox is finished (or can never start), null while it is still running
     */
    fun map(
        observation: SandboxObservation,
        launchedAt: Instant,
        now: Instant,
        imagePullGrace: Duration,
        startTimeout: Duration,
    ): SandboxOutcome? = when (observation.phase) {
        SandboxPhase.GONE -> SandboxOutcome(AgentJobStatus.FAILED, null, REASON_LOST)
        SandboxPhase.SUCCEEDED -> SandboxOutcome(AgentJobStatus.SUCCEEDED, observation.exitCode ?: 0, null)
        SandboxPhase.FAILED -> failed(observation)
        SandboxPhase.PENDING -> {
            val waiting = observation.waitingReason
            val age = Duration.between(launchedAt, now)
            when {
                waiting in IMAGE_ERRORS && age >= imagePullGrace -> SandboxOutcome(AgentJobStatus.FAILED, null, REASON_IMAGE_PULL)
                waiting in CONFIG_ERRORS && age >= imagePullGrace -> SandboxOutcome(AgentJobStatus.FAILED, null, waiting)
                age >= startTimeout -> SandboxOutcome(AgentJobStatus.FAILED, null, REASON_START_TIMEOUT)
                else -> null
            }
        }
        SandboxPhase.RUNNING -> null
    }

    private fun failed(o: SandboxObservation): SandboxOutcome {
        val reason = o.reason
        return when {
            reason == REASON_DEADLINE -> SandboxOutcome(AgentJobStatus.TIMED_OUT, o.exitCode, REASON_DEADLINE)
            reason == REASON_OOM -> SandboxOutcome(AgentJobStatus.FAILED, o.exitCode, REASON_OOM)
            reason == "Evicted" -> SandboxOutcome(AgentJobStatus.FAILED, o.exitCode, "Evicted")
            // entrypoint.py exits 3 when it could not deliver the RunnerResult.
            o.exitCode == 3 -> SandboxOutcome(AgentJobStatus.FAILED, 3, REASON_RESULT_NOT_DELIVERED)
            o.exitCode == 137 && reason.isNullOrBlank() -> SandboxOutcome(AgentJobStatus.FAILED, 137, "Killed")
            else -> SandboxOutcome(AgentJobStatus.FAILED, o.exitCode, reason?.takeIf { it.isNotBlank() && it != "Error" } ?: "ExitCode${o.exitCode ?: ""}")
        }
    }
}
