package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.node.v1.RuntimeClassBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.config.BackendChoice
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.EgressToken
import pl.mos.backoffice.runnercontroller.git.GitAccess
import pl.mos.backoffice.runnercontroller.git.RepoCredential
import pl.mos.backoffice.runnercontroller.policy.Isolation
import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.sandbox.BackendSelector
import pl.mos.backoffice.runnercontroller.sandbox.CapabilityDetector
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import pl.mos.backoffice.runnercontroller.sandbox.PoolSettings
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase
import pl.mos.backoffice.runnercontroller.sandbox.TestPolicies
import java.util.Base64

private const val NS = "agents"

private fun props(warm: Int? = null) = ControllerProperties(
    kubernetes = ControllerProperties.Kubernetes(namespace = NS, warmPoolSize = warm),
    git = ControllerProperties.Git(
        repoSecrets = listOf(RepoCredential("gitlab.example.com/g/*", read = "git-gitlab-read", push = "git-gitlab-push")),
        secrets = mapOf("gitlab.example.com" to "git-gitlab"),
    ),
    egress = ControllerProperties.Egress(proxyImage = "back-office/egress-proxy:1", allowRanges = listOf("10.96.0.10/32")),
)

private fun decode(data: Map<String, String>?) = data.orEmpty().mapValues { String(Base64.getDecoder().decode(it.value)) }

@EnableKubernetesMockClient(crud = true)
class KubernetesJobBackendTest {
    lateinit var client: KubernetesClient
    lateinit var server: KubernetesMockServer

    private fun backend(runtimeClasses: Set<String>? = setOf("gvisor", "kata")) = KubernetesJobBackend(KubernetesSupport(client, props(), runtimeClasses, EgressToken()))

    @Test
    fun `ensurePool creates the egress proxy, its token and both NetworkPolicies`() {
        val token = EgressToken()
        val b = KubernetesJobBackend(KubernetesSupport(client, props(), null, token))
        b.ensurePool(PoolSettings("pool-a", 0, null))
        val deployment = client.apps().deployments().inNamespace(NS).withName("bo-egress-pool-a").get()
        val container = deployment.spec.template.spec.containers.single()
        assertThat(container.image).isEqualTo("back-office/egress-proxy:1")
        assertThat(container.args).containsSequence("--listen-port", "4750").containsSequence("--allow-range", "10.96.0.10/32")
        assertThat(container.env.first { it.name == "BO_EGRESS_MODE" }.value).isEqualTo("dynamic")
        assertThat(container.securityContext.readOnlyRootFilesystem).isTrue()
        assertThat(deployment.spec.template.spec.automountServiceAccountToken).isFalse()
        assertThat(client.services().inNamespace(NS).withName("bo-egress-pool-a").get().spec.ports.single().port).isEqualTo(4750)
        assertThat(token.value).isNotBlank()
        val tokenSecret = client.secrets().inNamespace(NS).withName("bo-egress-pool-a").get()
        assertThat(tokenSecret.stringData?.get("token") ?: decode(tokenSecret.data)["token"]).isEqualTo(token.value)

        val sandboxNp = client.network().v1().networkPolicies().inNamespace(NS).withName("bo-sandbox-pool-a").get()
        assertThat(sandboxNp.spec.podSelector.matchLabels).containsEntry(Labels.SANDBOX, "true").containsEntry(Labels.POOL, "pool-a")
        assertThat(sandboxNp.spec.policyTypes).containsExactly("Ingress", "Egress")
        assertThat(sandboxNp.spec.ingress).isEmpty()
        assertThat(sandboxNp.spec.egress).hasSize(2)
        val toProxy = sandboxNp.spec.egress[0]
        assertThat(toProxy.to.single().podSelector.matchLabels).containsEntry(Labels.NAME, "back-office-egress-proxy")
        assertThat(toProxy.ports.single().port.intVal).isEqualTo(4750)
        assertThat(sandboxNp.spec.egress[1].ports.map { it.port.intVal }).containsExactly(53, 53)
        assertThat(sandboxNp.spec.egress.flatMap { it.to }).noneMatch { it.ipBlock != null }

        val proxyNp = client.network().v1().networkPolicies().inNamespace(NS).withName("bo-egress-pool-a").get()
        assertThat(proxyNp.spec.ingress.single().from.single().podSelector.matchLabels).containsEntry(Labels.SANDBOX, "true")
        assertThat(proxyNp.spec.egress[0].to.single().ipBlock.except).contains("10.0.0.0/8", "169.254.0.0/16")

        // idempotent, token kept
        val first = token.value
        b.ensurePool(PoolSettings("pool-a", 0, null))
        assertThat(token.value).isEqualTo(first)
    }

    @Test
    fun `only the Secret of the job's git access level is mounted (ADR 0031)`() {
        val b = backend()
        fun gitSecretOf(access: GitAccess, jobId: String): String? {
            val selected = b.gitCredentials.select("https://gitlab.example.com/g/app.git", access)
            val job = b.buildJob(TestPolicies.launch(jobId = jobId, gitAccess = selected.access, gitCredential = selected.credential))
            assertThat(job.metadata.annotations).containsEntry(Labels.GIT_ACCESS, access.name)
            return job.spec.template.spec.volumes.firstOrNull { it.name == "git-secret" }?.secret?.secretName
        }
        assertThat(gitSecretOf(GitAccess.READ, "11111111-2222-4333-8444-000000000001")).isEqualTo("git-gitlab-read")
        assertThat(gitSecretOf(GitAccess.PUSH_MR, "11111111-2222-4333-8444-000000000002")).isEqualTo("git-gitlab-push")
        assertThat(gitSecretOf(GitAccess.NONE, "11111111-2222-4333-8444-000000000003")).isNull()
        // a repository outside repoSecrets falls back to the push-level hostSecrets entry, for PUSH_MR only
        assertThat(b.gitCredentials.select("https://gitlab.example.com/other/x.git", GitAccess.PUSH_MR).credential).isEqualTo("git-gitlab")
        assertThatThrownBy { b.gitCredentials.select("https://gitlab.example.com/other/x.git", GitAccess.READ) }.isInstanceOf(PolicyViolation::class.java)
    }

    @Test
    fun `multi-repository job - one Secret per repository that has a credential, nothing else`() {
        val b = backend()
        val job = b.buildJob(TestPolicies.multiRepoLaunch())
        val pod = job.spec.template.spec
        assertThat(pod.volumes.mapNotNull { it.secret?.secretName }).containsExactly("bo-job-${TestPolicies.multiRepoLaunch().jobId}", "git-docs-read", "git-src-push")
        assertThat(pod.volumes.map { it.name }).doesNotContain("git-secret")
        assertThat(pod.initContainers.single().volumeMounts.map { it.mountPath })
            .contains("/var/run/backoffice/git-repo-secrets/docs", "/var/run/backoffice/git-repo-secrets/src")
            .doesNotContain("/var/run/backoffice/git-repo-secrets/tools")
        assertThat(job.metadata.annotations).containsEntry(Labels.GIT_ACCESS, "PUSH_MR")
            .containsEntry(Labels.GIT_REPOSITORIES, "docs=READ,src=PUSH_MR,tools=NONE")
    }

    @Test
    fun `launch creates the job Secret and a locked-down Job, observe and delete`() {
        val b = backend()
        val req = TestPolicies.launch()
        val handle = b.launch(req)
        assertThat(handle.name).isEqualTo("bo-job-${req.jobId}")

        val secret = client.secrets().inNamespace(NS).withName(handle.name).get()
        val files = secret.stringData ?: decode(secret.data)
        assertThat(files.keys).containsExactly("spec.json", "policy.json", "job-id", "job-token", "proxy-url", "ready")
        assertThat(files["job-token"]).isEqualTo("bo_job_secret")
        assertThat(files["proxy-url"]).isEqualTo("http://${req.jobId}:proxy-pw@bo-egress-pool-a.$NS.svc:4750")
        assertThat(secret.immutable).isTrue()

        val job = client.batch().v1().jobs().inNamespace(NS).withName(handle.name).get()
        assertThat(job.spec.backoffLimit).isEqualTo(0)
        assertThat(job.spec.activeDeadlineSeconds).isEqualTo(1800L + 120)
        assertThat(job.metadata.labels).containsEntry(Labels.JOB_ID, req.jobId).containsEntry(Labels.POOL, "pool-a")
            .containsEntry(Labels.PROJECT_ID, req.projectId).containsEntry(Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE)
        val pod = job.spec.template
        assertThat(pod.metadata.labels).containsEntry(Labels.SANDBOX, "true")
        assertThat(pod.spec.runtimeClassName).isEqualTo("gvisor")
        assertThat(pod.spec.volumes.first { it.name == "git-secret" }.secret.secretName).isEqualTo("git-gitlab")
        assertThat(pod.spec.containers.single().securityContext.readOnlyRootFilesystem).isTrue()

        assertThat(b.observe(handle).phase).isEqualTo(SandboxPhase.PENDING)
        client.pods().inNamespace(NS).resource(
            PodBuilder().withNewMetadata().withName("${handle.name}-x").withNamespace(NS).addToLabels(Labels.JOB_ID, req.jobId).endMetadata()
                .withNewStatus().withPhase("Failed").addNewContainerStatus().withName("agent").withNewState().withNewTerminated()
                .withExitCode(137).withReason("OOMKilled").endTerminated().endState().endContainerStatus().endStatus().build(),
        ).create()
        val observed = b.observe(handle)
        assertThat(observed.phase).isEqualTo(SandboxPhase.FAILED)
        assertThat(observed.reason).isEqualTo("OOMKilled")

        assertThat(b.listManaged("pool-a").map { it.handle.jobId }).containsExactly(req.jobId)
        assertThat(b.listManaged("pool-b")).isEmpty()
        b.delete(handle)
        assertThat(client.batch().v1().jobs().inNamespace(NS).withName(handle.name).get()).isNull()
        assertThat(client.secrets().inNamespace(NS).withName(handle.name).get()).isNull()
        assertThat(b.observe(handle).phase).isEqualTo(SandboxPhase.GONE)
    }

    @Test
    fun `missing RuntimeClass is a policy violation`() {
        val b = backend(runtimeClasses = setOf("gvisor"))
        assertThatThrownBy { b.launch(TestPolicies.launch(policy = TestPolicies.resolved(Isolation.HIGH))) }
            .isInstanceOf(PolicyViolation::class.java).hasMessageContaining("kata")
        // unknown (no RBAC to list) -> not checked
        backend(runtimeClasses = null).launch(TestPolicies.launch(jobId = "22222222-2222-4222-8222-222222222222", policy = TestPolicies.resolved(Isolation.HIGH)))
    }

    @Test
    fun `capability detection without CRDs selects K8S_JOB and reads RuntimeClasses`() {
        client.resources(io.fabric8.kubernetes.api.model.node.v1.RuntimeClass::class.java)
            .resource(RuntimeClassBuilder().withNewMetadata().withName("gvisor").endMetadata().withHandler("runsc").build()).create()
        val caps = CapabilityDetector(client) { false }.detect(NS)
        assertThat(caps.kubernetesReachable).isTrue()
        assertThat(caps.agentSandboxCrds).isFalse()
        assertThat(caps.runtimeClasses).containsExactly("gvisor")
        assertThat(BackendSelector.select(BackendChoice.AUTO, caps).kind).isEqualTo(BackendKind.K8S_JOB)
        assertThatThrownBy { BackendSelector.select(BackendChoice.AGENT_SANDBOX, caps) }.hasMessageContaining("CRDs")
        assertThat(BackendSelector.offered(caps)).containsExactly(BackendKind.K8S_JOB)
    }
}

@EnableKubernetesMockClient(crud = true)
class AgentSandboxBackendTest {
    lateinit var client: KubernetesClient
    lateinit var server: KubernetesMockServer

    private val written = mutableListOf<Triple<String, String, Map<String, String>>>()

    @BeforeEach
    fun crds() {
        listOf(AgentSandboxBackend.SANDBOX, AgentSandboxBackend.TEMPLATE, AgentSandboxBackend.WARM_POOL, AgentSandboxBackend.CLAIM).forEach { ctx ->
            client.apiextensions().v1().customResourceDefinitions().resource(crd(ctx)).create()
        }
    }

    private fun crd(ctx: ResourceDefinitionContext) = CustomResourceDefinitionBuilder()
        .withNewMetadata().withName("${ctx.plural}.${ctx.group}").endMetadata()
        .withNewSpec().withGroup(ctx.group).withScope("Namespaced")
        .withNewNames().withKind(ctx.kind).withPlural(ctx.plural).endNames()
        .addNewVersion().withName(ctx.version).withServed(true).withStorage(true)
        .withNewSchema().withNewOpenAPIV3Schema().withType("object").withXKubernetesPreserveUnknownFields(true).endOpenAPIV3Schema().endSchema()
        .endVersion().endSpec().build()

    private fun backend(warm: Int?) = AgentSandboxBackend(
        KubernetesSupport(client, props(warm), setOf("gvisor", "kata"), EgressToken()),
        writer = { ns, pod, container, files -> written += Triple("$ns/$pod", container, files) },
    )

    private fun res(ctx: ResourceDefinitionContext) = client.genericKubernetesResources(ctx).inNamespace(NS)

    @Suppress("UNCHECKED_CAST")
    private fun spec(r: GenericKubernetesResource) = r.additionalProperties["spec"] as Map<String, Any?>

    @Test
    fun `capability detection with the v1beta1 CRDs selects AGENT_SANDBOX`() {
        val caps = CapabilityDetector(client) { true }.detect(NS)
        assertThat(caps.agentSandboxCrds).isTrue()
        assertThat(BackendSelector.select(BackendChoice.AUTO, caps).kind).isEqualTo(BackendKind.AGENT_SANDBOX)
        assertThat(BackendSelector.offered(caps)).containsExactly(BackendKind.AGENT_SANDBOX, BackendKind.K8S_JOB, BackendKind.DOCKER)
        assertThat(AgentSandboxBackend.CRD_NAMES).containsExactly(
            "sandboxes.agents.x-k8s.io", "sandboxtemplates.extensions.agents.x-k8s.io",
            "sandboxwarmpools.extensions.agents.x-k8s.io", "sandboxclaims.extensions.agents.x-k8s.io",
        )
    }

    @Test
    fun `warm pool - template, warm pool, claim, and bundle delivery after binding`() {
        val b = backend(warm = 2)
        val policy = TestPolicies.resolved()
        b.ensurePool(PoolSettings("pool-a", 0, policy))
        val templateName = b.templateName("pool-a", policy)

        val template = res(AgentSandboxBackend.TEMPLATE).withName(templateName).get()
        assertThat(template.apiVersion).isEqualTo("extensions.agents.x-k8s.io/v1beta1")
        val tSpec = spec(template)
        assertThat(tSpec["networkPolicyManagement"]).isEqualTo("Managed")
        assertThat(tSpec["envVarsInjectionPolicy"]).isEqualTo("Disallowed")
        @Suppress("UNCHECKED_CAST")
        val np = tSpec["networkPolicy"] as Map<String, Any?>
        assertThat(np["ingress"] as List<*>).isEmpty()
        assertThat((np["egress"] as List<*>)).hasSize(2)
        assertThat(np.toString()).doesNotContain("0.0.0.0/0")
        @Suppress("UNCHECKED_CAST")
        val podSpec = (tSpec["podTemplate"] as Map<String, Any?>)["spec"] as Map<String, Any?>
        assertThat(podSpec["runtimeClassName"]).isEqualTo("gvisor")
        assertThat(podSpec["automountServiceAccountToken"]).isEqualTo(false)
        assertThat(podSpec.toString()).contains("RUNNER_WAIT_FOR_JOB, value=1").contains("medium=Memory")

        val pool = res(AgentSandboxBackend.WARM_POOL).withName(templateName).get()
        assertThat(spec(pool)["replicas"]).isEqualTo(2)
        assertThat(spec(pool)["sandboxTemplateRef"]).isEqualTo(mapOf("name" to templateName))

        client.secrets().inNamespace(NS).resource(
            SecretBuilder().withNewMetadata().withName("git-gitlab").endMetadata().addToStringData("token", "glpat-123").build(),
        ).create()
        val req = TestPolicies.launch(policy = policy)
        val handle = b.launch(req)
        val claim =res(AgentSandboxBackend.CLAIM).withName(handle.name).get()
        assertThat(claim.kind).isEqualTo("SandboxClaim")
        assertThat(spec(claim)["warmPoolRef"]).isEqualTo(mapOf("name" to templateName))
        assertThat(spec(claim)["lifecycle"]).isEqualTo(mapOf("shutdownTime" to "2026-09-28T11:00:00Z", "shutdownPolicy" to "Delete"))
        assertThat(spec(claim).toString()).doesNotContain("bo_job_secret")
        assertThat(client.secrets().inNamespace(NS).withName(handle.name).get()).isNotNull()

        // not bound yet
        assertThat(b.observe(handle).phase).isEqualTo(SandboxPhase.PENDING)
        assertThat(written).isEmpty()

        // the agent-sandbox controller binds a warm sandbox and the pod runs
        claim.additionalProperties["status"] = mapOf("sandbox" to mapOf("name" to "bo-pool-a-warm-1"))
        res(AgentSandboxBackend.CLAIM).resource(claim).update()
        client.pods().inNamespace(NS).resource(
            PodBuilder().withNewMetadata().withName("bo-pool-a-warm-1").endMetadata().withNewStatus().withPhase("Running")
                .addNewContainerStatus().withName("agent").withNewState().withNewRunning().endRunning().endState().endContainerStatus().endStatus().build(),
        ).create()
        val observed = b.observe(handle)
        assertThat(observed.phase).isEqualTo(SandboxPhase.RUNNING)
        assertThat(observed.bundleDelivered).isTrue()
        assertThat(written).hasSize(1)
        val (target, container, files) = written.single()
        assertThat(target).isEqualTo("$NS/bo-pool-a-warm-1")
        assertThat(container).isEqualTo("agent")
        assertThat(files.keys.last()).isEqualTo("/var/run/backoffice/job/ready")
        assertThat(files).containsEntry("/var/run/backoffice/job/job-token", "bo_job_secret")
        assertThat(files).containsEntry("/var/run/backoffice/git/token", "glpat-123")
        b.observe(handle)
        assertThat(written).hasSize(1) // delivered once

        assertThat(b.listManaged("pool-a").map { it.handle.jobId }).containsExactly(req.jobId)
        b.delete(handle)
        assertThat(res(AgentSandboxBackend.CLAIM).withName(handle.name).get()).isNull()
        assertThat(b.observe(handle).phase).isEqualTo(SandboxPhase.GONE)
    }

    @Test
    fun `warm pool - a multi-repository job gets one token file per repository that has a credential`() {
        val b = backend(warm = 1)
        val policy = TestPolicies.resolved()
        b.ensurePool(PoolSettings("pool-a", 0, policy))
        for ((name, token) in listOf("git-docs-read" to "glpat-docs", "git-src-push" to "glpat-src", "git-gitlab" to "glpat-legacy")) {
            client.secrets().inNamespace(NS).resource(SecretBuilder().withNewMetadata().withName(name).endMetadata().addToStringData("token", token).build()).create()
        }
        val handle = b.launch(TestPolicies.multiRepoLaunch(policy = policy))
        val claim = res(AgentSandboxBackend.CLAIM).withName(handle.name).get()
        claim.additionalProperties["status"] = mapOf("sandbox" to mapOf("name" to "bo-pool-a-warm-9"))
        res(AgentSandboxBackend.CLAIM).resource(claim).update()
        client.pods().inNamespace(NS).resource(
            PodBuilder().withNewMetadata().withName("bo-pool-a-warm-9").endMetadata().withNewStatus().withPhase("Running")
                .addNewContainerStatus().withName("agent").withNewState().withNewRunning().endRunning().endState().endContainerStatus().endStatus().build(),
        ).create()
        b.observe(handle)
        val files = written.single().third
        assertThat(files).containsEntry("/var/run/backoffice/git/docs/token", "glpat-docs")
            .containsEntry("/var/run/backoffice/git/src/token", "glpat-src")
        assertThat(files.keys).doesNotContain("/var/run/backoffice/git/token", "/var/run/backoffice/git/tools/token")
        assertThat(files.keys.last()).isEqualTo("/var/run/backoffice/job/ready")
    }

    @Test
    fun `no warm pool - a cold Sandbox with the Secret mounted`() {
        val b = backend(warm = 0)
        b.ensurePool(PoolSettings("pool-a", 0, TestPolicies.resolved()))
        assertThat(res(AgentSandboxBackend.WARM_POOL).list().items).isEmpty()
        val req = TestPolicies.launch()
        val handle = b.launch(req)
        val sandbox = res(AgentSandboxBackend.SANDBOX).withName(handle.name).get()
        assertThat(sandbox.apiVersion).isEqualTo("agents.x-k8s.io/v1beta1")
        val s = spec(sandbox)
        assertThat(s["shutdownTime"]).isEqualTo("2026-09-28T11:00:00Z")
        assertThat(s["shutdownPolicy"]).isEqualTo("Delete")
        @Suppress("UNCHECKED_CAST")
        val podTemplate = s["podTemplate"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        assertThat((podTemplate["metadata"] as Map<String, Any?>)["labels"] as Map<String, String>).containsEntry(Labels.SANDBOX, "true")
            .containsEntry(Labels.JOB_ID, req.jobId)
        assertThat(podTemplate["spec"].toString()).contains("secretName=${handle.name}").contains("activeDeadlineSeconds=1920")

        // pod name = sandbox name
        client.pods().inNamespace(NS).resource(
            PodBuilder().withNewMetadata().withName(handle.name).endMetadata().withNewStatus().withPhase("Succeeded")
                .addNewContainerStatus().withName("agent").withNewState().withNewTerminated().withExitCode(0).endTerminated().endState().endContainerStatus().endStatus().build(),
        ).create()
        assertThat(b.observe(handle).phase).isEqualTo(SandboxPhase.SUCCEEDED)
        assertThat(written).isEmpty()
    }
}
