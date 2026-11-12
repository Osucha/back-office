package pl.mos.backoffice.shared.runner

import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.UsageContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import pl.mos.backoffice.shared.platform.WorkRequest

/** PROMPT (ADR 0050 step 4): one answer of Claude Code without a repository or tools (an LLM call on a user's own login). */
enum class RunnerMode { SPEC_KIT, IMPLEMENT, EVAL_CODING, PROMPT }
enum class AgentHarness { OPENCODE, CLAUDE_CODE }

/** Where agent jobs of a benchmark run (ADR 0046): the shared runner fleet or the starting user's personal runner. */
enum class RunnerTarget { SHARED, PERSONAL }

/**
 * What a [RunnerTarget] offers right now: its pools and the runners that called in recently. [localLogin] (ADR 0048):
 * a pool of the target lets Claude Code use its owner's own login (LOCAL_LOGIN).
 */
data class ExecutionTarget(
    val target: RunnerTarget,
    val pools: Int,
    val onlineRunners: Int,
    val localLogin: Boolean = false,
    /**
     * ADR 0050 step 3: whether an online runner of the target reported a Claude login: true = one did, false = every online
     * runner reported none (its LOCAL_LOGIN jobs would fail), null = not reported (older controllers) or none online.
     */
    val claudeLogin: Boolean? = null,
    /** ADR 0050 step 3: the coding agents the online runners reported. */
    val harnesses: List<String> = emptyList(),
) {
    val exists: Boolean get() = pools > 0
}
enum class SpecKitPhase { SPECIFY, CLARIFY, PLAN, TASKS, ANALYZE, IMPLEMENT }

/**
 * ADR 0044/0060: the persistent run directory a job works in. Every job of the run that names the same [key] gets the
 * same directory at `/workspace` ([name]: a Docker volume or a PVC of the runner that holds it); repositories cloned
 * there by an earlier job are reused. ADR 0060: the only key is [RUN_KEY]. [sessionRole] continues the coding agent's
 * session of that role (kept in the agent's home); [prepareOnly] clones and runs `setupCommand` without an agent.
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

        /** ADR 0060: the key of the run directory every RUN step of a run shares. */
        const val RUN_KEY = "run"

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

/**
 * Agent Skill of the job's agent. ADR 0060: written into the agent's home (`/workspace/.agents/<agentHome>/skills/<key>/`)
 * when the spec names one, never into a repository; older specs: `.claude/skills/<key>/SKILL.md`, `.opencode/skills/...`.
 */
data class RunnerSkill(val key: String, val name: String, val content: String)

/** ADR 0060: what a coding agent may change. */
enum class AgentAccessMode { FULL, READ_ONLY, PATHS }

/**
 * ADR 0062: where a coding agent works: [RUN] the run directory every step of the run shares (default); [SEPARATE] a
 * fresh directory with only the step's repositories, thrown away after the step (no session continuation).
 */
enum class AgentDirectory { RUN, SEPARATE }

/**
 * ADR 0062: which executions of a coding agent continue one conversation: [PER_STEP] the repeats of one step (default,
 * ADR 0060 `OWN`), [PER_RUN] every step of this agent in a run, [FRESH] none (every execution starts anew).
 */
enum class AgentSession { PER_STEP, PER_RUN, FRESH }

/**
 * ADR 0060: what a coding agent may change: [AgentAccessMode.FULL] anything, [AgentAccessMode.READ_ONLY] nothing,
 * [AgentAccessMode.PATHS] only files matching [paths] (globs relative to a repository root, e.g. `docs/` and everything below it as a double-star glob). Enforced by
 * the harness's tool rules and by the runner, which reverts other changes after the agent finished.
 */
data class AgentAccess(
    val mode: AgentAccessMode = AgentAccessMode.FULL,
    val paths: List<String> = emptyList(),
    /** ADR 0062: the run directory (default) or a fresh directory with only the step's repositories. */
    val directory: AgentDirectory = AgentDirectory.RUN,
) {
    init {
        problems().firstOrNull()?.let { throw ValidationException(it, "access.paths") }
    }

    /** May change everything (the runner enforces nothing); says nothing about [directory]. */
    val full: Boolean get() = mode == AgentAccessMode.FULL

    /** ADR 0062: works in a fresh directory, does not see what other steps left. */
    val separate: Boolean get() = directory == AgentDirectory.SEPARATE

    /** Why the access is inconsistent; empty when it is fine. */
    private fun problems(): List<String> = buildList {
        if (mode == AgentAccessMode.PATHS) {
            if (paths.isEmpty() || paths.any { it.isBlank() }) add("access PATHS needs at least one non-blank path glob")
            paths.filter { it.isNotBlank() }.forEach { p ->
                when {
                    p.startsWith("/") || p.startsWith("\\") -> add("path $p must be relative to the repository root (no leading /)")
                    p.split('/', '\\').any { it == ".." } -> add("path $p must not contain ..")
                    p.length > MAX_PATH_LENGTH -> add("path $p is longer than $MAX_PATH_LENGTH characters")
                }
            }
            if (paths.size > MAX_PATHS) add("at most $MAX_PATHS paths")
        } else if (paths.isNotEmpty()) {
            add("paths are only for access PATHS")
        }
    }

    companion object {
        val FULL = AgentAccess()
        val READ_ONLY = AgentAccess(AgentAccessMode.READ_ONLY)
        const val MAX_PATHS = 50
        const val MAX_PATH_LENGTH = 300
    }
}

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
    /** ADR 0050 step 4: the system prompt of a PROMPT job; [prompt] is then the user message. */
    val systemPrompt: String? = null,
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
    /** ADR 0047: the commit subject; null = the runner derives it from the first prompt line (older behaviour). */
    val commitSubject: String? = null,
    /** ADR 0047: the Spec Kit command argument (feature description, feedback); null = the whole prompt. */
    val featureDescription: String? = null,
    /** ADR 0047: checks after the agent (coding benchmarks): hidden files for the verification, protected paths. */
    val acceptance: RunnerAcceptance? = null,
    /**
     * ADR 0051: tokens of the project's repositories the runner-controller fetches sealed to its own key
     * (`POST /runner/v2/jobs/{id}/credentials`) before the sandbox starts. Names only, never values; part of the
     * signed spec, so a runner can fetch nothing else. Empty = the runner pool's own tokens (deprecated fallback).
     */
    val credentials: List<JobCredentialRef> = emptyList(),
    /**
     * ADR 0051 step 4: read-only tools the agent gets (e.g. `gitlab-read` = glab with the CI_READ token of a repository,
     * named in [credentials] with that capability). Empty = none.
     */
    val agentTools: List<AgentTool> = emptyList(),
    /**
     * ADR 0057: the agent's environment document (platform preamble, agent instructions, project constitution, rules).
     * The runner writes it outside every repository in the harness's own form (OpenCode `AGENTS.md` listed in
     * `opencode.json`, Claude Code user memory `CLAUDE.md`). Null = none (older specs keep their digests).
     */
    val instructions: String? = null,
    /** ADR 0057: remote MCP servers of the agent (no credentials). Empty = none (omitted from the spec digest). */
    val mcpServers: List<AgentMcpServer> = emptyList(),
    /**
     * ADR 0060: the agent key whose home `/workspace/.agents/<agentHome>/` holds the environment document, skills, MCP
     * configuration and sessions. Null = the ADR 0057 locations (omitted from the spec digest).
     */
    val agentHome: String? = null,
    /** ADR 0060: what the agent may change; null = FULL (omitted from the spec digest). */
    val access: AgentAccess? = null,
)

/**
 * ADR 0057: a remote MCP server an agent may use: [name] (`^[a-z][a-z0-9-]{0,39}$`) and an HTTP(S) [url] of at most
 * [MAX_URL_LENGTH] characters, no credentials. The sandbox egress policy decides whether it is reachable.
 */
data class AgentMcpServer(val name: String, val url: String) {
    init {
        if (!NAME_PATTERN.matches(name)) throw ValidationException("MCP server name must match ${NAME_PATTERN.pattern}", "mcpServers.name")
        if (url.length > MAX_URL_LENGTH) throw ValidationException("MCP server $name: url is longer than $MAX_URL_LENGTH characters", "mcpServers.url")
        val lower = url.lowercase()
        val scheme = listOf("http://", "https://").firstOrNull { lower.startsWith(it) }
        if (scheme == null || url.length <= scheme.length || url.any { it.isWhitespace() }) {
            throw ValidationException("MCP server $name: url must be an http:// or https:// URL", "mcpServers.url")
        }
    }

    companion object {
        val NAME_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{0,39}$")
        const val MAX_URL_LENGTH = 500

        /** Most MCP servers one agent may list. */
        const val MAX_PER_AGENT = 10
    }
}

/** ADR 0051 step 4: one agent tool of a job and the repository its token belongs to. */
data class AgentTool(val name: String, val repository: String) {
    companion object {
        /** glab with a verified read-only token (CI_READ): pipelines, job logs, merge requests, issues of the repository. */
        const val GITLAB_READ = "gitlab-read"
        val KNOWN = setOf(GITLAB_READ)
    }
}

/**
 * ADR 0051: one project repository token of a job. [ref] identifies the token (its connection id), [repository] the
 * job repository it is for (a `repositories[].key`, or "" for the single legacy `repository`), [capability] what
 * the runner uses it for (`GIT_READ`: clone, `PUSH_BRANCH`: clone and push).
 */
data class JobCredentialRef(val ref: String, val repository: String, val capability: String)

/**
 * ADR 0047: what a verification checks besides `verifyCommand`. After the agent the runner fails the verification when
 * [requireChanges] and nothing changed, or when a tracked file matching [protectedPaths] (git pathspecs) differs from
 * the base; then it writes [files] (path → content), runs `verifyCommand` and removes them again, so they reach
 * neither the commit nor the diff.
 */
data class RunnerAcceptance(
    val files: Map<String, String> = emptyMap(),
    val protectedPaths: List<String> = emptyList(),
    val requireChanges: Boolean = false,
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
    /** ADR 0060: what the runner enforced when the agent's access was not FULL; null otherwise (and from older runners). */
    val access: RunnerAccessReport? = null,
) {
    val succeeded: Boolean get() = status == RunnerJobStatus.SUCCEEDED
}

/** ADR 0060: one change outside the agent's access the runner reverted; [repository] = the repository key, null for the job root. */
data class RevertedChange(val repository: String?, val path: String, val change: String) {
    fun describe(): String = (repository?.let { "$it:" } ?: "") + path + " ($change)"
}

/** ADR 0060: `RunnerResult.access`: the access the runner enforced and the changes it reverted. */
data class RunnerAccessReport(val mode: AgentAccessMode, val paths: List<String> = emptyList(), val reverted: List<RevertedChange> = emptyList())

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
