package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.sandbox.SandboxLaunchFailed
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import pl.mos.backoffice.runnercontroller.sandbox.LaunchRequest
import pl.mos.backoffice.runnercontroller.sandbox.ManagedSandbox
import pl.mos.backoffice.runnercontroller.sandbox.PoolSettings
import pl.mos.backoffice.runnercontroller.sandbox.SandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.SandboxHandle
import pl.mos.backoffice.runnercontroller.sandbox.SandboxObservation
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase

/**
 * `K8S_JOB` backend: a `batch/v1 Job` per job (no warm pool) with the same pod spec, RuntimeClass and egress
 * design as the Agent Sandbox backend. The job bundle is a per-job Secret mounted read-only, owned by the Job
 * so it is garbage-collected with it.
 */
class KubernetesJobBackend(private val k: KubernetesSupport) : SandboxBackend {
    private val log = LoggerFactory.getLogger(javaClass)
    override val kind = BackendKind.K8S_JOB
    private val jobs get() = k.client.batch().v1().jobs().inNamespace(k.namespace)

    override fun ensurePool(pool: PoolSettings) {
        k.egress.ensure(pool.poolId)
    }

    override fun proxyUrl(request: LaunchRequest): String = k.proxyUrl(request)

    override val gitCredentials get() = k.gitCredentials

    override fun launch(request: LaunchRequest): SandboxHandle {
        k.checkRuntimeClass(request)
        val name = Labels.sandboxName(request.jobId)
        jobs.withName(name).get()?.let {
            log.info("job {} already has Job {}; re-attaching", request.jobId, name)
            return SandboxHandle(request.jobId, name, kind)
        }
        k.createJobSecret(request, proxyUrl(request))
        val created = try {
            jobs.resource(buildJob(request)).create()
        } catch (e: KubernetesClientException) {
            k.deleteJobSecret(request.jobId)
            throw SandboxLaunchFailed("cannot create Job $name: ${e.message}", e)
        }
        k.adoptSecret(name, created)
        return SandboxHandle(request.jobId, name, kind)
    }

    fun buildJob(request: LaunchRequest): Job {
        val name = Labels.sandboxName(request.jobId)
        val deadline = request.policy.timeoutSeconds.toLong() + k.props.deadlineGraceSeconds
        val labels = k.jobLabels(request)
        val podSpec = k.renderer.podSpec(
            request.policy,
            k.bundleSecrets(name, request),
            activeDeadlineSeconds = deadline,
        )
        return JobBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(k.namespace)
            .withLabels<String, String>(labels)
            .withAnnotations<String, String>(k.jobAnnotations(request))
            .endMetadata()
            .withNewSpec()
            .withBackoffLimit(0)
            .withActiveDeadlineSeconds(deadline)
            .withTtlSecondsAfterFinished(k.props.kubernetes.jobTtlSecondsAfterFinished)
            .withNewTemplate()
            .withNewMetadata().withLabels<String, String>(labels).withAnnotations<String, String>(k.jobAnnotations(request)).endMetadata()
            .withSpec(podSpec)
            .endTemplate()
            .endSpec()
            .build()
    }

    override fun observe(handle: SandboxHandle): SandboxObservation {
        val job = jobs.withName(handle.name).get() ?: return SandboxObservation(SandboxPhase.GONE)
        val deadlineExceeded = job.status?.conditions.orEmpty().any { it.type == "Failed" && it.status == "True" && it.reason == "DeadlineExceeded" }
        val pods = k.client.pods().inNamespace(k.namespace).withLabel(Labels.JOB_ID, handle.jobId).list().items
        val pod = pods.maxByOrNull { it.metadata.creationTimestamp ?: "" }
        if (deadlineExceeded) {
            return PodStatusMapper.observe(pod).copy(phase = SandboxPhase.FAILED, reason = "DeadlineExceeded")
        }
        if (pod == null) {
            val failed = job.status?.conditions.orEmpty().firstOrNull { it.type == "Failed" && it.status == "True" }
            return if (failed != null) SandboxObservation(SandboxPhase.FAILED, reason = failed.reason, message = failed.message)
            else SandboxObservation(SandboxPhase.PENDING)
        }
        return PodStatusMapper.observe(pod)
    }

    override fun delete(handle: SandboxHandle) {
        runCatching { jobs.withName(handle.name).withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.BACKGROUND).delete() }
            .onFailure { log.warn("cannot delete Job {}: {}", handle.name, it.message) }
        k.deleteJobSecret(handle.jobId)
    }

    override fun listManaged(poolId: String): List<ManagedSandbox> =
        jobs.withLabel(Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE).withLabel(Labels.POOL, Labels.value(poolId)).list().items.mapNotNull { job ->
            val jobId = job.metadata.labels?.get(Labels.JOB_ID) ?: return@mapNotNull null
            ManagedSandbox(SandboxHandle(jobId, job.metadata.name, kind), poolId)
        }
}
