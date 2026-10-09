package pl.mos.backoffice.runnercontroller.sandbox

import io.fabric8.kubernetes.api.model.node.v1.RuntimeClass
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.config.BackendChoice
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.sandbox.k8s.AgentSandboxBackend

/** What the environment offers; gathered once at start. */
data class Capabilities(
    val kubernetesReachable: Boolean,
    val agentSandboxCrds: Boolean,
    /** null = unknown (listing RuntimeClasses was forbidden). */
    val runtimeClasses: Set<String>?,
    val dockerAvailable: Boolean,
)

class CapabilityDetector(private val client: KubernetesClient?, private val dockerProbe: () -> Boolean) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun detect(namespace: String?): Capabilities {
        val c = client
        val reachable = c != null && runCatching { c.kubernetesVersion != null }.getOrElse {
            log.info("kubernetes not reachable: {}", it.message)
            false
        }
        val crds = reachable && agentSandboxInstalled(namespace)
        val runtimeClasses: Set<String>? = if (!reachable || c == null) emptySet() else try {
            c.resources(RuntimeClass::class.java).list().items.map { it.metadata.name }.toSet()
        } catch (e: KubernetesClientException) {
            log.warn("cannot list RuntimeClasses ({}); RuntimeClass checks disabled", e.code)
            null
        }
        val docker = runCatching { dockerProbe() }.getOrDefault(false)
        return Capabilities(reachable, crds, runtimeClasses, docker)
    }

    private fun agentSandboxInstalled(namespace: String?): Boolean {
        val c = client!!
        return try {
            AgentSandboxBackend.CRD_NAMES.all { c.apiextensions().v1().customResourceDefinitions().withName(it).get() != null }
        } catch (e: KubernetesClientException) {
            if (e.code != 403) throw e
            // No cluster-scoped read of CRDs: probe the namespaced API instead (404 = not installed).
            try {
                c.genericKubernetesResources(AgentSandboxBackend.CLAIM).inNamespace(namespace ?: c.namespace ?: "default").list()
                true
            } catch (probe: KubernetesClientException) {
                probe.code != 404 && probe.code != 403
            }
        }
    }
}

/**
 * Chooses the sandbox backend (doc 10, "Backends"): an explicit choice must be supported by the environment;
 * `AUTO` prefers Agent Sandbox when its CRDs are installed, then plain Kubernetes Jobs, then Docker.
 */
object BackendSelector {
    data class Selection(val kind: BackendKind, val reason: String)

    fun select(choice: BackendChoice, caps: Capabilities): Selection = when (choice) {
        BackendChoice.AGENT_SANDBOX -> {
            require(caps.kubernetesReachable) { "backend AGENT_SANDBOX configured but Kubernetes is not reachable" }
            require(caps.agentSandboxCrds) { "backend AGENT_SANDBOX configured but the agent-sandbox CRDs (${AgentSandboxBackend.CRD_NAMES}) are not installed" }
            Selection(BackendKind.AGENT_SANDBOX, "configured")
        }
        BackendChoice.K8S_JOB -> {
            require(caps.kubernetesReachable) { "backend K8S_JOB configured but Kubernetes is not reachable" }
            Selection(BackendKind.K8S_JOB, "configured")
        }
        BackendChoice.DOCKER -> {
            require(caps.dockerAvailable) { "backend DOCKER configured but `docker version` failed" }
            Selection(BackendKind.DOCKER, "configured")
        }
        BackendChoice.AUTO -> when {
            caps.kubernetesReachable && caps.agentSandboxCrds -> Selection(BackendKind.AGENT_SANDBOX, "agent-sandbox CRDs found")
            caps.kubernetesReachable -> Selection(BackendKind.K8S_JOB, "Kubernetes reachable, no agent-sandbox CRDs")
            caps.dockerAvailable -> Selection(BackendKind.DOCKER, "no Kubernetes, Docker available")
            else -> throw IllegalStateException("no sandbox backend available: Kubernetes not reachable and Docker not usable")
        }
    }

    /** Backends reported at registration (`capabilities.backends`). */
    fun offered(caps: Capabilities): List<BackendKind> = buildList {
        if (caps.kubernetesReachable && caps.agentSandboxCrds) add(BackendKind.AGENT_SANDBOX)
        if (caps.kubernetesReachable) add(BackendKind.K8S_JOB)
        if (caps.dockerAvailable) add(BackendKind.DOCKER)
    }
}
