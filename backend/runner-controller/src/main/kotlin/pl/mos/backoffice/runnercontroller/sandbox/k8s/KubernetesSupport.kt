package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.EgressToken
import pl.mos.backoffice.runnercontroller.git.GitCredentialSelector
import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import pl.mos.backoffice.runnercontroller.sandbox.LaunchRequest
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Shared plumbing of the Kubernetes backends: namespace, labels, job Secrets, git credentials, RuntimeClass checks. */
class KubernetesSupport(
    val client: KubernetesClient,
    val props: ControllerProperties,
    /** RuntimeClasses found in the cluster; null = could not be listed (no RBAC), checks are skipped. */
    val availableRuntimeClasses: Set<String>?,
    val egressToken: EgressToken = EgressToken(),
) {
    val namespace: String = resolveNamespace(props.kubernetes.namespace, client.namespace)
    val renderer = PodSpecRenderer(props.kubernetes, props.git)
    val egress = EgressInfrastructure(client, namespace, props, egressToken)

    fun jobLabels(request: LaunchRequest): Map<String, String> = buildMap {
        putAll(renderer.poolLabels(request.poolId))
        put(Labels.JOB_ID, request.jobId)
        put(Labels.ISOLATION, request.policy.isolation.name)
        request.projectId?.let { put(Labels.PROJECT_ID, Labels.value(it)) }
        request.runId?.let { put(Labels.RUN_ID, Labels.value(it)) }
    }

    fun jobAnnotations(request: LaunchRequest): Map<String, String> = buildMap {
        put(Labels.JOB_ID, request.jobId)
        put(Labels.POOL, request.poolId)
        put(Labels.DEADLINE, request.deadline.toString())
        put(Labels.GIT_ACCESS, request.gitAccess.name)
        if (request.repositoryAccess.isNotEmpty()) {
            put(Labels.GIT_REPOSITORIES, request.repositoryAccess.entries.joinToString(",") { "${it.key}=${it.value}" }.take(2000))
        }
        request.projectId?.let { put(Labels.PROJECT_ID, it) }
        request.runId?.let { put(Labels.RUN_ID, it) }
        if (request.policy.notes.isNotEmpty()) put(Labels.POLICY_NOTES, request.policy.notes.joinToString("; ").take(2000))
    }

    /** Refuses isolation levels whose RuntimeClass is missing (unless `requireRuntimeClass=false`). */
    fun checkRuntimeClass(request: LaunchRequest) {
        val rc = renderer.runtimeClassFor(request.policy.isolation) ?: return
        val available = availableRuntimeClasses ?: return
        if (props.kubernetes.requireRuntimeClass && rc !in available) {
            throw PolicyViolation("isolation ${request.policy.isolation} needs RuntimeClass '$rc', which is not installed in this cluster")
        }
    }

    fun proxyUrl(request: LaunchRequest): String =
        "http://${request.proxy.username}:${request.proxy.password}@${egress.proxyHost(request.poolId)}:${props.egress.proxyPort}"

    fun createJobSecret(request: LaunchRequest, proxyUrl: String): Secret {
        val secret = SecretBuilder()
            .withNewMetadata()
            .withName(Labels.sandboxName(request.jobId))
            .withNamespace(namespace)
            .withLabels<String, String>(jobLabels(request) - Labels.SANDBOX)
            .withAnnotations<String, String>(jobAnnotations(request))
            .endMetadata()
            .withType("Opaque")
            .withImmutable(true)
            .withStringData<String, String>(request.bundle.files(proxyUrl))
            .build()
        return client.secrets().inNamespace(namespace).resource(secret).create()
    }

    fun adoptSecret(secretName: String, owner: HasMetadata) {
        val ref = OwnerReferenceBuilder()
            .withApiVersion(owner.apiVersion)
            .withKind(owner.kind)
            .withName(owner.metadata.name)
            .withUid(owner.metadata.uid)
            .withBlockOwnerDeletion(false)
            .build()
        runCatching {
            client.secrets().inNamespace(namespace).withName(secretName).edit { s ->
                s.metadata.ownerReferences = (s.metadata.ownerReferences.orEmpty() + ref)
                s
            }
        }
    }

    /** Git Secrets by repository and level (ADR 0031): `git.repo-secrets`, `git.secrets` as push-level fallback. */
    val gitCredentials = GitCredentialSelector.forSecrets(props.git)

    /** Value (key `token`) of the git Secret [name] selected for a job; null when there is none. */
    fun gitToken(name: String?): String? {
        if (name == null) return null
        val secret = client.secrets().inNamespace(namespace).withName(name).get() ?: return null
        return secret.data?.get("token")?.let { String(Base64.getDecoder().decode(it)) } ?: secret.stringData?.get("token")
    }

    /** Cold start delivery: the job Secret plus only the git Secrets selected for this job (legacy or per repository). */
    fun bundleSecrets(jobSecret: String, request: LaunchRequest) = BundleDelivery.Secrets(
        jobSecret,
        request.gitCredential,
        request.repositoryTokens.map { BundleDelivery.RepositorySecret(it.key, it.credential) },
    )

    /** Warm delivery: `git/token` (legacy) or `git/<key>/token` per repository, read from the selected Secrets. */
    fun gitTokenFiles(request: LaunchRequest): Map<String, String> = buildMap {
        gitToken(request.gitCredential)?.let { put("${PodSpecRenderer.GIT_DIR}/token", it) }
        request.repositoryTokens.forEach { repo -> gitToken(repo.credential)?.let { put("${PodSpecRenderer.GIT_DIR}/${repo.key}/token", it) } }
    }

    fun deleteJobSecret(jobId: String) {
        runCatching { client.secrets().inNamespace(namespace).withName(Labels.sandboxName(jobId)).delete() }
    }

    companion object {
        const val NAMESPACE_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"

        fun resolveNamespace(configured: String, clientNamespace: String?): String {
            if (configured.isNotBlank()) return configured
            val fromFile = runCatching { Files.readString(Path.of(NAMESPACE_FILE)).trim() }.getOrNull()
            if (!fromFile.isNullOrBlank()) return fromFile
            return clientNamespace?.takeIf { it.isNotBlank() } ?: "default"
        }
    }
}
