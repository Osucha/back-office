package pl.mos.backoffice.runnercontroller.engine

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.EgressDecision
import pl.mos.backoffice.runnercontroller.egress.EgressRegistry
import pl.mos.backoffice.runnercontroller.fake.FakeBackOffice
import pl.mos.backoffice.runnercontroller.fake.strings
import pl.mos.backoffice.runnercontroller.git.GitAccess
import pl.mos.backoffice.runnercontroller.git.GitCredentialSelector
import pl.mos.backoffice.runnercontroller.git.RepoCredential
import pl.mos.backoffice.runnercontroller.git.RepositoryToken
import pl.mos.backoffice.runnercontroller.policy.PolicyDefaults
import pl.mos.backoffice.runnercontroller.policy.PolicyResolver
import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeClient
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.protocol.FileCredentialStore
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson
import pl.mos.backoffice.runnercontroller.protocol.RunnerCapabilities
import pl.mos.backoffice.runnercontroller.sandbox.LaunchRequest
import pl.mos.backoffice.runnercontroller.sandbox.ManagedSandbox
import pl.mos.backoffice.runnercontroller.sandbox.PoolSettings
import pl.mos.backoffice.runnercontroller.sandbox.SandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.SandboxHandle
import pl.mos.backoffice.runnercontroller.sandbox.SandboxObservation
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** In-memory backend: tests set the observed state per job. */
class FakeBackend : SandboxBackend {
    override var kind = BackendKind.K8S_JOB
    override var gitCredentials = GitCredentialSelector.EMPTY
    val launched = ConcurrentHashMap<String, LaunchRequest>()
    val state = ConcurrentHashMap<String, SandboxObservation>()
    val deleted = ConcurrentHashMap.newKeySet<String>()
    val orphans = mutableListOf<ManagedSandbox>()
    var failLaunch: RuntimeException? = null
    var decisions: List<EgressDecision> = emptyList()
    var pools = mutableListOf<PoolSettings>()

    override fun ensurePool(pool: PoolSettings) {
        pools += pool
    }

    override fun launch(request: LaunchRequest): SandboxHandle {
        failLaunch?.let { throw it }
        launched[request.jobId] = request
        state[request.jobId] = SandboxObservation(SandboxPhase.PENDING)
        return SandboxHandle(request.jobId, "bo-job-${request.jobId}", kind)
    }

    override fun observe(handle: SandboxHandle) = state[handle.jobId] ?: SandboxObservation(SandboxPhase.GONE)
    override fun delete(handle: SandboxHandle) {
        deleted += handle.jobId
        state.remove(handle.jobId)
    }

    override fun listManaged(poolId: String) = orphans.toList()
    override fun egressDecisions(handle: SandboxHandle) = decisions.filter { it.role == handle.jobId }
    override fun proxyUrl(request: LaunchRequest) = "http://egress:4750"
}

class JobSupervisorTest {
    private val bo = FakeBackOffice()
    private val json = ProtocolJson.mapper()
    private val backend = FakeBackend()
    private val registry = EgressRegistry("test-pool")
    private val holder = RunnerTokenHolder()
    private val client = BackOfficeClient(bo.url, json, holder::token)
    private val props = ControllerProperties(maxConcurrent = 2, heartbeatSeconds = 0, lease = ControllerProperties.Lease(waitSeconds = 1))
    private val meters = SimpleMeterRegistry()
    private val controllerEnv = mutableMapOf<String, String>()
    private val supervisor = JobSupervisor(
        props, client, backend, registry, PolicyResolver(PolicyDefaults(image = "back-office/runner:dev")), json, meters,
        env = { controllerEnv[it] },
        keys = { holder.keys },
    )

    private val spec = mapOf(
        "mode" to "EVAL_CODING", "agent" to "OPENCODE", "prompt" to "do it", "timeoutSeconds" to 7200,
        "model" to mapOf("provider" to "ANTHROPIC", "modelId" to "claude-opus-5"),
        "repository" to mapOf("url" to "https://gitlab.example.com/g/app.git", "baseRef" to "main"),
        "runId" to "0b8b1c2e-0000-4000-8000-000000000002",
    )
    private val policy = mapOf("network" to mapOf("allow" to listOf("registry.npmjs.org")), "tools" to mapOf("deny" to listOf("curl *")))

    private val credentialsFile = Files.createTempDirectory("rc").resolve("creds.json")

    private fun register() {
        val store = FileCredentialStore(credentialsFile, json)
        RunnerRegistrar(client, store, holder).ensureRegistered(bo.registrationToken, "test", "dev", null, RunnerCapabilities(listOf(BackendKind.K8S_JOB), emptyList(), 2))
        supervisor.bootstrap()
    }

    private fun leaseOnce() = client.lease(pl.mos.backoffice.runnercontroller.protocol.LeaseRequest(2, 1, 2)).forEach { supervisor.accept(it) }

    @AfterEach
    fun close() = bo.close()

    /** ADR 0051: a multi-repository spec naming one project token. */
    private val sealedSpec = spec - "repository" + mapOf(
        "repositories" to listOf(mapOf("key" to "main", "url" to "https://gitlab.example.com/g/app.git", "baseBranch" to "main", "access" to "READ", "primary" to true)),
        "credentials" to listOf(mapOf("ref" to "ref-1", "repository" to "main", "capability" to "GIT_READ")),
    )

    @Test
    fun `project tokens named in the signed spec are fetched sealed, opened with the controller's key and delivered instead of the pool's`() {
        controllerEnv["BO_GIT_TOKEN_READ"] = "tkn-pool-read"
        register()
        val stored = json.readTree(Files.readString(credentialsFile))
        assertThat(stored.get("publicKey").asString()).isEqualTo(bo.runnerPublicKey).isEqualTo(holder.keys!!.publicKey)
        assertThat(stored.get("privateKey").asString()).isNotBlank()
        // a restart keeps the key pair and registers the same public key again
        val holder2 = RunnerTokenHolder()
        RunnerRegistrar(BackOfficeClient(bo.url, json, holder2::token), FileCredentialStore(credentialsFile, json), holder2)
            .ensureRegistered(bo.registrationToken, "n", "v", null, RunnerCapabilities(emptyList(), emptyList(), 1))
        assertThat(holder2.keys!!.publicKey).isEqualTo(holder.keys!!.publicKey)

        val jobId = bo.enqueue(sealedSpec, policy)
        bo.projectTokens[jobId] = mapOf("ref-1" to ("main" to "tkn-project-read"))
        leaseOnce()
        val launched = backend.launched.getValue(jobId)
        assertThat(launched.projectTokens.byRepository).containsExactlyEntriesOf(mapOf("main" to "tkn-project-read"))
        assertThat(launched.repositoryTokens).isEmpty() // the pool's token is not used for this repository
        assertThat(launched.toString()).doesNotContain("tkn-project-read")
        assertThat(bo.calls.single { it.path.endsWith("/$jobId/credentials") }.body).doesNotContain("tkn-")
    }

    @Test
    fun `ADR 0051 step 4 - a CI_READ token named twice with the clone token goes to the agent tool, not to git`() {
        register()
        val toolSpec = sealedSpec + mapOf(
            "credentials" to listOf(mapOf("ref" to "ref-1", "repository" to "main", "capability" to "GIT_READ"), mapOf("ref" to "ref-1", "repository" to "main", "capability" to "CI_READ")),
            "agentTools" to listOf(mapOf("name" to "gitlab-read", "repository" to "main")),
        )
        val jobId = bo.enqueue(toolSpec, policy)
        bo.projectTokens[jobId] = mapOf("ref-1" to ("main" to "tkn-reader"))
        bo.credentialCapabilities = listOf("GIT_READ", "CI_READ")
        leaseOnce()
        val launched = backend.launched.getValue(jobId)
        assertThat(launched.projectTokens.byRepository).containsExactlyEntriesOf(mapOf("main" to "tkn-reader"))
        assertThat(launched.projectTokens.tools).containsExactlyEntriesOf(mapOf("main" to "tkn-reader"))
        assertThat(launched.projectTokens.secretEntries()).containsKeys("scm-repo-main", "tool-token-main")
    }

    @Test
    fun `a tampered or incomplete release fails the job before a sandbox starts`() {
        register()
        val tampered = bo.enqueue(sealedSpec, policy)
        bo.projectTokens[tampered] = mapOf("ref-1" to ("main" to "tkn-project-read"))
        bo.credentialAad = "another-job|ref-1|2026-10-22T12:00:00Z"
        leaseOnce()
        assertThat(backend.launched).doesNotContainKey(tampered)
        assertThat(bo.completionOf(tampered)!!.get("reason").asString()).startsWith("credentials:").contains("does not open")

        bo.credentialAad = null
        val missing = bo.enqueue(sealedSpec, policy)
        leaseOnce()
        assertThat(backend.launched).doesNotContainKey(missing)
        assertThat(bo.completionOf(missing)!!.get("reason").asString()).contains("no token released for repositories [main]")
    }

    @Test
    fun `registration is persisted and reused`() {
        val file = Files.createTempDirectory("rc").resolve("creds.json")
        val store = FileCredentialStore(file, json)
        RunnerRegistrar(client, store, holder).ensureRegistered(bo.registrationToken, "n", "v", "c", RunnerCapabilities(emptyList(), emptyList(), 1))
        assertThat(Files.readString(file)).contains(bo.runnerToken)
        val holder2 = RunnerTokenHolder()
        // second start: the single-use registration token would be refused, the stored token is used instead
        RunnerRegistrar(BackOfficeClient(bo.url, json, holder2::token), store, holder2).ensureRegistered(bo.registrationToken, "n", "v", null, RunnerCapabilities(emptyList(), emptyList(), 1))
        assertThat(holder2.token()).isEqualTo(bo.runnerToken)
        assertThat(bo.calls.count { it.path.endsWith("/register") }).isEqualTo(1)
    }

    @Test
    fun `happy path - verified assignment is launched, heartbeated, completed with egress evidence and deleted`() {
        register()
        assertThat(backend.pools.single().poolId).isEqualTo("test-pool")
        val jobId = bo.enqueue(spec, policy)
        leaseOnce()
        val launched = backend.launched.getValue(jobId)
        assertThat(launched.policy.egressHosts).contains("127.0.0.1", "gitlab.example.com", "registry.npmjs.org")
        assertThat(launched.policy.timeoutSeconds).isEqualTo(3600) // clamped to the ceiling
        assertThat(launched.bundle.jobToken).isEqualTo(bo.jobTokens[jobId])
        val deliveredSpec = json.readTree(launched.bundle.specJson)
        assertThat(deliveredSpec.get("timeoutSeconds").asInt()).isEqualTo(3600)
        assertThat(deliveredSpec.get("gateway").get("anthropicBaseUrl").asString()).endsWith("/api/v1/llm/anthropic")
        assertThat(json.readTree(launched.bundle.policyJson).get("tools").get("deny").strings()).containsExactly("curl *")
        // EVAL_CODING with a repository: READ, and without configured credentials an anonymous clone
        assertThat(launched.gitAccess).isEqualTo(GitAccess.READ)
        assertThat(launched.gitCredential).isNull()
        assertThat(launched.runId).isEqualTo("0b8b1c2e-0000-4000-8000-000000000002")

        supervisor.tick()
        assertThat(bo.calls.filter { it.path.endsWith("/heartbeat") }.map { json.readTree(it.body).get("phase").asString() }).containsExactly("STARTING")
        backend.state[jobId] = SandboxObservation(SandboxPhase.RUNNING, node = "node-1")
        supervisor.tick()
        assertThat(bo.calls.last { it.path.endsWith("/heartbeat") }.body).contains("\"phase\":\"RUNNING\"").contains("node-1")
        assertThat(meters.find("backoffice.sandbox.start").timer()!!.count()).isEqualTo(1)
        assertThat(meters.find("backoffice.runner.active.jobs").gauge()!!.value()).isEqualTo(1.0)

        backend.decisions = listOf(
            EgressDecision(jobId, "gitlab.example.com:443", true, "allowed", "2026-09-28T10:00:00Z"),
            EgressDecision(jobId, "evil.example.net:443", false, "denied", "2026-09-28T10:00:01Z"),
        )
        backend.state[jobId] = SandboxObservation(SandboxPhase.SUCCEEDED, exitCode = 0, imageDigest = "sha256:abc")
        supervisor.tick()
        val completion = bo.completionOf(jobId)!!
        assertThat(completion.get("status").asString()).isEqualTo("SUCCEEDED")
        assertThat(completion.get("exitCode").asInt()).isEqualTo(0)
        assertThat(completion.get("imageDigest").asString()).isEqualTo("sha256:abc")
        assertThat(completion.get("egressDenied").strings()).containsExactly("evil.example.net")
        assertThat(completion.get("egressLogDigest").asString()).startsWith("sha256:")
        assertThat(backend.deleted).contains(jobId)
        assertThat(supervisor.running).isEmpty()
        assertThat(registry.activeJobs()).isEmpty()
    }

    @Test
    fun `tampered or unsigned assignments are not run and not reported`() {
        register()
        val tampered = bo.enqueue(spec, policy, tamper = { (it.get("spec") as tools.jackson.databind.node.ObjectNode).put("prompt", "rm -rf /") })
        val unsigned = bo.enqueue(spec, policy, sign = false)
        leaseOnce()
        assertThat(backend.launched).doesNotContainKeys(tampered, unsigned)
        assertThat(bo.completions).isEmpty()
        assertThat(meters.find("backoffice.runner.assignments.rejected").tag("reason", "verification").counter()!!.count()).isEqualTo(2.0)
    }

    @Test
    fun `policy violations are reported as FAILED policy-violation`() {
        register()
        val badImage = bo.enqueue(spec, mapOf("environment" to mapOf("image" to "evil/miner:1")))
        leaseOnce()
        assertThat(bo.completionOf(badImage)!!.get("reason").asString()).startsWith("policy-violation: image evil/miner:1")
        backend.failLaunch = PolicyViolation("isolation HIGH needs RuntimeClass 'kata'")
        val noKata = bo.enqueue(spec, policy)
        leaseOnce()
        assertThat(bo.completionOf(noKata)!!.get("reason").asString()).contains("kata")
        assertThat(backend.launched).isEmpty()
    }

    @Test
    fun `LOCAL_LOGIN jobs get the owner login variable and Anthropic egress on docker, are refused elsewhere`() {
        register()
        val localLogin = spec + mapOf("agent" to "CLAUDE_CODE", "llmCredentials" to "LOCAL_LOGIN")
        // ADR 0040: not on Kubernetes backends
        val onK8s = bo.enqueue(localLogin, policy)
        leaseOnce()
        assertThat(bo.completionOf(onK8s)!!.get("reason").asString()).startsWith("local-login: ").contains("DOCKER")

        backend.kind = BackendKind.DOCKER
        val noToken = bo.enqueue(localLogin, policy)
        leaseOnce()
        assertThat(bo.completionOf(noToken)!!.get("reason").asString()).contains("CLAUDE_CODE_OAUTH_TOKEN is not set")

        controllerEnv["CLAUDE_CODE_OAUTH_TOKEN"] = "sk-ant-oat-own"
        val ok = bo.enqueue(localLogin, policy)
        val gateway = bo.enqueue(spec, policy)
        leaseOnce()
        val launched = backend.launched.getValue(ok)
        assertThat(launched.localLoginCredential).isEqualTo("CLAUDE_CODE_OAUTH_TOKEN")
        assertThat(launched.policy.egressHosts).contains("api.anthropic.com")
        assertThat(launched.bundle.specJson).doesNotContain("sk-ant-oat-own")
        assertThat(backend.launched.getValue(gateway).localLoginCredential).isNull()
        assertThat(backend.launched.getValue(gateway).policy.egressHosts).doesNotContain("api.anthropic.com")
    }

    @Test
    fun `git credential is selected by level - read for READ, push only for PUSH_MR, nothing for NONE`() {
        register()
        backend.gitCredentials = GitCredentialSelector(listOf(RepoCredential("gitlab.example.com/g/*", read = "git-read", push = "git-push")))
        val implement = spec + mapOf(
            "mode" to "IMPLEMENT",
            "repository" to mapOf("url" to "https://gitlab.example.com/g/app.git", "baseRef" to "main", "branch" to "feature/x", "push" to true),
            "gitAccess" to "PUSH_MR",
        )
        val pushJob = bo.enqueue(implement, policy)
        val readJob = bo.enqueue(spec + mapOf("gitAccess" to "READ"), policy)
        val legacyRead = bo.enqueue(spec, policy) // no gitAccess (older Back Office): derived as READ
        val noRepo = bo.enqueue(spec - "repository" + mapOf("gitAccess" to "PUSH_MR"), policy)
        leaseOnce()
        leaseOnce()
        assertThat(backend.launched.getValue(pushJob).gitAccess).isEqualTo(GitAccess.PUSH_MR)
        assertThat(backend.launched.getValue(pushJob).gitCredential).isEqualTo("git-push")
        assertThat(backend.launched.getValue(readJob).gitCredential).isEqualTo("git-read")
        assertThat(backend.launched.getValue(legacyRead).gitCredential).isEqualTo("git-read")
        assertThat(backend.launched.getValue(noRepo).gitAccess).isEqualTo(GitAccess.NONE)
        assertThat(backend.launched.getValue(noRepo).gitCredential).isNull()
    }

    @Test
    fun `a READ job that only has a push credential fails with a clear reason unless explicitly allowed`() {
        register()
        backend.gitCredentials = GitCredentialSelector(legacyPush = mapOf("*" to "back-office-git"))
        val refused = bo.enqueue(spec + mapOf("gitAccess" to "READ"), policy)
        leaseOnce()
        assertThat(backend.launched).doesNotContainKey(refused)
        assertThat(bo.completionOf(refused)!!.get("reason").asString())
            .startsWith("policy-violation: git credential: READ job for gitlab.example.com/g/app")
            .contains("only a push credential").contains("allowPushCredentialForRead")

        backend.gitCredentials = GitCredentialSelector(legacyPush = mapOf("*" to "back-office-git"), allowPushCredentialForRead = true)
        val allowed = bo.enqueue(spec + mapOf("gitAccess" to "READ"), policy)
        leaseOnce()
        assertThat(backend.launched.getValue(allowed).gitCredential).isEqualTo("back-office-git")
    }

    private fun multiRepoSpec(vararg repositories: Map<String, Any>) =
        spec - "repository" + mapOf("mode" to "IMPLEMENT", "repositories" to repositories.toList())

    private val docsRead = mapOf("key" to "docs", "url" to "https://docs.example.org/shop/docs.git", "baseBranch" to "main", "access" to "READ", "path" to "docs")
    private val srcPush = mapOf(
        "key" to "src", "url" to "https://gitlab.example.com/g/app.git", "baseBranch" to "main", "access" to "PUSH_MR", "path" to "src",
        "pushBranch" to "feature/x", "primary" to true,
    )
    private val toolsNone = mapOf("key" to "tools", "url" to "https://gitlab.example.com/g/tools.git", "access" to "NONE", "path" to "tools")

    @Test
    fun `multi-repository job - one credential per repository and level, NONE gets nothing, every host reachable`() {
        register()
        backend.gitCredentials = GitCredentialSelector(
            listOf(
                RepoCredential("docs.example.org/shop/docs", read = "git-docs-read"),
                RepoCredential("gitlab.example.com/g/*", read = "git-g-read", push = "git-g-push"),
            ),
        )
        val jobId = bo.enqueue(multiRepoSpec(docsRead, srcPush, toolsNone), policy)
        leaseOnce()
        val launched = backend.launched.getValue(jobId)
        assertThat(launched.gitAccess).isEqualTo(GitAccess.PUSH_MR) // highest level, for labels only
        assertThat(launched.gitCredential).isNull() // no legacy single token
        assertThat(launched.repositoryTokens).containsExactly(
            RepositoryToken("docs", GitAccess.READ, "git-docs-read"),
            RepositoryToken("src", GitAccess.PUSH_MR, "git-g-push"),
        )
        assertThat(launched.repositoryAccess).containsExactlyEntriesOf(mapOf("docs" to GitAccess.READ, "src" to GitAccess.PUSH_MR, "tools" to GitAccess.NONE))
        assertThat(launched.policy.egressHosts).contains("docs.example.org", "gitlab.example.com", "registry.npmjs.org")
        // the spec reaches the sandbox unchanged (signed), with the repositories list
        assertThat(json.readTree(launched.bundle.specJson).get("repositories").size()).isEqualTo(3)
    }

    @Test
    fun `multi-repository job is refused naming the repository whose credential breaks least privilege`() {
        register()
        backend.gitCredentials = GitCredentialSelector(
            listOf(
                RepoCredential("docs.example.org", push = "git-docs-push"),
                RepoCredential("gitlab.example.com/g/*", read = "git-g-read", push = "git-g-push"),
            ),
        )
        val readOnlyPush = bo.enqueue(multiRepoSpec(docsRead, srcPush), policy)
        leaseOnce()
        assertThat(backend.launched).doesNotContainKey(readOnlyPush)
        assertThat(bo.completionOf(readOnlyPush)!!.get("reason").asString())
            .startsWith("policy-violation: repository 'docs' (docs.example.org/shop/docs): git credential: READ job for docs.example.org/shop/docs")
            .contains("only a push credential")

        backend.gitCredentials = GitCredentialSelector(listOf(RepoCredential("gitlab.example.com", read = "git-read")))
        val pushWithoutPush = bo.enqueue(multiRepoSpec(srcPush), policy)
        leaseOnce()
        assertThat(bo.completionOf(pushWithoutPush)!!.get("reason").asString())
            .startsWith("policy-violation: repository 'src' (gitlab.example.com/g/app)").contains("no push credential")

        backend.gitCredentials = GitCredentialSelector(listOf(RepoCredential("docs.example.org", push = "git-docs-push")), allowPushCredentialForRead = true)
        val allowed = bo.enqueue(multiRepoSpec(docsRead), policy)
        leaseOnce()
        assertThat(backend.launched.getValue(allowed).repositoryTokens).containsExactly(RepositoryToken("docs", GitAccess.READ, "git-docs-push"))

        val badKey = bo.enqueue(multiRepoSpec(docsRead + mapOf("key" to "../x")), policy)
        val duplicate = bo.enqueue(multiRepoSpec(srcPush, srcPush + mapOf("path" to "other")), policy)
        leaseOnce()
        assertThat(bo.completionOf(badKey)!!.get("reason").asString()).startsWith("policy-violation: repositories[0]: key '../x'")
        assertThat(bo.completionOf(duplicate)!!.get("reason").asString()).contains("duplicate key(s) src")
    }

    @Test
    fun `an empty repositories list next to a legacy repository is the legacy spec`() {
        register()
        backend.gitCredentials = GitCredentialSelector(listOf(RepoCredential("gitlab.example.com/g/*", read = "git-read", push = "git-push")))
        val legacy = bo.enqueue(spec + mapOf("gitAccess" to "READ", "repositories" to emptyList<Any>()), policy)
        val none = bo.enqueue(spec - "repository" + mapOf("repositories" to emptyList<Any>()), policy)
        leaseOnce()
        assertThat(backend.launched.getValue(legacy).gitCredential).isEqualTo("git-read")
        assertThat(backend.launched.getValue(legacy).repositoryTokens).isEmpty()
        assertThat(backend.launched.getValue(none).gitAccess).isEqualTo(GitAccess.NONE)
        assertThat(backend.launched.getValue(none).gitCredential).isNull()
        assertThat(backend.launched.getValue(none).repositoryTokens).isEmpty()
    }

    @Test
    fun `launch failure is reported and frees the egress registration`() {
        register()
        backend.failLaunch = IllegalStateException("apiserver down")
        val jobId = bo.enqueue(spec, policy)
        leaseOnce()
        assertThat(bo.completionOf(jobId)!!.get("reason").asString()).startsWith("SandboxLaunchFailed")
        assertThat(registry.activeJobs()).isEmpty()
    }

    @Test
    fun `cancellation from the heartbeat deletes the sandbox and reports CANCELLED`() {
        register()
        val jobId = bo.enqueue(spec, policy)
        leaseOnce()
        backend.state[jobId] = SandboxObservation(SandboxPhase.RUNNING)
        bo.cancel += jobId
        supervisor.tick()
        assertThat(bo.completionOf(jobId)!!.get("status").asString()).isEqualTo("CANCELLED")
        assertThat(backend.deleted).contains(jobId)
    }

    @Test
    fun `lost lease deletes the sandbox without a completion`() {
        register()
        val jobId = bo.enqueue(spec, policy)
        leaseOnce()
        bo.heartbeatStatus[jobId] = 409
        supervisor.tick()
        assertThat(backend.deleted).contains(jobId)
        assertThat(supervisor.running).isEmpty()
        assertThat(bo.completionOf(jobId)).isNull()
    }

    @Test
    fun `deadline is enforced by the controller`() {
        val clockOffset = java.util.concurrent.atomic.AtomicLong(0)
        val s = JobSupervisor(props, client, backend, registry, PolicyResolver(PolicyDefaults(image = "back-office/runner:dev")), json, meters,
            now = { java.time.Instant.now().plusSeconds(clockOffset.get()) })
        val store = FileCredentialStore(Files.createTempDirectory("rc").resolve("creds.json"), json)
        RunnerRegistrar(client, store, holder).ensureRegistered(bo.registrationToken, "t", "dev", null, RunnerCapabilities(emptyList(), emptyList(), 2))
        s.bootstrap()
        val jobId = bo.enqueue(spec, policy)
        client.lease(pl.mos.backoffice.runnercontroller.protocol.LeaseRequest(1, 1, 1)).forEach { s.accept(it) }
        backend.state[jobId] = SandboxObservation(SandboxPhase.RUNNING)
        clockOffset.set(3600 + 121)
        s.tick()
        val completion = bo.completionOf(jobId)!!
        assertThat(completion.get("status").asString()).isEqualTo("TIMED_OUT")
        assertThat(completion.get("reason").asString()).isEqualTo("DeadlineExceeded")
    }

    @Test
    fun `orphans from a previous run are deleted and handed back as LOST`() {
        backend.orphans += ManagedSandbox(SandboxHandle("33333333-3333-4333-8333-333333333333", "bo-job-3", BackendKind.K8S_JOB), "test-pool")
        register()
        assertThat(backend.deleted).contains("33333333-3333-4333-8333-333333333333")
        val c = bo.completionOf("33333333-3333-4333-8333-333333333333")!!
        assertThat(c.get("status").asString()).isEqualTo("LOST")
        assertThat(c.get("reason").asString()).isEqualTo("controller-restarted")
    }

    @Test
    fun `lease loop asks only for free slots and graceful shutdown hands back unfinished jobs`() {
        register()
        val first = bo.enqueue(spec, policy)
        val second = bo.enqueue(spec, policy)
        val third = bo.enqueue(spec, policy)
        supervisor.start()
        val until = System.currentTimeMillis() + 10_000
        while (backend.launched.size < 2 && System.currentTimeMillis() < until) Thread.sleep(50)
        Thread.sleep(1500)
        assertThat(backend.launched.keys).containsExactlyInAnyOrder(first, second)
        val leases = bo.calls.filter { it.path.endsWith("/lease") }.map { json.readTree(it.body) }
        assertThat(leases.first().get("freeSlots").asInt()).isEqualTo(2)
        assertThat(leases.drop(1)).allMatch { it.get("freeSlots").asInt() in 0..2 }
        assertThat(bo.queue.map { it.get("jobId").asString() }).containsExactly(third)

        backend.state[first] = SandboxObservation(SandboxPhase.SUCCEEDED, exitCode = 0)
        supervisor.shutdown(Duration.ofSeconds(3))
        assertThat(bo.completionOf(first)!!.get("status").asString()).isEqualTo("SUCCEEDED")
        val handedBack = bo.completionOf(second)!!
        assertThat(handedBack.get("status").asString()).isEqualTo("LOST")
        assertThat(handedBack.get("reason").asString()).isEqualTo("runner-shutdown")
        assertThat(supervisor.isDraining()).isTrue()
    }
}
