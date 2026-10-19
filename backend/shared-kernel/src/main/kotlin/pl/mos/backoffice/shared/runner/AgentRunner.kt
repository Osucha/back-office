package pl.mos.backoffice.shared.runner

import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.UsageContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import pl.mos.backoffice.shared.platform.WorkRequest

enum class RunnerMode { SPEC_KIT, IMPLEMENT, EVAL_CODING }
enum class AgentHarness { OPENCODE, CLAUDE_CODE }

/** Where agent jobs of a benchmark run (ADR 0046): the shared runner fleet or the starting user's personal runner. */
enum class RunnerTarget { SHARED, PERSONAL }

/** What a [RunnerTarget] offers right now: its pools and the runners that called in recently. */
data class ExecutionTarget(val target: RunnerTarget, val pools: Int, val onlineRunners: Int) {
    val exists: Boolean get() = pools > 0
}
enum class SpecKitPhase { SPECIFY, CLARIFY, PLAN, TASKS, ANALYZE, IMPLEMENT }

/**
 * ADR 0044: the persistent workspace of a run a job works in. Every job of the run that names the same [key] gets the
 * same directory at `/workspace` ([name]: a Docker volume or a PVC of the runner that holds it); repositories cloned
 * there by an earlier job are reused. [sessionRole] continues the coding agent's session of that role (one per role
 * and workspace); [prepareOnly] clones and runs `setupCommand` without an agent.
 */
data class RunnerWorkspace(
    val key: String,
    val name: String,
    val sessionRole: String? = null,
    val prepareOnly: Boolean = false,
    /** The workspace's own setup (e.g. npm ci): the runner runs it once per workspace, again only when it changes. */
    val setupCommand: String? = null,
) {
    companion object {
        /** Workspace keys and session roles: short, lower case, DNS-safe (the name must stay a valid volume / PVC name). */
        val KEY_PATTERN = Regex("^[a-z][a-z0-9-]{0,19}$")

        const val NAME_PREFIX = "bo-ws-"

        /** `bo-ws-<run id without dashes>-<key>`: at most 59 characters. */
        fun nameFor(runId: UUID, key: String): String = NAME_PREFIX + runId.toString().replace("-", "") + "-" + key
    }
}

/**
 * Merge request to open when the branch is pushed. Implemented with git push options
 * (`-o merge_request.create ...`), which GitLab honours and other hosts ignore, so the platform never
 * needs an SCM API token (ADR 0013).
 */
data class RunnerMergeRequest(
    val create: Boolean = true,
    val title: String,
    val description: String? = null,
    val targetBranch: String,
    val draft: Boolean = false,
)

/**
 * Git rights a job needs (ADR 0031, least privilege). Back Office sends only the level, never a credential;
 * the runner-controller picks the matching credential of its cluster (a read-only token for [READ], a token
 * that can push a branch and open a merge request for [PUSH_MR], nothing for [NONE]) and the runner enforces
 * it (READ: no push at all; PUSH_MR: only the job branch, only whitelisted push options, never a merge).
 */
enum class GitAccess {
    /** No repository, no git credential. */
    NONE,

    /** Clone and fetch only: reviews, specification analysis, benchmarks, steps with `push = false`. */
    READ,

    /** Clone, push the job branch and open a merge request through push options; never merge. */
    PUSH_MR,
    ;

    companion object {
        /** Modes whose result is pushed when `repository.push` is set (evaluation attempts never push). */
        private val PUSHING_MODES = setOf(RunnerMode.IMPLEMENT, RunnerMode.SPEC_KIT)

        /** IMPLEMENT / SPEC_KIT with `push = true` -> PUSH_MR; any other job with a repository -> READ; none -> NONE. */
        fun derive(mode: RunnerMode, repository: RunnerRepository?): GitAccess = when {
            repository == null -> NONE
            mode in PUSHING_MODES && repository.push -> PUSH_MR
            else -> READ
        }

        /** Whether a job of [mode] may push at all (evaluation attempts never push). */
        fun pushingMode(mode: RunnerMode): Boolean = mode in PUSHING_MODES

        /** The highest level of [levels] (NONE for none): the top-level `gitAccess` of a multi-repository job (ADR 0034). */
        fun highest(levels: Collection<GitAccess>): GitAccess = levels.maxByOrNull { it.ordinal } ?: NONE
    }
}

/**
 * Merge request opened when a [JobRepository] with [GitAccess.PUSH_MR] is pushed (git push options, never a
 * merge; ADR 0031). The target branch is the entry's `baseBranch`.
 */
data class JobMergeRequest(
    val create: Boolean = true,
    val title: String,
    val description: String? = null,
    val draft: Boolean = false,
)

/**
 * One repository of a runner job (ADR 0034, contract shared with the execution plane). The workspace root is
 * `/workspace`; the entry is cloned into `/workspace/<path>`; the agent works in the [primary] entry's path.
 * [pushBranch] and [mergeRequest] are set only for [GitAccess.PUSH_MR]; [pushBranch] is never [baseBranch].
 */
data class JobRepository(
    val key: String,
    val url: String,
    val baseBranch: String,
    val access: GitAccess,
    val path: String = key,
    val pushBranch: String? = null,
    val mergeRequest: JobMergeRequest? = null,
    val primary: Boolean = false,
)

/** What a job did in one repository (ADR 0034); `RunnerResult.repositories`. */
data class RepoResult(
    val key: String,
    val branch: String? = null,
    val commitSha: String? = null,
    val diffStat: RunnerDiffStat? = null,
    val mergeRequestUrl: String? = null,
    val pushed: Boolean = false,
    val error: String? = null,
)

data class RunnerRepository(
    val url: String,
    val baseRef: String,
    val branch: String? = null,
    val push: Boolean = false,
    val mergeRequest: RunnerMergeRequest? = null,
)

/** Agent Skill written into the workspace (`.claude/skills/<key>/SKILL.md`, `.opencode/skills/<key>/SKILL.md`). */
data class RunnerSkill(val key: String, val name: String, val content: String)

/**
 * What one runner container is asked to do. Serialised to JSON (see openapi.yaml `RunnerJobSpec`) and
 * delivered as base64 in the `RUNNER_SPEC` environment variable.
 */
data class RunnerJobSpec(
    val mode: RunnerMode,
    val agent: AgentHarness,
    val model: ModelRef,
    val prompt: String,
    val repository: RunnerRepository? = null,
    val specKitPhase: SpecKitPhase? = null,
    val constitution: String? = null,
    val setupCommand: String? = null,
    val verifyCommand: String? = null,
    val timeout: Duration = Duration.ofMinutes(60),
    val collectArtifacts: List<String> = emptyList(),
    /** Skills the agent should have available in the workspace. */
    val skills: List<RunnerSkill> = emptyList(),
    /** When true the runner returns `git diff <baseRef>...HEAD` in `RunnerResult.diff` (capped at 2 MB). */
    val collectDiff: Boolean = true,
    /** Usage booking for the job; the runner adapter records the ledger entry from the result. */
    val usageContext: UsageContext,
    /** Human-readable label used in Job names/labels, e.g. `run-1a2b3c4d-implement-1`. Must be DNS-safe. */
    val jobLabel: String,
    /**
     * Requested sandbox policy (platform defaults, project, template and node already merged by the caller).
     * The runner control plane clamps it to the pool ceiling (ADR 0024); null = defaults.
     */
    val policy: SandboxPolicy? = null,
    /**
     * Repositories of the job (ADR 0034). Empty for jobs built the legacy way (coding benchmarks): the runner then
     * uses [repository]. When not empty, [repository] mirrors the [JobRepository.primary] entry for older runners.
     */
    val repositories: List<JobRepository> = emptyList(),
    /**
     * Git rights of the job (ADR 0031): derived from [mode] and [repository] unless the caller sets it; with
     * [repositories] the highest access among the entries. The runner pushes only for [GitAccess.PUSH_MR]; the
     * runner-controller selects the credential by this level (per entry when it knows [repositories]).
     */
    val gitAccess: GitAccess = if (repositories.isNotEmpty()) GitAccess.highest(repositories.map { it.access }) else GitAccess.derive(mode, repository),
    /**
     * ADR 0036: whether the job may use Spec Kit (`specify init`, the constitution, `/speckit.*` commands). Set by
     * pipeline steps from the project setting and the platform switch; `false` = the runner does no Spec Kit work at
     * all. Null (jobs built the legacy way, specs stored before 0036) keeps the runner's earlier behaviour and is
     * omitted from the canonical JSON, so their spec digests do not change.
     */
    val specKit: Boolean? = null,
    /**
     * ADR 0040: the user whose personal runner should run this job (a step of a private project started by its
     * owner). When that user has a personal runner pool the job goes there and nowhere else; otherwise it is routed
     * like any other job. Routing data only: never sent to the runner.
     */
    val personalRunnerOf: UUID? = null,
    /** ADR 0044: the run workspace the job works in; null = a throwaway workspace (and a new agent session). */
    val workspace: RunnerWorkspace? = null,
)

enum class RunnerJobStatus { CREATED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }

data class RunnerArtifact(val name: String, val path: String?, val mediaType: String, val content: String)

data class RunnerVerification(val command: String, val exitCode: Int, val outputTail: String)

data class RunnerDiffStat(val files: Int, val insertions: Int, val deletions: Int)

data class RunnerResult(
    val status: RunnerJobStatus,
    val summary: String?,
    val branch: String?,
    val commitSha: String?,
    val diffStat: RunnerDiffStat?,
    val verification: RunnerVerification?,
    val artifacts: List<RunnerArtifact>,
    /** Unified diff of the work against `baseRef`, when `collectDiff` was requested. */
    val diff: String? = null,
    val mergeRequestUrl: String? = null,
    val usage: TokenUsage,
    /** Cost reported by the harness (Claude Code reports `total_cost_usd`); null when unknown. */
    val reportedCostUsd: BigDecimal?,
    val error: String?,
    val finishedAt: Instant,
    /**
     * Per-repository result (ADR 0034); empty from older runners, whose top-level [branch] / [commitSha] /
     * [diffStat] / [mergeRequestUrl] then describe the single (primary) repository.
     */
    val repositories: List<RepoResult> = emptyList(),
    /**
     * ADR 0040: the coding agent used its user's own subscription login on a personal runner instead of the LLM
     * gateway. Nothing was billed through Back Office; [reportedCostUsd] is only the harness's estimate.
     */
    val subscription: Boolean = false,
) {
    val succeeded: Boolean get() = status == RunnerJobStatus.SUCCEEDED
}

data class RunnerLogLine(val at: Instant, val message: String, val progress: Boolean = false)

/**
 * Outbound port: run one agent task in an isolated container and wait for its result.
 * Implemented in `agent-runner` (Kubernetes Job via fabric8, or `docker run` locally).
 *
 * `start` must be idempotent on `spec.jobLabel`: if a job with that label already exists (after a
 * restart), the implementation re-attaches and returns the existing id instead of creating a new one.
 * `await` blocks the calling (virtual) thread until the result arrives or `timeout` elapses; on timeout it
 * returns a `TIMED_OUT` result and cancels the job.
 */
interface AgentRunner {
    /**
     * Non-blocking submission (ADR 0023/0024): queues the job for the runner fleet and returns at once. When
     * the job reaches a terminal state the control plane enqueues [continuation] with `jobId` and `status`
     * added to its payload, e.g. a `RUN_ADVANCE` for the waiting workflow run. Idempotent on `spec.jobLabel`.
     */
    fun submit(spec: RunnerJobSpec, continuation: WorkRequest): RunnerJobId =
        throw UnsupportedOperationException("runner v2 submit not implemented by ${this::class.simpleName}")

    /** The stored result of a finished job, or null while it is still running. */
    fun result(jobId: RunnerJobId): RunnerResult? = null

    @Deprecated("Blocks a thread; use submit with a continuation", ReplaceWith("submit(spec, continuation)"))
    fun start(spec: RunnerJobSpec): RunnerJobId

    @Deprecated("Blocks a thread; use submit with a continuation and result(jobId)")
    fun await(jobId: RunnerJobId, timeout: Duration): RunnerResult
    fun status(jobId: RunnerJobId): RunnerJobStatus
    fun cancel(jobId: RunnerJobId)
    fun logs(jobId: RunnerJobId, after: Instant?): List<RunnerLogLine>
    fun isAvailable(): Boolean

    /** ADR 0046: the shared fleet and the personal runner of [userId] (when given), with their online runners. */
    fun executionTargets(userId: UUID?): List<ExecutionTarget> =
        listOf(ExecutionTarget(RunnerTarget.SHARED, if (isAvailable()) 1 else 0, 0))

    /**
     * ADR 0044: the run [runId] ended; its workspaces are released after [keepFor] (zero = now) unless a job of the
     * run uses them again before. The runners holding them delete them on their next sync.
     */
    fun releaseWorkspaces(runId: UUID, keepFor: Duration) {}
}

// ------------------------------------------------------------------------------------------------
// Sandbox policy (ADR 0024, docs/architecture/10-agent-execution.md). Narrowing-only merge:
// platform -> pool ceiling -> project -> template -> node.
// ------------------------------------------------------------------------------------------------

enum class SandboxIsolation { STANDARD, HIGH, DEV }

data class SandboxResources(val cpu: String? = null, val memory: String? = null, val ephemeralStorage: String? = null, val pids: Int? = null)

data class SandboxTools(val allow: List<String> = emptyList(), val deny: List<String> = emptyList(), val ask: List<String> = emptyList())

data class SandboxEnvironment(val image: String? = null, val setupCommand: String? = null, val caches: List<String> = emptyList())

data class SandboxPolicy(
    val isolation: SandboxIsolation? = null,
    val resources: SandboxResources = SandboxResources(),
    val timeoutSeconds: Int? = null,
    /** host[:port] patterns; the LLM gateway and the git host are always added by the control plane. */
    val networkAllow: List<String> = emptyList(),
    val tools: SandboxTools = SandboxTools(),
    /** Secret names resolved in the runner cluster, never values. */
    val secrets: List<String> = emptyList(),
    val environment: SandboxEnvironment = SandboxEnvironment(),
    val maxCostUsd: java.math.BigDecimal? = null,
    val maxTokens: Long? = null,
    val poolSelector: Map<String, String> = emptyMap(),
) {
    companion object {
        val DEFAULT = SandboxPolicy()
    }
}
