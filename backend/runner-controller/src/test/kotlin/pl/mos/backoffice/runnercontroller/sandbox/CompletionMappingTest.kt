package pl.mos.backoffice.runnercontroller.sandbox

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.protocol.AgentJobStatus
import pl.mos.backoffice.runnercontroller.sandbox.k8s.PodStatusMapper
import java.time.Duration
import java.time.Instant

class CompletionMappingTest {
    private val t0 = Instant.parse("2026-09-28T10:00:00Z")
    private val grace = Duration.ofSeconds(90)
    private val startTimeout = Duration.ofMinutes(15)

    private fun map(o: SandboxObservation, after: Long = 10) = CompletionMapper.map(o, t0, t0.plusSeconds(after), grace, startTimeout)

    private fun pod(phase: String, podReason: String? = null, configure: ContainerStatusBuilder.() -> Unit): Pod {
        val cs = ContainerStatusBuilder().withName("agent").withImageID("docker.io/back-office/runner@sha256:abc").apply(configure).build()
        return PodBuilder().withNewMetadata().withName("bo-job-1").endMetadata().withNewSpec().withNodeName("node-1").endSpec()
            .withNewStatus().withPhase(phase).withReason(podReason).withContainerStatuses(cs).endStatus().build()
    }

    @Test
    fun `exit 0 is SUCCEEDED with the image digest`() {
        val o = PodStatusMapper.observe(pod("Succeeded") { withNewState().withNewTerminated().withExitCode(0).withReason("Completed").endTerminated().endState() })
        assertThat(o.imageDigest).isEqualTo("sha256:abc")
        assertThat(o.node).isEqualTo("node-1")
        assertThat(map(o)).isEqualTo(SandboxOutcome(AgentJobStatus.SUCCEEDED, 0, null))
    }

    @Test
    fun `OOMKilled, DeadlineExceeded, Evicted and result-not-delivered`() {
        val oom = PodStatusMapper.observe(pod("Failed") { withNewState().withNewTerminated().withExitCode(137).withReason("OOMKilled").endTerminated().endState() })
        assertThat(map(oom)).isEqualTo(SandboxOutcome(AgentJobStatus.FAILED, 137, "OOMKilled"))

        val deadline = PodStatusMapper.observe(pod("Failed", "DeadlineExceeded") { withNewState().withNewRunning().endRunning().endState() })
        assertThat(map(deadline)).isEqualTo(SandboxOutcome(AgentJobStatus.TIMED_OUT, null, "DeadlineExceeded"))

        val evicted = PodStatusMapper.observe(pod("Failed", "Evicted") { })
        assertThat(map(evicted)!!.reason).isEqualTo("Evicted")

        val undelivered = PodStatusMapper.observe(pod("Failed") { withNewState().withNewTerminated().withExitCode(3).withReason("Error").endTerminated().endState() })
        assertThat(map(undelivered)).isEqualTo(SandboxOutcome(AgentJobStatus.FAILED, 3, "ResultNotDelivered"))

        val other = PodStatusMapper.observe(pod("Failed") { withNewState().withNewTerminated().withExitCode(2).withReason("Error").endTerminated().endState() })
        assertThat(map(other)).isEqualTo(SandboxOutcome(AgentJobStatus.FAILED, 2, "ExitCode2"))
    }

    @Test
    fun `ImagePullBackOff fails only after the grace period`() {
        val pulling = PodStatusMapper.observe(pod("Pending") { withNewState().withNewWaiting().withReason("ImagePullBackOff").endWaiting().endState() })
        assertThat(pulling.phase).isEqualTo(SandboxPhase.PENDING)
        assertThat(map(pulling, after = 30)).isNull()
        assertThat(map(pulling, after = 91)).isEqualTo(SandboxOutcome(AgentJobStatus.FAILED, null, "ImagePullBackOff"))
        val errPull = pulling.copy(waitingReason = "ErrImagePull")
        assertThat(map(errPull, after = 120)!!.reason).isEqualTo("ImagePullBackOff")
        val config = pulling.copy(waitingReason = "CreateContainerConfigError")
        assertThat(map(config, after = 120)!!.reason).isEqualTo("CreateContainerConfigError")
    }

    @Test
    fun `pending too long, running, and gone`() {
        val pending = SandboxObservation(SandboxPhase.PENDING)
        assertThat(map(pending, after = 60)).isNull()
        assertThat(map(pending, after = 901)!!.reason).isEqualTo("StartTimeout")
        val running = PodStatusMapper.observe(pod("Running") { withNewState().withNewRunning().withStartedAt("2026-09-28T10:00:05Z").endRunning().endState() })
        assertThat(running.phase).isEqualTo(SandboxPhase.RUNNING)
        assertThat(running.startedAt).isEqualTo(Instant.parse("2026-09-28T10:00:05Z"))
        assertThat(map(running)).isNull()
        assertThat(map(PodStatusMapper.observe(null))).isEqualTo(SandboxOutcome(AgentJobStatus.FAILED, null, "SandboxLost"))
    }
}
