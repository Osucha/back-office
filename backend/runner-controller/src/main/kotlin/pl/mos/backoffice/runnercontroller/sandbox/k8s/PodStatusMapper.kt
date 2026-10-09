package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.Pod
import pl.mos.backoffice.runnercontroller.sandbox.SandboxObservation
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase
import java.time.Instant

/** Pod status -> [SandboxObservation]; the agent container is named [PodSpecRenderer.AGENT_CONTAINER]. */
object PodStatusMapper {

    fun observe(pod: Pod?, bundleDelivered: Boolean = true): SandboxObservation {
        if (pod == null) return SandboxObservation(SandboxPhase.GONE, bundleDelivered = bundleDelivered)
        val status = pod.status
        val agent = status?.containerStatuses?.firstOrNull { it.name == PodSpecRenderer.AGENT_CONTAINER }
        val terminated = agent?.state?.terminated
        val waiting = agent?.state?.waiting
        val digest = agent?.imageID?.substringAfter("@", "")?.takeIf { it.startsWith("sha256:") }
        val node = pod.spec?.nodeName
        val started = agent?.state?.running?.startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val podReason = status?.reason
        return when {
            podReason == "DeadlineExceeded" ->
                SandboxObservation(SandboxPhase.FAILED, terminated?.exitCode, "DeadlineExceeded", status.message, node, digest)
            podReason == "Evicted" ->
                SandboxObservation(SandboxPhase.FAILED, terminated?.exitCode, "Evicted", status.message, node, digest)
            terminated != null -> {
                val code = terminated.exitCode
                SandboxObservation(
                    phase = if (code == 0) SandboxPhase.SUCCEEDED else SandboxPhase.FAILED,
                    exitCode = code,
                    reason = terminated.reason,
                    message = terminated.message,
                    node = node,
                    imageDigest = digest,
                )
            }
            status?.phase == "Succeeded" -> SandboxObservation(SandboxPhase.SUCCEEDED, 0, null, null, node, digest)
            status?.phase == "Failed" -> SandboxObservation(SandboxPhase.FAILED, null, podReason ?: "PodFailed", status.message, node, digest)
            agent?.state?.running != null ->
                SandboxObservation(SandboxPhase.RUNNING, node = node, imageDigest = digest, startedAt = started, bundleDelivered = bundleDelivered)
            else -> SandboxObservation(
                SandboxPhase.PENDING,
                node = node,
                waitingReason = waiting?.reason,
                message = waiting?.message ?: status?.conditions?.firstOrNull { it.type == "PodScheduled" && it.status == "False" }?.message,
                bundleDelivered = bundleDelivered,
            )
        }
    }
}
