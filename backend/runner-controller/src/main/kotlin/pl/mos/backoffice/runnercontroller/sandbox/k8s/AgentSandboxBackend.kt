package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.policy.ResolvedPolicy
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import pl.mos.backoffice.runnercontroller.sandbox.LaunchRequest
import pl.mos.backoffice.runnercontroller.sandbox.ManagedSandbox
import pl.mos.backoffice.runnercontroller.sandbox.PoolSettings
import pl.mos.backoffice.runnercontroller.sandbox.SandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.SandboxHandle
import pl.mos.backoffice.runnercontroller.sandbox.SandboxLaunchFailed
import pl.mos.backoffice.runnercontroller.sandbox.SandboxObservation
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase
import java.util.concurrent.ConcurrentHashMap

/** Writes the job bundle into a running warm pod (the pod waits for `ready`). */
fun interface BundleWriter {
    fun write(namespace: String, pod: String, container: String, files: Map<String, String>)
}

/** `pods/exec` + fabric8 file upload (needs `sh` and `base64` in the image, which the runner image has). */
class ExecBundleWriter(private val k: KubernetesSupport) : BundleWriter {
    override fun write(namespace: String, pod: String, container: String, files: Map<String, String>) {
        val target = k.client.pods().inNamespace(namespace).withName(pod).inContainer(container)
        files.forEach { (path, content) ->
            val ok = target.file(path).upload(content.byteInputStream())
            if (!ok) throw SandboxLaunchFailed("upload of $path into $pod failed")
        }
    }
}

/**
 * `AGENT_SANDBOX` backend on kubernetes-sigs/agent-sandbox **v1beta1** CRDs:
 *
 * - `SandboxTemplate` (`extensions.agents.x-k8s.io/v1beta1`) per (image, isolation, RuntimeClass, resources,
 *   caches) hash, with the sandbox pod spec and an explicit `networkPolicy` (egress only to the pool's
 *   proxy and DNS; `networkPolicyManagement: Managed` with the default policy would allow the public
 *   internet), `envVarsInjectionPolicy: Disallowed`;
 * - `SandboxWarmPool` (`extensions.agents.x-k8s.io/v1beta1`) per template, `replicas` = pool warm size;
 * - per job a `SandboxClaim` (`extensions.agents.x-k8s.io/v1beta1`, `warmPoolRef`, `lifecycle.shutdownTime` =
 *   deadline, `shutdownPolicy: Delete`). A warm pod cannot mount a per-job Secret (its volumes are fixed
 *   before the claim exists and a claim with `env` forces a cold start), so the controller writes the job
 *   bundle into the pod's memory-backed `/var/run/backoffice/job` via `pods/exec` once the claim is bound;
 *   the runner waits for the `ready` file;
 * - with warm size 0 a `Sandbox` (`agents.x-k8s.io/v1beta1`) is created directly, cold, with the per-job
 *   Secret mounted read-only and `activeDeadlineSeconds`.
 *
 * The pod of a Sandbox has the Sandbox's name; the claim's bound Sandbox is `status.sandbox.name`.
 */
class AgentSandboxBackend(
    private val k: KubernetesSupport,
    private val writer: BundleWriter = ExecBundleWriter(k),
    private val warmPoolSizeFor: (PoolSettings) -> Int = { k.props.kubernetes.warmPoolSize ?: it.warmPoolSize },
) : SandboxBackend {
    private val log = LoggerFactory.getLogger(javaClass)
    override val kind = BackendKind.AGENT_SANDBOX

    private val poolWarmSize = ConcurrentHashMap<String, Int>()
    private val delivered = ConcurrentHashMap.newKeySet<String>()
    private val pendingBundles = ConcurrentHashMap<String, Map<String, String>>()

    private fun res(ctx: ResourceDefinitionContext) = k.client.genericKubernetesResources(ctx).inNamespace(k.namespace)

    override fun ensurePool(pool: PoolSettings) {
        k.egress.ensure(pool.poolId)
        val size = warmPoolSizeFor(pool).coerceAtLeast(0)
        poolWarmSize[pool.poolId] = size
        val default = pool.defaultPolicy
        if (size > 0 && default != null) {
            ensureTemplate(pool.poolId, default)
            ensureWarmPool(pool.poolId, default, size)
        }
    }

    override fun proxyUrl(request: LaunchRequest): String = k.proxyUrl(request)

    override val gitCredentials get() = k.gitCredentials

    override fun launch(request: LaunchRequest): SandboxHandle {
        k.checkRuntimeClass(request)
        val name = Labels.sandboxName(request.jobId)
        val warm = (poolWarmSize[request.poolId] ?: 0) > 0
        val secret = k.createJobSecret(request, proxyUrl(request))
        try {
            if (warm) {
                val template = ensureTemplate(request.poolId, request.policy)
                ensureWarmPool(request.poolId, request.policy, poolWarmSize.getValue(request.poolId))
                val claim = res(CLAIM).resource(claim(request, template)).create()
                k.adoptSecret(secret.metadata.name, claim)
                val files = request.bundle.files(proxyUrl(request)).mapKeys { "${PodSpecRenderer.JOB_DIR}/${it.key}" }.toMutableMap()
                files += k.gitTokenFiles(request)
                // `ready` last: the runner starts as soon as it sees it.
                val ready = files.remove("${PodSpecRenderer.JOB_DIR}/ready")!!
                pendingBundles[request.jobId] = files + ("${PodSpecRenderer.JOB_DIR}/ready" to ready)
                return SandboxHandle(request.jobId, name, kind)
            }
            val sandbox = res(SANDBOX).resource(directSandbox(request)).create()
            k.adoptSecret(secret.metadata.name, sandbox)
            delivered += request.jobId
            return SandboxHandle(request.jobId, name, kind)
        } catch (e: RuntimeException) {
            k.deleteJobSecret(request.jobId)
            throw if (e is SandboxLaunchFailed) e else SandboxLaunchFailed("cannot create sandbox $name: ${e.message}", e)
        }
    }

    fun templateName(pool: String, policy: ResolvedPolicy) = "bo-${Labels.value(pool)}-${k.renderer.templateHash(policy)}".take(63)

    fun ensureTemplate(pool: String, policy: ResolvedPolicy): String {
        val name = templateName(pool, policy)
        if (res(TEMPLATE).withName(name).get() != null) return name
        val podLabels = k.renderer.poolLabels(pool) + (Labels.TEMPLATE_HASH to k.renderer.templateHash(policy))
        val template = GenericKubernetesResourceBuilder()
            .withApiVersion("${TEMPLATE.group}/${TEMPLATE.version}")
            .withKind(TEMPLATE.kind)
            .withNewMetadata().withName(name).withNamespace(k.namespace).withLabels<String, String>(podLabels).endMetadata()
            .addToAdditionalProperties(
                "spec",
                mapOf(
                    "podTemplate" to mapOf(
                        "metadata" to mapOf("labels" to podLabels),
                        "spec" to toMap(k.renderer.podSpec(policy, BundleDelivery.Warm, activeDeadlineSeconds = null)),
                    ),
                    "networkPolicyManagement" to "Managed",
                    "networkPolicy" to mapOf(
                        "ingress" to emptyList<Any>(),
                        "egress" to k.egress.sandboxEgressRules(pool).map { toMap(it) },
                    ),
                    "envVarsInjectionPolicy" to "Disallowed",
                    "volumeClaimTemplatesPolicy" to "Disallowed",
                ),
            )
            .build()
        res(TEMPLATE).resource(template).createOr { it.update() }
        log.info("SandboxTemplate {} for image {} isolation {}", name, policy.image, policy.isolation)
        return name
    }

    fun ensureWarmPool(pool: String, policy: ResolvedPolicy, replicas: Int) {
        val name = templateName(pool, policy)
        val existing = res(WARM_POOL).withName(name).get()
        @Suppress("UNCHECKED_CAST")
        val current = (existing?.additionalProperties?.get("spec") as? Map<String, Any?>)?.get("replicas") as? Number
        if (existing != null && current?.toInt() == replicas) return
        val warmPool = GenericKubernetesResourceBuilder()
            .withApiVersion("${WARM_POOL.group}/${WARM_POOL.version}")
            .withKind(WARM_POOL.kind)
            .withNewMetadata().withName(name).withNamespace(k.namespace).withLabels<String, String>(k.renderer.poolLabels(pool) - Labels.SANDBOX).endMetadata()
            .addToAdditionalProperties(
                "spec",
                mapOf(
                    "replicas" to replicas,
                    "sandboxTemplateRef" to mapOf("name" to name),
                    "updateStrategy" to mapOf("type" to "Recreate"),
                ),
            )
            .build()
        res(WARM_POOL).resource(warmPool).createOr { it.update() }
    }

    fun claim(request: LaunchRequest, template: String): GenericKubernetesResource = GenericKubernetesResourceBuilder()
        .withApiVersion("${CLAIM.group}/${CLAIM.version}")
        .withKind(CLAIM.kind)
        .withNewMetadata()
        .withName(Labels.sandboxName(request.jobId))
        .withNamespace(k.namespace)
        .withLabels<String, String>(k.jobLabels(request) - Labels.SANDBOX)
        .withAnnotations<String, String>(k.jobAnnotations(request))
        .endMetadata()
        .addToAdditionalProperties(
            "spec",
            mapOf(
                "warmPoolRef" to mapOf("name" to template),
                "lifecycle" to mapOf("shutdownTime" to request.deadline.toString(), "shutdownPolicy" to "Delete"),
                // Pod label keys must be in the controller's allowed label domains; annotations are free.
                "additionalPodMetadata" to mapOf("annotations" to k.jobAnnotations(request)),
            ),
        )
        .build()

    fun directSandbox(request: LaunchRequest): GenericKubernetesResource {
        val name = Labels.sandboxName(request.jobId)
        val labels = k.jobLabels(request)
        val deadline = request.policy.timeoutSeconds.toLong() + k.props.deadlineGraceSeconds
        val podSpec = k.renderer.podSpec(request.policy, k.bundleSecrets(name, request), deadline)
        return GenericKubernetesResourceBuilder()
            .withApiVersion("${SANDBOX.group}/${SANDBOX.version}")
            .withKind(SANDBOX.kind)
            .withNewMetadata().withName(name).withNamespace(k.namespace).withLabels<String, String>(labels)
            .withAnnotations<String, String>(k.jobAnnotations(request)).endMetadata()
            .addToAdditionalProperties(
                "spec",
                mapOf(
                    "podTemplate" to mapOf(
                        "metadata" to mapOf("labels" to labels, "annotations" to k.jobAnnotations(request)),
                        "spec" to toMap(podSpec),
                    ),
                    "shutdownTime" to request.deadline.toString(),
                    "shutdownPolicy" to "Delete",
                ),
            )
            .build()
    }

    override fun observe(handle: SandboxHandle): SandboxObservation {
        val claim = res(CLAIM).withName(handle.name).get()
        if (claim != null) return observeClaim(handle, claim)
        val sandbox = res(SANDBOX).withName(handle.name).get() ?: return SandboxObservation(SandboxPhase.GONE)
        if (conditionReason(sandbox, "Ready") == "SandboxExpired") {
            return SandboxObservation(SandboxPhase.FAILED, reason = "DeadlineExceeded")
        }
        return PodStatusMapper.observe(k.client.pods().inNamespace(k.namespace).withName(handle.name).get())
            .let { if (it.phase == SandboxPhase.GONE) SandboxObservation(SandboxPhase.PENDING) else it }
    }

    private fun observeClaim(handle: SandboxHandle, claim: GenericKubernetesResource): SandboxObservation {
        if (conditionReason(claim, "Ready") == "ClaimExpired") return SandboxObservation(SandboxPhase.FAILED, reason = "DeadlineExceeded")
        @Suppress("UNCHECKED_CAST")
        val status = claim.additionalProperties["status"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sandboxName = ((status?.get("sandbox") as? Map<String, Any?>)?.get("name") as? String)
            ?: claim.metadata.annotations?.get("agents.x-k8s.io/sandbox-name")
            ?: return SandboxObservation(SandboxPhase.PENDING, bundleDelivered = false)
        val pod = k.client.pods().inNamespace(k.namespace).withName(sandboxName).get()
            ?: return SandboxObservation(SandboxPhase.PENDING, bundleDelivered = handle.jobId in delivered)
        val observed = PodStatusMapper.observe(pod, bundleDelivered = handle.jobId in delivered)
        if (observed.phase == SandboxPhase.RUNNING && handle.jobId !in delivered) {
            // Missing after a controller restart; orphaned sandboxes are deleted at start, so this is a lost job.
            val files = pendingBundles[handle.jobId] ?: return SandboxObservation(SandboxPhase.FAILED, reason = "BundleMissing")
            writer.write(k.namespace, sandboxName, PodSpecRenderer.AGENT_CONTAINER, files)
            delivered += handle.jobId
            pendingBundles.remove(handle.jobId)
            log.info("job {} bundle delivered into warm sandbox {}", handle.jobId, sandboxName)
            return observed.copy(bundleDelivered = true)
        }
        return observed
    }

    override fun delete(handle: SandboxHandle) {
        runCatching { res(CLAIM).withName(handle.name).delete() }.onFailure { log.warn("cannot delete claim {}: {}", handle.name, it.message) }
        runCatching { res(SANDBOX).withName(handle.name).delete() }
        k.deleteJobSecret(handle.jobId)
        delivered -= handle.jobId
        pendingBundles.remove(handle.jobId)
    }

    override fun listManaged(poolId: String): List<ManagedSandbox> =
        listOf(CLAIM, SANDBOX).flatMap { ctx ->
            res(ctx).withLabel(Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE).withLabel(Labels.POOL, Labels.value(poolId)).list().items
                .mapNotNull { r ->
                    val jobId = r.metadata.labels?.get(Labels.JOB_ID) ?: return@mapNotNull null
                    ManagedSandbox(SandboxHandle(jobId, r.metadata.name, kind), poolId)
                }
        }.distinctBy { it.handle.jobId }

    private fun conditionReason(resource: GenericKubernetesResource, type: String): String? {
        @Suppress("UNCHECKED_CAST")
        val conditions = (resource.additionalProperties["status"] as? Map<String, Any?>)?.get("conditions") as? List<Map<String, Any?>>
        return conditions?.firstOrNull { it["type"] == type }?.get("reason") as? String
    }

    @Suppress("UNCHECKED_CAST")
    private fun toMap(value: Any): Map<String, Any?> = k.client.kubernetesSerialization.convertValue(value, Map::class.java) as Map<String, Any?>

    companion object {
        const val CORE_GROUP = "agents.x-k8s.io"
        const val EXTENSIONS_GROUP = "extensions.agents.x-k8s.io"
        const val VERSION = "v1beta1"

        val SANDBOX: ResourceDefinitionContext = ctx(CORE_GROUP, "Sandbox", "sandboxes")
        val TEMPLATE: ResourceDefinitionContext = ctx(EXTENSIONS_GROUP, "SandboxTemplate", "sandboxtemplates")
        val WARM_POOL: ResourceDefinitionContext = ctx(EXTENSIONS_GROUP, "SandboxWarmPool", "sandboxwarmpools")
        val CLAIM: ResourceDefinitionContext = ctx(EXTENSIONS_GROUP, "SandboxClaim", "sandboxclaims")

        /** CRD names that must exist for this backend (`<plural>.<group>`). */
        val CRD_NAMES = listOf(SANDBOX, TEMPLATE, WARM_POOL, CLAIM).map { "${it.plural}.${it.group}" }

        private fun ctx(group: String, kind: String, plural: String) = ResourceDefinitionContext.Builder()
            .withGroup(group).withVersion(VERSION).withKind(kind).withPlural(plural).withNamespaced(true).build()
    }
}
