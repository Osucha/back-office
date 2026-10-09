package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.EgressToken
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import java.security.SecureRandom
import java.util.Base64

/**
 * Egress design (doc 10, "Egress"): a NetworkPolicy is pod-level, so a Smokescreen *sidecar* would need an
 * internet egress rule that the agent container shares - the allowlist would be advisory. Instead each pool
 * gets a dedicated egress proxy Deployment (`bo-egress-<pool>`, Smokescreen + per-job roles) and:
 *
 * - `bo-sandbox-<pool>` NetworkPolicy on sandbox pods: no ingress; egress only to the proxy pods on TCP 4750
 *   and to the cluster DNS pods on 53. The agent has no other route out, whatever it runs.
 * - `bo-egress-<pool>` NetworkPolicy on proxy pods: ingress only from sandbox pods of the pool (4750);
 *   egress to the internet except private ranges, plus DNS and the controller (ACL pull).
 * - Smokescreen authorises each CONNECT by the job's role (`Proxy-Authorization` = job id + per-job secret)
 *   against the job's `allowed_domains`, re-checks resolved IPs against private ranges, and reports every
 *   decision to the controller.
 */
class EgressInfrastructure(
    private val client: KubernetesClient,
    private val namespace: String,
    private val props: ControllerProperties,
    private val egressToken: EgressToken = EgressToken(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val egress = props.egress
    private val k8s = props.kubernetes

    fun proxyName(pool: String) = "bo-egress-${Labels.value(pool)}"
    fun proxyHost(pool: String) = "${proxyName(pool)}.$namespace.svc"

    private fun proxyLabels(pool: String) = mapOf(
        Labels.NAME to "back-office-egress-proxy",
        Labels.MANAGED_BY to Labels.MANAGED_BY_VALUE,
        Labels.POOL to Labels.value(pool),
    )

    private fun sandboxSelector(pool: String) = mapOf(Labels.SANDBOX to "true", Labels.POOL to Labels.value(pool))

    /**
     * Creates or updates the pool's egress resources. Returns the proxy<->controller token (from configuration
     * or from the pool Secret, generated on first use).
     */
    fun ensure(pool: String): String {
        val token = ensureTokenSecret(pool).also { egressToken.value = it }
        val name = proxyName(pool)
        val labels = proxyLabels(pool)
        val args = mutableListOf("--listen-port", egress.proxyPort.toString())
        egress.allowRanges.forEach { args += listOf("--allow-range", it) }
        egress.allowAddresses.forEach { args += listOf("--allow-address", it) }
        val deployment = DeploymentBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withReplicas(k8s.proxyReplicas)
            .withNewSelector().withMatchLabels<String, String>(labels).endSelector()
            .withNewTemplate()
            .withNewMetadata().withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withAutomountServiceAccountToken(false)
            .withEnableServiceLinks(false)
            .withNewSecurityContext().withRunAsNonRoot(true).withRunAsUser(65532L).withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile().endSecurityContext()
            .addNewContainer()
            .withName("smokescreen")
            .withImage(egress.proxyImage)
            .withArgs(args)
            .addNewEnv().withName("BO_EGRESS_MODE").withValue("dynamic").endEnv()
            .addNewEnv().withName("BO_EGRESS_CONTROLLER_URL").withValue(k8s.controllerUrl).endEnv()
            .addNewEnv().withName("BO_EGRESS_CONTROLLER_TOKEN").withNewValueFrom().withNewSecretKeyRef("token", name, false).endValueFrom().endEnv()
            .addNewPort().withName("proxy").withContainerPort(egress.proxyPort).endPort()
            .withNewReadinessProbe().withNewTcpSocket().withPort(IntOrString(egress.proxyPort)).endTcpSocket().withPeriodSeconds(5).endReadinessProbe()
            .withNewResources()
            .addToRequests("cpu", io.fabric8.kubernetes.api.model.Quantity("100m"))
            .addToRequests("memory", io.fabric8.kubernetes.api.model.Quantity("64Mi"))
            .addToLimits("memory", io.fabric8.kubernetes.api.model.Quantity("256Mi"))
            .endResources()
            .withNewSecurityContext()
            .withAllowPrivilegeEscalation(false).withReadOnlyRootFilesystem(true).withRunAsNonRoot(true)
            .withNewCapabilities().withDrop("ALL").endCapabilities()
            .endSecurityContext()
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()
        client.apps().deployments().inNamespace(namespace).resource(deployment).createOr { it.update() }

        val service = ServiceBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).withLabels<String, String>(labels).endMetadata()
            .withNewSpec()
            .withSelector<String, String>(labels)
            .addNewPort().withName("proxy").withPort(egress.proxyPort).withTargetPort(IntOrString(egress.proxyPort)).withProtocol("TCP").endPort()
            .endSpec()
            .build()
        client.services().inNamespace(namespace).resource(service).createOr { it.update() }

        client.network().v1().networkPolicies().inNamespace(namespace).resource(sandboxPolicy(pool)).createOr { it.update() }
        client.network().v1().networkPolicies().inNamespace(namespace).resource(proxyPolicy(pool)).createOr { it.update() }
        return token
    }

    /** Egress rules of sandbox pods; also used for the `networkPolicy` of every SandboxTemplate. */
    fun sandboxEgressRules(pool: String): List<NetworkPolicyEgressRule> = listOf(
        NetworkPolicyEgressRuleBuilder()
            .addToTo(NetworkPolicyPeerBuilder().withNewPodSelector().withMatchLabels<String, String>(proxyLabels(pool)).endPodSelector().build())
            .addToPorts(NetworkPolicyPortBuilder().withProtocol("TCP").withPort(IntOrString(egress.proxyPort)).build())
            .build(),
        dnsRule(),
    )

    fun sandboxPolicy(pool: String): NetworkPolicy = NetworkPolicyBuilder()
        .withNewMetadata().withName("bo-sandbox-${Labels.value(pool)}").withNamespace(namespace)
        .addToLabels(Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE).addToLabels(Labels.POOL, Labels.value(pool)).endMetadata()
        .withNewSpec()
        .withNewPodSelector().withMatchLabels<String, String>(sandboxSelector(pool)).endPodSelector()
        .withPolicyTypes("Ingress", "Egress")
        .withIngress(emptyList())
        .withEgress(sandboxEgressRules(pool))
        .endSpec()
        .build()

    fun proxyPolicy(pool: String): NetworkPolicy = NetworkPolicyBuilder()
        .withNewMetadata().withName(proxyName(pool)).withNamespace(namespace)
        .addToLabels(Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE).addToLabels(Labels.POOL, Labels.value(pool)).endMetadata()
        .withNewSpec()
        .withNewPodSelector().withMatchLabels<String, String>(proxyLabels(pool)).endPodSelector()
        .withPolicyTypes("Ingress", "Egress")
        .addNewIngress()
        .addNewFrom().withNewPodSelector().withMatchLabels<String, String>(sandboxSelector(pool)).endPodSelector().endFrom()
        .addNewPort().withProtocol("TCP").withPort(IntOrString(egress.proxyPort)).endPort()
        .endIngress()
        .addNewEgress()
        .addNewTo().withNewIpBlock().withCidr("0.0.0.0/0").withExcept(k8s.privateCidrs).endIpBlock().endTo()
        .endEgress()
        .addToEgress(
            *egress.allowRanges.map { cidr ->
                NetworkPolicyEgressRuleBuilder().addToTo(NetworkPolicyPeerBuilder().withNewIpBlock().withCidr(cidr).endIpBlock().build()).build()
            }.toTypedArray(),
        )
        .addNewEgress()
        .addNewTo().withNewPodSelector().withMatchLabels<String, String>(mapOf(Labels.NAME to "back-office-runner-controller")).endPodSelector().endTo()
        .endEgress()
        .addToEgress(dnsRule())
        .endSpec()
        .build()

    private fun dnsRule(): NetworkPolicyEgressRule = NetworkPolicyEgressRuleBuilder()
        .addToTo(
            NetworkPolicyPeerBuilder()
                .withNewNamespaceSelector().addToMatchLabels("kubernetes.io/metadata.name", k8s.dnsNamespace).endNamespaceSelector()
                .withNewPodSelector().withMatchLabels<String, String>(k8s.dnsPodLabels).endPodSelector()
                .build(),
        )
        .addToPorts(NetworkPolicyPortBuilder().withProtocol("UDP").withPort(IntOrString(53)).build())
        .addToPorts(NetworkPolicyPortBuilder().withProtocol("TCP").withPort(IntOrString(53)).build())
        .build()

    private fun ensureTokenSecret(pool: String): String {
        val name = proxyName(pool)
        val secrets = client.secrets().inNamespace(namespace)
        val existing = secrets.withName(name).get()
        val stored = existing?.data?.get("token")?.let { String(Base64.getDecoder().decode(it)) } ?: existing?.stringData?.get("token")
        val token = egress.token.ifBlank { stored ?: Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)) }
        if (stored != token) {
            val secret = SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace).withLabels<String, String>(proxyLabels(pool)).endMetadata()
                .withType("Opaque")
                .addToStringData("token", token)
                .build()
            secrets.resource(secret).createOr { it.update() }
        }
        return token
    }
}
