package pl.mos.backoffice.runner

import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.RunnerArtifact
import pl.mos.backoffice.shared.runner.RunnerDiffStat
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMergeRequest
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.RunnerSkill
import pl.mos.backoffice.shared.runner.RunnerVerification
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.JobCompletionReport
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.JobMergeRequest
import pl.mos.backoffice.shared.runner.JobCredentialRef
import pl.mos.backoffice.shared.runner.JobRepository
import pl.mos.backoffice.shared.runner.RepoResult
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.SandboxEnvironment
import pl.mos.backoffice.shared.runner.SandboxIsolation
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.runner.SandboxResources
import pl.mos.backoffice.shared.runner.SandboxTools
import pl.mos.backoffice.shared.runner.SpecKitPhase
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import pl.mos.backoffice.shared.runner.RunnerAcceptance

/*
 * JSON documents of the runner protocol exactly as in openapi.yaml (`RunnerJobSpec`, `RunnerResult`,
 * `RunnerEvent`, `SandboxPolicy`). Kept as plain Kotlin data classes so Jackson 3 (with the Kotlin module)
 * maps them; the same documents are stored in the `agent_job` JSONB columns.
 */

data class ModelOptionsJson(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoning: String = Reasoning.MEDIUM.name,
    val extra: Map<String, Any?> = emptyMap(),
)

/** [connection] (ADR 0045): the provider connection serving the model; omitted when the provider's default serves it. */
data class ModelRefJson(val provider: String, val modelId: String, val options: ModelOptionsJson = ModelOptionsJson(), val connection: String? = null) {
    fun toModelRef(): ModelRef = ModelRef(
        provider = Provider.valueOf(provider),
        modelId = modelId,
        connection = connection,
        options = ModelOptions(
            temperature = options.temperature,
            maxOutputTokens = options.maxOutputTokens,
            reasoning = Reasoning.entries.firstOrNull { it.name == options.reasoning } ?: Reasoning.MEDIUM,
            extra = options.extra,
        ),
    )

    companion object {
        fun of(ref: ModelRef) = ModelRefJson(
            ref.provider.name, ref.modelId,
            ModelOptionsJson(ref.options.temperature, ref.options.maxOutputTokens, ref.options.reasoning.name, ref.options.extra),
            ref.connection,
        )
    }
}

/** `repository.mergeRequest`: git push options that open a merge request on push (ADR 0013). */
data class MergeRequestJson(
    val create: Boolean = true,
    val title: String = "",
    val description: String? = null,
    val targetBranch: String = "",
    val draft: Boolean = false,
) {
    fun toMergeRequest() = RunnerMergeRequest(create, title, description, targetBranch, draft)

    companion object {
        fun of(mr: RunnerMergeRequest) = MergeRequestJson(mr.create, mr.title, mr.description, mr.targetBranch, mr.draft)
    }
}

data class RepositoryJson(
    val url: String,
    val baseRef: String,
    val branch: String? = null,
    val push: Boolean = false,
    val mergeRequest: MergeRequestJson? = null,
) {
    fun toRepository() = RunnerRepository(url, baseRef, branch, push, mergeRequest?.toMergeRequest())

    companion object {
        fun of(r: RunnerRepository) = RepositoryJson(r.url, r.baseRef, r.branch, r.push, r.mergeRequest?.let { MergeRequestJson.of(it) })
    }
}

/** `repositories[].mergeRequest` (ADR 0034): push options of one PUSH_MR repository; target = its `baseBranch`. */
data class JobMergeRequestJson(val create: Boolean = true, val title: String = "", val description: String? = null, val draft: Boolean = false) {
    fun toMergeRequest() = JobMergeRequest(create, title, description, draft)

    companion object {
        fun of(mr: JobMergeRequest) = JobMergeRequestJson(mr.create, mr.title, mr.description, mr.draft)
    }
}

/** openapi `JobRepository` (ADR 0034): one repository of a job, cloned into `/workspace/<path>`. */
data class JobRepositoryJson(
    val key: String,
    val url: String,
    val baseBranch: String,
    val access: String,
    val path: String? = null,
    val pushBranch: String? = null,
    val mergeRequest: JobMergeRequestJson? = null,
    val primary: Boolean = false,
) {
    fun toRepository() = JobRepository(
        key = key,
        url = url,
        baseBranch = baseBranch,
        access = GitAccess.entries.firstOrNull { it.name == access.uppercase() } ?: GitAccess.READ,
        path = path?.takeIf { it.isNotBlank() } ?: key,
        pushBranch = pushBranch,
        mergeRequest = mergeRequest?.toMergeRequest(),
        primary = primary,
    )

    companion object {
        fun of(r: JobRepository) = JobRepositoryJson(r.key, r.url, r.baseBranch, r.access.name, r.path, r.pushBranch, r.mergeRequest?.let { JobMergeRequestJson.of(it) }, r.primary)
    }
}

/** openapi `RunnerResult.repositories[]` (ADR 0034). */
data class RepoResultJson(
    val key: String,
    val branch: String? = null,
    val commitSha: String? = null,
    val diffStat: DiffStatJson? = null,
    val mergeRequestUrl: String? = null,
    val pushed: Boolean = false,
    val error: String? = null,
) {
    fun toRepoResult() = RepoResult(key, branch, commitSha, diffStat?.let { RunnerDiffStat(it.files, it.insertions, it.deletions) }, mergeRequestUrl, pushed, error)

    companion object {
        fun of(r: RepoResult) = RepoResultJson(r.key, r.branch, r.commitSha, r.diffStat?.let { DiffStatJson(it.files, it.insertions, it.deletions) }, r.mergeRequestUrl, r.pushed, r.error)
    }
}

/** Agent Skill written into the workspace before the agent starts. */
data class SkillJson(val key: String, val name: String, val content: String) {
    fun toSkill() = RunnerSkill(key, name, content)

    companion object {
        fun of(s: RunnerSkill) = SkillJson(s.key, s.name, s.content)
    }
}

data class RunnerJobSpecJson(
    val mode: String,
    val agent: String,
    val model: ModelRefJson,
    /**
     * ADR 0034: the repositories of the job. Null (omitted from the canonical JSON and the digest) for jobs built
     * the legacy way and in specs stored before 0034; [repository] then describes the single repository.
     */
    val repositories: List<JobRepositoryJson>? = null,
    val repository: RepositoryJson? = null,
    val specKitPhase: String? = null,
    val prompt: String,
    val constitution: String? = null,
    val systemPrompt: String? = null,
    val setupCommand: String? = null,
    val verifyCommand: String? = null,
    val timeoutSeconds: Long,
    val collectArtifacts: List<String> = emptyList(),
    val collectDiff: Boolean = true,
    val skills: List<SkillJson> = emptyList(),
    /** Written by the runner as the `Back-Office-Run` git trailer; taken from the usage context. */
    val runId: String? = null,
    /** Set in v2 assignments only: the LLM gateway endpoints the sandbox uses instead of provider keys. */
    val gateway: GatewayJson? = null,
    /**
     * `NONE | READ | PUSH_MR` (ADR 0031): the git rights of the job. Signed with the spec; the runner-controller
     * selects the credential by it and the runner pushes only for PUSH_MR. Null in specs stored before 0031.
     */
    val gitAccess: String? = null,
    /** ADR 0036: false = no Spec Kit work in the job; null (legacy-built jobs, older specs) = the runner's earlier behaviour. */
    val specKit: Boolean? = null,
    /**
     * ADR 0040, set in v2 assignments only: `LOCAL_LOGIN` = Claude Code uses the login of the personal runner's owner
     * (the controller delivers it) instead of the gateway. Absent = the gateway (older specs keep their digests).
     */
    val llmCredentials: String? = null,
    /** ADR 0040: routing to a user's personal runner; stored with the job, removed from assignments. */
    val personalRunnerOf: String? = null,
    /** ADR 0044: the run workspace (volume / PVC name, key, session role); absent = a throwaway workspace. */
    val workspace: WorkspaceJson? = null,
    /** ADR 0047: absent in older specs (their digests do not change). */
    val commitSubject: String? = null,
    val featureDescription: String? = null,
    val acceptance: AcceptanceJson? = null,
    /** ADR 0051: absent when the job uses the runner pool's tokens (older specs keep their digests). */
    val credentials: List<JobCredentialRefJson>? = null,
) {
    companion object {
        fun of(spec: RunnerJobSpec) = RunnerJobSpecJson(
            mode = spec.mode.name,
            agent = spec.agent.name,
            model = ModelRefJson.of(spec.model),
            repositories = spec.repositories.takeIf { it.isNotEmpty() }?.map { JobRepositoryJson.of(it) },
            repository = spec.repository?.let { RepositoryJson.of(it) },
            specKitPhase = spec.specKitPhase?.name,
            prompt = spec.prompt,
            constitution = spec.constitution,
            systemPrompt = spec.systemPrompt,
            setupCommand = spec.setupCommand,
            verifyCommand = spec.verifyCommand,
            timeoutSeconds = spec.timeout.seconds,
            collectArtifacts = spec.collectArtifacts,
            collectDiff = spec.collectDiff,
            skills = spec.skills.map { SkillJson.of(it) },
            runId = spec.usageContext.runId?.toString(),
            gitAccess = spec.gitAccess.name,
            specKit = spec.specKit,
            personalRunnerOf = spec.personalRunnerOf?.toString(),
            workspace = spec.workspace?.let { WorkspaceJson.of(it) },
            commitSubject = spec.commitSubject,
            featureDescription = spec.featureDescription,
            acceptance = spec.acceptance?.let { AcceptanceJson(it.files, it.protectedPaths, it.requireChanges) },
            credentials = spec.credentials.takeIf { it.isNotEmpty() }?.map { JobCredentialRefJson(it.ref, it.repository, it.capability) },
        )
    }
}

/** ADR 0051: `RunnerJobSpec.credentials` (names of project repository tokens, never values). */
data class JobCredentialRefJson(val ref: String, val repository: String, val capability: String)

/** ADR 0047: `RunnerJobSpec.acceptance`. */
data class AcceptanceJson(
    val files: Map<String, String> = emptyMap(),
    val protectedPaths: List<String> = emptyList(),
    val requireChanges: Boolean = false,
)

/** ADR 0044: `RunnerJobSpec.workspace`; `sessionRole` and `prepareOnly` are omitted when unset. */
data class WorkspaceJson(
    val name: String,
    val key: String,
    val sessionRole: String? = null,
    val prepareOnly: Boolean? = null,
    val setupCommand: String? = null,
) {
    fun toWorkspace() = RunnerWorkspace(key = key, name = name, sessionRole = sessionRole, prepareOnly = prepareOnly == true, setupCommand = setupCommand)

    companion object {
        fun of(w: RunnerWorkspace) = WorkspaceJson(w.name, w.key, w.sessionRole, w.prepareOnly.takeIf { it }, w.setupCommand)
    }
}

data class UsageContextJson(
    val kind: String,
    val projectId: String? = null,
    val runId: String? = null,
    val stepId: String? = null,
    val reviewId: String? = null,
    val benchmarkId: String? = null,
    val attemptId: String? = null,
    val actingUserId: String? = null,
)

data class RunnerEventJson(val type: String = "LOG", val message: String, val at: Instant? = null)

data class DiffStatJson(val files: Int = 0, val insertions: Int = 0, val deletions: Int = 0)

data class VerificationJson(val command: String = "", val exitCode: Int = 0, val outputTail: String = "")

data class ResultArtifactJson(val name: String, val path: String? = null, val mediaType: String = "text/markdown", val content: String = "")

data class ResultUsageJson(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val costUsd: BigDecimal? = null,
    /** ADR 0040: `SUBSCRIPTION` when Claude Code used its user's own login (cost is an estimate, nothing billed). */
    val billing: String? = null,
) {
    fun toTokenUsage() = TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens)
}

/** `RunnerResult` as posted by the runner; `finishedAt` is added by the server when stored. */
data class RunnerResultJson(
    val status: String,
    val summary: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val diffStat: DiffStatJson? = null,
    val diff: String? = null,
    val mergeRequestUrl: String? = null,
    val verification: VerificationJson? = null,
    val artifacts: List<ResultArtifactJson> = emptyList(),
    val usage: ResultUsageJson = ResultUsageJson(),
    val error: String? = null,
    val finishedAt: Instant? = null,
    /** ADR 0034: per-repository results; null from older runners (the top-level fields then describe the single repository). */
    val repositories: List<RepoResultJson>? = null,
) {
    fun toRunnerResult(finishedAt: Instant): RunnerResult = RunnerResult(
        status = RunnerJobStatus.entries.firstOrNull { it.name == status.uppercase() } ?: RunnerJobStatus.FAILED,
        summary = summary,
        branch = branch,
        commitSha = commitSha,
        diffStat = diffStat?.let { RunnerDiffStat(it.files, it.insertions, it.deletions) },
        verification = verification?.let { RunnerVerification(it.command, it.exitCode, it.outputTail) },
        artifacts = artifacts.map { RunnerArtifact(it.name, it.path, it.mediaType, it.content) },
        diff = diff,
        mergeRequestUrl = mergeRequestUrl,
        usage = usage.toTokenUsage(),
        reportedCostUsd = usage.costUsd,
        error = error,
        finishedAt = this.finishedAt ?: finishedAt,
        repositories = repositories.orEmpty().map { it.toRepoResult() },
        subscription = usage.billing.equals(BILLING_SUBSCRIPTION, ignoreCase = true),
    )

    companion object {
        fun of(result: RunnerResult) = RunnerResultJson(
            status = result.status.name,
            summary = result.summary,
            branch = result.branch,
            commitSha = result.commitSha,
            diffStat = result.diffStat?.let { DiffStatJson(it.files, it.insertions, it.deletions) },
            diff = result.diff,
            mergeRequestUrl = result.mergeRequestUrl,
            verification = result.verification?.let { VerificationJson(it.command, it.exitCode, it.outputTail) },
            artifacts = result.artifacts.map { ResultArtifactJson(it.name, it.path, it.mediaType, it.content) },
            usage = ResultUsageJson(
                result.usage.inputTokens, result.usage.outputTokens, result.usage.cacheReadTokens, result.usage.cacheWriteTokens, result.reportedCostUsd,
                billing = BILLING_SUBSCRIPTION.takeIf { result.subscription },
            ),
            error = result.error,
            finishedAt = result.finishedAt,
            repositories = result.repositories.takeIf { it.isNotEmpty() }?.map { RepoResultJson.of(it) },
        )

        const val BILLING_SUBSCRIPTION = "SUBSCRIPTION"
    }
}

/** `RunnerJobSpec.gateway` / `RunnerConfig.gateway`. */
data class GatewayJson(val anthropicBaseUrl: String, val openaiBaseUrl: String, val apiBaseUrl: String)

data class PolicyResourcesJson(val cpu: String? = null, val memory: String? = null, val ephemeralStorage: String? = null, val pids: Int? = null)

data class PolicyNetworkJson(val allow: List<String> = emptyList())

data class PolicyToolsJson(val allow: List<String> = emptyList(), val deny: List<String> = emptyList(), val ask: List<String> = emptyList())

data class PolicyEnvironmentJson(val image: String? = null, val setupCommand: String? = null, val caches: List<String> = emptyList())

/** openapi `SandboxPolicy` (network and tools nested as in the YAML of docs/architecture/10-agent-execution.md). */
data class SandboxPolicyJson(
    val isolation: String? = null,
    val resources: PolicyResourcesJson? = null,
    val timeoutSeconds: Int? = null,
    val network: PolicyNetworkJson? = null,
    val tools: PolicyToolsJson? = null,
    val secrets: List<String>? = null,
    val environment: PolicyEnvironmentJson? = null,
    val maxCostUsd: BigDecimal? = null,
    val maxTokens: Long? = null,
    val poolSelector: Map<String, String>? = null,
) {
    fun toPolicy(): SandboxPolicy = SandboxPolicy(
        isolation = isolation?.let { value ->
            SandboxIsolation.entries.firstOrNull { it.name == value.uppercase() }
                ?: throw ValidationException("isolation must be one of ${SandboxIsolation.entries.joinToString()}", "isolation")
        },
        resources = SandboxResources(resources?.cpu, resources?.memory, resources?.ephemeralStorage, resources?.pids),
        timeoutSeconds = timeoutSeconds,
        networkAllow = network?.allow.orEmpty(),
        tools = SandboxTools(tools?.allow.orEmpty(), tools?.deny.orEmpty(), tools?.ask.orEmpty()),
        secrets = secrets.orEmpty(),
        environment = SandboxEnvironment(environment?.image, environment?.setupCommand, environment?.caches.orEmpty()),
        maxCostUsd = maxCostUsd,
        maxTokens = maxTokens,
        poolSelector = poolSelector.orEmpty(),
    )

    companion object {
        fun of(p: SandboxPolicy) = SandboxPolicyJson(
            isolation = p.isolation?.name,
            resources = PolicyResourcesJson(p.resources.cpu, p.resources.memory, p.resources.ephemeralStorage, p.resources.pids),
            timeoutSeconds = p.timeoutSeconds,
            network = PolicyNetworkJson(p.networkAllow),
            tools = PolicyToolsJson(p.tools.allow, p.tools.deny, p.tools.ask),
            secrets = p.secrets,
            environment = PolicyEnvironmentJson(p.environment.image, p.environment.setupCommand, p.environment.caches),
            maxCostUsd = p.maxCostUsd,
            maxTokens = p.maxTokens,
            poolSelector = p.poolSelector,
        )
    }
}

/** openapi `JobCompletion`: the terminal report of the runner-controller (also stored on the job). */
data class JobCompletionJson(
    val status: String,
    val exitCode: Int? = null,
    val reason: String? = null,
    val imageDigest: String? = null,
    val egressLogDigest: String? = null,
    val egressDenied: List<String> = emptyList(),
    val durationMs: Long? = null,
) {
    fun toReport() = JobCompletionReport(
        status = AgentJobStatus.entries.firstOrNull { it.name == status.uppercase() }
            ?: throw ValidationException("status must be one of ${AgentJobStatus.entries.joinToString()}", "status"),
        exitCode = exitCode, reason = reason, imageDigest = imageDigest, egressLogDigest = egressLogDigest,
        egressDenied = egressDenied, durationMs = durationMs,
    )

    companion object {
        fun of(r: JobCompletionReport) = JobCompletionJson(r.status.name, r.exitCode, r.reason, r.imageDigest, r.egressLogDigest, r.egressDenied, r.durationMs)
    }
}

/** The continuation stored with a job and enqueued when it becomes terminal. */
data class WorkRequestJson(
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val dedupeKey: String? = null,
    val runAfter: Instant? = null,
    val priority: Int = 0,
    val maxAttempts: Int = 10,
) {
    fun toRequest() = WorkRequest(kind, payload, dedupeKey, runAfter, priority, maxAttempts)

    companion object {
        fun of(r: WorkRequest) = WorkRequestJson(r.kind, r.payload, r.dedupeKey, r.runAfter, r.priority, r.maxAttempts)
    }
}

fun UsageContext.toJson() = UsageContextJson(
    kind.name, projectId?.toString(), runId?.toString(), stepId, reviewId?.toString(), benchmarkId?.toString(), attemptId?.toString(),
    actingUserId?.toString(),
)

fun UsageContextJson.toContext() = UsageContext(
    kind = UsageKind.valueOf(kind),
    projectId = projectId?.let(UUID::fromString),
    runId = runId?.let(UUID::fromString),
    stepId = stepId,
    reviewId = reviewId?.let(UUID::fromString),
    benchmarkId = benchmarkId?.let(UUID::fromString),
    attemptId = attemptId?.let(UUID::fromString),
    actingUserId = actingUserId?.let(UUID::fromString),
)

/** Rebuilds the port-level spec from its stored JSON parts. */
fun RunnerJobSpecJson.toSpec(context: UsageContext, label: String, policy: SandboxPolicy?): RunnerJobSpec {
    val runnerMode = RunnerMode.valueOf(mode)
    val repo = repository?.toRepository()
    val repos = repositories.orEmpty().map { it.toRepository() }
    return RunnerJobSpec(
        mode = runnerMode,
        agent = AgentHarness.valueOf(agent),
        model = model.toModelRef(),
        prompt = prompt,
        repository = repo,
        specKitPhase = specKitPhase?.let { p -> SpecKitPhase.entries.firstOrNull { it.name == p } },
        constitution = constitution,
        systemPrompt = systemPrompt,
        setupCommand = setupCommand,
        verifyCommand = verifyCommand,
        timeout = Duration.ofSeconds(timeoutSeconds),
        collectArtifacts = collectArtifacts,
        collectDiff = collectDiff,
        skills = skills.map { it.toSkill() },
        usageContext = context,
        jobLabel = label,
        policy = policy,
        repositories = repos,
        // specs stored before ADR 0031 have no level: derived the same way Back Office derives it today
        gitAccess = gitAccess?.let { g -> GitAccess.entries.firstOrNull { it.name == g } }
            ?: if (repos.isNotEmpty()) GitAccess.highest(repos.map { it.access }) else GitAccess.derive(runnerMode, repo),
        specKit = specKit,
        personalRunnerOf = personalRunnerOf?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        workspace = workspace?.toWorkspace(),
        commitSubject = commitSubject,
        featureDescription = featureDescription,
        acceptance = acceptance?.let { RunnerAcceptance(it.files, it.protectedPaths, it.requireChanges) },
        credentials = credentials.orEmpty().map { JobCredentialRef(it.ref, it.repository, it.capability) },
    )
}
