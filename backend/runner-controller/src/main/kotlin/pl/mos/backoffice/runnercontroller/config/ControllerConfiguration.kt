package pl.mos.backoffice.runnercontroller.config

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.mos.backoffice.runnercontroller.egress.EgressRegistry
import pl.mos.backoffice.runnercontroller.egress.EgressToken
import pl.mos.backoffice.runnercontroller.engine.JobSupervisor
import pl.mos.backoffice.runnercontroller.engine.RunnerRegistrar
import pl.mos.backoffice.runnercontroller.engine.RunnerTokenHolder
import pl.mos.backoffice.runnercontroller.policy.PolicyDefaults
import pl.mos.backoffice.runnercontroller.policy.PolicyResolver
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeClient
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.protocol.FileCredentialStore
import pl.mos.backoffice.runnercontroller.protocol.KubernetesSecretCredentialStore
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson
import pl.mos.backoffice.runnercontroller.protocol.RunnerCapabilities
import pl.mos.backoffice.runnercontroller.protocol.RunnerCredentialStore
import pl.mos.backoffice.runnercontroller.sandbox.BackendSelector
import pl.mos.backoffice.runnercontroller.sandbox.Capabilities
import pl.mos.backoffice.runnercontroller.sandbox.CapabilityDetector
import pl.mos.backoffice.runnercontroller.sandbox.SandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.docker.DockerBackend
import pl.mos.backoffice.runnercontroller.sandbox.docker.DockerCli
import pl.mos.backoffice.runnercontroller.sandbox.k8s.AgentSandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.k8s.KubernetesJobBackend
import pl.mos.backoffice.runnercontroller.sandbox.k8s.KubernetesSupport
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class ControllerConfiguration {

    @Bean
    fun egressRegistry() = EgressRegistry()

    @Bean
    fun egressToken(props: ControllerProperties) = EgressToken(props.egress.token)

    @Bean
    fun controllerRuntime(
        props: ControllerProperties,
        registry: EgressRegistry,
        egressToken: EgressToken,
        meters: MeterRegistry,
        buildProperties: ObjectProvider<BuildProperties>,
    ) = ControllerRuntime(props, registry, egressToken, meters, buildProperties.ifAvailable?.version ?: "dev")
}

/**
 * Starts the controller after the web server is up (so `/actuator/health` answers while Back Office is still
 * unreachable): detect capabilities -> choose backend -> register (once) -> fetch pool config -> run.
 * Stops gracefully: [JobSupervisor.shutdown].
 */
class ControllerRuntime(
    private val props: ControllerProperties,
    private val registry: EgressRegistry,
    private val egressToken: EgressToken,
    private val meters: MeterRegistry,
    private val version: String,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var running = false
    @Volatile var supervisor: JobSupervisor? = null
        private set
    private var bootstrapThread: Thread? = null

    override fun start() {
        running = true
        if (!props.enabled) {
            log.info("runner-controller.enabled=false: not connecting to Back Office")
            return
        }
        bootstrapThread = Thread.ofPlatform().name("controller-bootstrap").daemon(true).start { bootstrapWithRetry() }
    }

    private fun bootstrapWithRetry() {
        var delay = 2_000L
        while (running) {
            try {
                supervisor = build().also { it.start() }
                return
            } catch (e: InterruptedException) {
                return
            } catch (e: RuntimeException) {
                log.error("controller bootstrap failed: {}; retrying in {} s", e.message, delay / 1000, e)
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    return
                }
                delay = (delay * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun build(): JobSupervisor {
        val json = ProtocolJson.mapper()
        val docker = DockerCli(props.docker.executable)
        val k8sClient: KubernetesClient? = if (props.backend == BackendChoice.DOCKER) null else runCatching { KubernetesClientBuilder().build() }.getOrNull()
        val namespace = k8sClient?.let { KubernetesSupport.resolveNamespace(props.kubernetes.namespace, it.namespace) }
        val caps: Capabilities = CapabilityDetector(k8sClient, docker::available).detect(namespace)
        val selection = BackendSelector.select(props.backend, caps)
        log.info("backend {} ({}); capabilities {}", selection.kind, selection.reason, caps)

        // The credential Secret lives next to the controller, not in the sandbox namespace: agents never share a
        // namespace with the runner token, and KEDA (release namespace) can read it for queue-based scaling.
        val credentialNamespace = k8sClient?.let { KubernetesSupport.resolveNamespace(props.backOffice.credentialSecretNamespace, it.namespace) }
        val holder = RunnerTokenHolder()
        val client = BackOfficeClient(props.backOffice.url, json, holder::token, "back-office-runner-controller/$version")
        val store: RunnerCredentialStore = when (props.backOffice.credentialStore) {
            CredentialStoreKind.FILE -> FileCredentialStore(Path.of(props.backOffice.credentialFile), json)
            CredentialStoreKind.KUBERNETES_SECRET -> KubernetesSecretCredentialStore(requireNotNull(k8sClient), credentialNamespace!!, props.backOffice.credentialSecret)
            CredentialStoreKind.AUTO ->
                if (selection.kind == BackendKind.DOCKER || k8sClient == null) FileCredentialStore(Path.of(props.backOffice.credentialFile), json)
                else KubernetesSecretCredentialStore(k8sClient, credentialNamespace!!, props.backOffice.credentialSecret)
        }
        val runtimeClasses = caps.runtimeClasses?.toList() ?: props.kubernetes.runtimeClasses.values.filter { it.isNotBlank() }
        val credentials = RunnerRegistrar(client, store, holder).ensureRegistered(
            registrationToken = props.backOffice.registrationToken,
            name = props.name ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("runner-controller"),
            version = version,
            cluster = props.cluster,
            capabilities = RunnerCapabilities(BackendSelector.offered(caps), runtimeClasses, props.maxConcurrent),
        )
        registry.pool = credentials.poolId ?: "default"

        val backend: SandboxBackend = when (selection.kind) {
            BackendKind.AGENT_SANDBOX -> AgentSandboxBackend(KubernetesSupport(k8sClient!!, props, caps.runtimeClasses, egressToken))
            BackendKind.K8S_JOB -> KubernetesJobBackend(KubernetesSupport(k8sClient!!, props, caps.runtimeClasses, egressToken))
            BackendKind.DOCKER -> DockerBackend(props, docker, json)
        }
        val d = props.defaults
        val resolver = PolicyResolver(PolicyDefaults(d.image, d.cpu, d.memory, d.ephemeralStorage, d.pids, d.timeoutSeconds), props.egress.alwaysAllow)
        return JobSupervisor(props, client, backend, registry, resolver, json, meters, keys = { holder.keys })
    }

    override fun stop() {
        running = false
        bootstrapThread?.interrupt()
        supervisor?.shutdown(Duration.ofSeconds(props.shutdownGraceSeconds))
    }

    override fun isRunning(): Boolean = running

    /** Stop late (after the web server stopped accepting requests) and start last. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - 1024
}
