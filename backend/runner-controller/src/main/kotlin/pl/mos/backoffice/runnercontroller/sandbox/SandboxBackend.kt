package pl.mos.backoffice.runnercontroller.sandbox

import pl.mos.backoffice.runnercontroller.egress.EgressDecision
import pl.mos.backoffice.runnercontroller.egress.ProxyCredentials
import pl.mos.backoffice.runnercontroller.git.GitAccess
import pl.mos.backoffice.runnercontroller.git.GitCredentialSelector
import pl.mos.backoffice.runnercontroller.git.RepositoryToken
import pl.mos.backoffice.runnercontroller.policy.ResolvedPolicy
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import java.time.Instant

/**
 * Everything one sandbox needs. Per-job data never goes into environment variables: it is delivered as the
 * files of [JobBundle] (a read-only Secret volume, or written into a memory-backed volume of a warm pod), so
 * cold and warm starts look the same to the runner.
 */
data class LaunchRequest(
    val jobId: String,
    val poolId: String,
    val projectId: String?,
    val runId: String?,
    val policy: ResolvedPolicy,
    val bundle: JobBundle,
    /** Git rights of the job (`spec.gitAccess`, ADR 0031); multi-repository jobs: the highest level of any repository. */
    val gitAccess: GitAccess,
    /**
     * Legacy single-repository specs: the credential [SandboxBackend.gitCredentials] selected for [gitAccess]
     * (Secret name, or env var name for Docker); delivered as `/var/run/backoffice/git/token`. Null = no credential
     * (NONE, anonymous clone, or a multi-repository job).
     */
    val gitCredential: String?,
    val proxy: ProxyCredentials,
    val deadline: Instant,
    /**
     * Multi-repository specs: one credential per repository that gets one (NONE and anonymous repositories are
     * absent), delivered as `/var/run/backoffice/git/<key>/token`. Nothing else is mounted.
     */
    val repositoryTokens: List<RepositoryToken> = emptyList(),
    /** Multi-repository specs: every repository key with its level (annotation `backoffice.io/git-repositories`). */
    val repositoryAccess: Map<String, GitAccess> = emptyMap(),
    /**
     * ADR 0040: name of the controller environment variable with the owner's Claude Code login (LOCAL_LOGIN jobs,
     * DOCKER backend only); delivered as `claude-login-token` in the bundle. Null = the job uses the LLM gateway.
     */
    val localLoginCredential: String? = null,
)

/**
 * Files under `/var/run/backoffice/job/` (runner: `RUNNER_JOB_DIR`):
 * `spec.json` (RunnerJobSpec incl. `gateway`), `policy.json` (resolved SandboxPolicy), `job-id`, `job-token`
 * (bo_job_), `proxy-url` (HTTPS_PROXY for the sandbox; filled in by the backend) and `ready` (written last).
 * LOCAL_LOGIN jobs (ADR 0040, DOCKER backend) also get `claude-login-token`, written before `ready`.
 */
data class JobBundle(val specJson: String, val policyJson: String, val jobId: String, val jobToken: String) {
    fun files(proxyUrl: String): Map<String, String> = linkedMapOf(
        "spec.json" to specJson,
        "policy.json" to policyJson,
        "job-id" to jobId,
        "job-token" to jobToken,
        "proxy-url" to proxyUrl,
        "ready" to "1",
    )
}

data class SandboxHandle(val jobId: String, val name: String, val backend: BackendKind)

enum class SandboxPhase { PENDING, RUNNING, SUCCEEDED, FAILED, GONE }

/** Normalised state of a sandbox, whatever the backend. */
data class SandboxObservation(
    val phase: SandboxPhase,
    val exitCode: Int? = null,
    /** OOMKilled, DeadlineExceeded, ImagePullBackOff, ErrImagePull, Evicted, CreateContainerConfigError, Error, ... */
    val reason: String? = null,
    val message: String? = null,
    val node: String? = null,
    val imageDigest: String? = null,
    val startedAt: Instant? = null,
    /** Waiting reason of the agent container while PENDING (e.g. ImagePullBackOff). */
    val waitingReason: String? = null,
    /** True once the job bundle is in the sandbox (warm pods: after delivery). */
    val bundleDelivered: Boolean = true,
)

/** The backend could not create the sandbox (reported as FAILED with reason SandboxLaunchFailed). */
class SandboxLaunchFailed(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ManagedSandbox(val handle: SandboxHandle, val poolId: String?)

interface SandboxBackend {
    val kind: BackendKind

    /** Idempotent pool-level setup: egress proxy, NetworkPolicies, warm pools. Called at start and after config changes. */
    fun ensurePool(pool: PoolSettings)

    fun launch(request: LaunchRequest): SandboxHandle

    fun observe(handle: SandboxHandle): SandboxObservation

    /** Deletes the sandbox and everything created for it (Secret, network, proxy container). Idempotent. */
    fun delete(handle: SandboxHandle)

    /** Sandboxes this controller created (label `app.kubernetes.io/managed-by=back-office-runner-controller`). */
    fun listManaged(poolId: String): List<ManagedSandbox>

    /** Decisions the backend collected itself (Docker reads the per-job proxy's log); empty when the proxy pushes them. */
    fun egressDecisions(handle: SandboxHandle): List<EgressDecision> = emptyList()

    /** The proxy URL the sandbox uses (`HTTPS_PROXY`). */
    fun proxyUrl(request: LaunchRequest): String

    /** Git credentials this backend can deliver, selected per job by repository and [GitAccess] (ADR 0031). */
    val gitCredentials: GitCredentialSelector get() = GitCredentialSelector.EMPTY
}

/** Pool-level settings from `/runner/v2/config` plus local configuration. */
data class PoolSettings(val poolId: String, val warmPoolSize: Int, val defaultPolicy: ResolvedPolicy?)

/** Bundle file with the owner's Claude Code login of a LOCAL_LOGIN job (ADR 0040). */
const val LOCAL_LOGIN_FILE = "claude-login-token"

object Labels {
    const val MANAGED_BY = "app.kubernetes.io/managed-by"
    const val MANAGED_BY_VALUE = "back-office-runner-controller"
    const val NAME = "app.kubernetes.io/name"
    const val SANDBOX = "backoffice.io/sandbox"
    const val POOL = "backoffice.io/pool"
    const val JOB_ID = "backoffice.io/job-id"
    const val PROJECT_ID = "backoffice.io/project-id"
    const val RUN_ID = "backoffice.io/run-id"
    const val TEMPLATE_HASH = "backoffice.io/template-hash"
    const val DEADLINE = "backoffice.io/deadline"
    const val ISOLATION = "backoffice.io/isolation"
    const val POLICY_NOTES = "backoffice.io/policy-notes"
    const val GIT_ACCESS = "backoffice.io/git-access"
    /** Multi-repository jobs: `key=LEVEL` per repository (annotation). */
    const val GIT_REPOSITORIES = "backoffice.io/git-repositories"

    fun sandboxName(jobId: String) = "bo-job-$jobId"

    /** Label values: max 63 chars of `[A-Za-z0-9_.-]`, alphanumeric at both ends. */
    fun value(v: String): String = v.replace(Regex("[^A-Za-z0-9_.-]"), "-").take(63).trim('-', '_', '.')
}
