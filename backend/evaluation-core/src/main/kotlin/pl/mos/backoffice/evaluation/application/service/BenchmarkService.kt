package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.port.`in`.EvaluationDashboardQuery
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks.BenchmarkCommand
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks.BenchmarkDetails
import pl.mos.backoffice.evaluation.application.port.`in`.RecoverBenchmarks
import pl.mos.backoffice.evaluation.application.port.out.AttemptExecutor
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkProgressCounts
import pl.mos.backoffice.evaluation.domain.model.BenchmarkCandidate
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.CandidateRef
import pl.mos.backoffice.evaluation.domain.model.LeaderboardCandidate
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.Leaderboard
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.service.LeaderboardCalculator
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerTarget
import pl.mos.backoffice.shared.tasks.TaskCatalog

/**
 * Benchmark lifecycle. `start` persists the whole attempt matrix as `PENDING` before queueing anything,
 * so progress survives a restart; [RecoverBenchmarks.recover] re-queues what was in flight.
 *
 * Access (ADR 0017): reads need `evaluation.view`; create, start, cancel and delete need `evaluation.run`
 * and are audited (`BENCHMARK_CREATE/START/CANCEL/DELETE`). The creator is stamped as `createdBy`.
 */
class BenchmarkService(
    private val benchmarks: BenchmarkRepository,
    private val suites: TaskSuiteRepository,
    private val profiles: ModelProfileRepository,
    private val executor: AttemptExecutor,
    private val runner: AgentRunner,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    /** ADR 0059: the agents of candidates (global agents of the Task Library); null = only benchmarks of models. */
    private val agents: TaskCatalog? = null,
) : ManageBenchmarks, RecoverBenchmarks, EvaluationDashboardQuery {

    override fun create(command: BenchmarkCommand): BenchmarkDetails {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val suite = suites.findById(command.suiteId) ?: throw NotFoundException("TaskSuite", command.suiteId)
        val candidates = resolveCandidates(command)
        if (candidates.isEmpty() && command.modelProfileIds.isEmpty()) {
            throw ValidationException("at least one candidate (an agent with a model) or model is required", "candidates")
        }
        val modelIds = if (candidates.isEmpty()) command.modelProfileIds else candidates.map { it.modelProfileId }.distinct()
        val models = resolveModels(modelIds)
        val judge = profiles.findById(command.judgeModelProfileId) ?: throw NotFoundException("ModelProfile", command.judgeModelProfileId)
        val unknownTasks = command.taskIds.filterNot { id -> suite.tasks.any { it.id == id } }
        if (unknownTasks.isNotEmpty()) throw ValidationException("tasks not in suite: ${unknownTasks.joinToString()}", "taskIds")
        val tasks = if (command.taskIds.isEmpty()) suite.tasks else suite.tasks.filter { it.id in command.taskIds.toSet() }
        requireOwnModels(models + judge, user.userId?.value)
        requireLoginModelRules(codingPairs(candidates, models, command.codingHarness), tasks, command.runnerTarget)
        val benchmark = benchmarks.save(
            Benchmark.create(
                name = command.name,
                suiteId = suite.id,
                taskIds = command.taskIds,
                modelProfileIds = modelIds,
                judgeModelProfileId = judge.id,
                repetitions = command.repetitions,
                codingHarness = command.codingHarness,
                now = clock.now(),
                actor = user.actor,
                runnerTarget = command.runnerTarget,
                candidates = candidates,
            ),
        )
        audit.record(
            entry(
                "BENCHMARK_CREATE", benchmark.id,
                mapOf(
                    "name" to benchmark.name, "suiteId" to suite.id.toString(), "models" to models.map { it.displayName },
                    "candidates" to candidates.map { c -> "${c.agentKey}:${c.modelProfileId}" },
                    "judge" to judge.displayName, "repetitions" to benchmark.repetitions, "taskIds" to benchmark.taskIds.map { it.toString() },
                    "runnerTarget" to benchmark.runnerTarget.name,
                ),
            ),
        )
        return BenchmarkDetails(benchmark, suite.name, BenchmarkProgressCounts.EMPTY, Money.ZERO, models, judge, candidateRefs(benchmark, tasks, models))
    }

    override fun start(id: BenchmarkId): BenchmarkDetails {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val benchmark = refreshCandidates(load(id))
        val suite = suites.findById(benchmark.suiteId) ?: throw NotFoundException("TaskSuite", benchmark.suiteId)
        val models = resolveModels(benchmark.modelProfileIds)
        val disabled = models.filterNot { it.enabled }
        if (disabled.isNotEmpty()) throw InvalidStateException("disabled model profiles: ${disabled.joinToString { it.displayName }}")
        val judge = profiles.findById(benchmark.judgeModelProfileId) ?: throw NotFoundException("ModelProfile", benchmark.judgeModelProfileId)
        if (!judge.enabled) throw InvalidStateException("judge profile ${judge.displayName} is disabled")
        val now = clock.now()
        val attempts = BenchmarkExecutionPlanner.plan(benchmark, suite, models, now)
        // ADR 0059: a benchmark whose candidates fit none of its tasks would never finish
        if (attempts.isEmpty()) throw ValidationException("no candidate can do any of the selected tasks (model answer agents do prompt tasks, coding agents coding tasks)", "candidates")
        val tasks = BenchmarkExecutionPlanner.tasks(benchmark, suite)
        requireOwnModels(models + judge, benchmark.createdBy?.userId)
        requireLoginModelRules(codingPairs(benchmark.candidates, models, benchmark.codingHarness), tasks, benchmark.runnerTarget)
        if (attempts.any { it.kind == TaskKind.CODING_JOB }) {
            requireRunnerTarget(benchmark, attempts.any { a -> a.kind == TaskKind.CODING_JOB && models.any { it.id == a.modelProfileId && it.viaClaudeCodeLogin } })
        }
        if ((models + judge).any { it.viaClaudeCodeLogin }) requirePersonalLogin(benchmark)
        val started = benchmarks.save(benchmark.start(now, user.actor))
        benchmarks.saveAttempts(attempts)
        attempts.forEach { executor.submit(it.id) }
        audit.record(entry("BENCHMARK_START", id, mapOf("name" to benchmark.name, "attempts" to attempts.size)))
        return details(started)
    }

    /**
     * ADR 0059: the candidates of [command]: each agent must exist (a global agent of the library), a candidate without a
     * model takes the agent's own model (the agent must have one), and no agent may come twice with the same model.
     */
    private fun resolveCandidates(command: BenchmarkCommand): List<BenchmarkCandidate> {
        if (command.candidates.isEmpty()) return emptyList()
        val catalog = agents ?: throw ValidationException("agents are not available on this server; compare models instead", "candidates")
        val resolved = command.candidates.map { input ->
            val key = input.agentKey.trim()
            val agent = catalog.agent(key, null) ?: throw ValidationException("agent $key does not exist", "candidates")
            val model = input.modelProfileId ?: agent.modelProfileId
                ?: throw ValidationException("agent ${agent.name} has no model of its own; choose a model for it", "candidates")
            BenchmarkCandidate(agent.key, model, agent.name, agent.harness)
        }
        val duplicates = resolved.groupBy { it.agentKey to it.modelProfileId }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ValidationException("the same agent with the same model is listed twice: ${duplicates.joinToString { it.first }}", "candidates")
        }
        return resolved
    }

    /** ADR 0059: fresh agent names and harnesses when the benchmark starts; an agent deleted since creation stops it. */
    private fun refreshCandidates(benchmark: Benchmark): Benchmark {
        val catalog = agents
        if (benchmark.legacy || catalog == null) return benchmark
        return benchmark.withCandidates(
            benchmark.candidates.map { c ->
                val agent = catalog.agent(c.agentKey, null) ?: throw InvalidStateException("agent ${c.agentName} (${c.agentKey}) of this benchmark no longer exists")
                c.copy(agentName = agent.name, harness = agent.harness)
            },
        )
    }

    /** The models that run coding tasks, with the harness they run in (login model rules). */
    private fun codingPairs(candidates: List<BenchmarkCandidate>, models: List<ModelProfile>, legacyHarness: AgentHarness): List<Pair<ModelProfile, AgentHarness>> {
        val byId = models.associateBy { it.id }
        if (candidates.isEmpty()) return models.map { it to legacyHarness }
        return candidates.mapNotNull { c -> c.harness?.let { h -> byId[c.modelProfileId]?.let { it to h } } }
    }

    override fun executionTargets(): List<ExecutionTarget> {
        val user = gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return runner.executionTargets(user.userId?.value)
    }

    /** ADR 0050 step 2: a personal model profile takes part only in its owner's benchmarks. */
    private fun requireOwnModels(models: List<ModelProfile>, creator: java.util.UUID?) {
        val foreign = models.filter { it.ownerUserId != null && it.ownerUserId != creator }
        if (foreign.isNotEmpty()) {
            throw ValidationException("personal model profiles of another user cannot take part: ${foreign.joinToString { it.displayName }}", "modelProfileIds")
        }
    }

    /**
     * ADR 0048 / 0050 step 4: Claude Code login models answer prompt tasks and judge through PROMPT jobs on the
     * creator's personal runner; for coding tasks they run as Claude Code there too, so the benchmark must send coding
     * jobs to the personal runner with the Claude Code harness.
     */
    private fun requireLoginModelRules(
        coding: List<Pair<ModelProfile, AgentHarness>>,
        tasks: List<EvalTask>,
        target: RunnerTarget,
    ) {
        val login = coding.filter { it.first.viaClaudeCodeLogin }
        if (login.isEmpty() || tasks.none { it.kind == TaskKind.CODING_JOB }) return
        val names = login.map { it.first.displayName }.distinct().joinToString()
        if (target != RunnerTarget.PERSONAL) {
            throw ValidationException("$names use your own Claude Code login and run coding tasks only on your personal runner; choose it as the execution target", "runnerTarget")
        }
        if (login.any { it.second != AgentHarness.CLAUDE_CODE }) throw ValidationException("$names need Claude Code as the coding agent", "codingHarness")
    }

    /** ADR 0050 step 4: login models (candidates or the judge) answer on the creator's personal runner with their login. */
    private fun requirePersonalLogin(benchmark: Benchmark) {
        val personal = benchmark.createdBy?.userId?.let { owner -> runner.executionTargets(owner).firstOrNull { it.target == RunnerTarget.PERSONAL } }
        if (personal == null || !personal.exists || !personal.localLogin) {
            throw InvalidStateException("Claude Code login models need your personal runner to use your own login; set it up under My account")
        }
    }

    /** ADR 0046: coding jobs need a pool where the benchmark sends them; a personal runner needs its creator. */
    private fun requireRunnerTarget(benchmark: Benchmark, needsLocalLogin: Boolean = false) {
        val owner = benchmark.createdBy?.userId
        if (benchmark.runnerTarget == RunnerTarget.PERSONAL && owner == null) {
            throw InvalidStateException("the benchmark should run on a personal runner but has no creator")
        }
        val target = runner.executionTargets(owner).firstOrNull { it.target == benchmark.runnerTarget }
        if (target == null || !target.exists) {
            throw InvalidStateException(
                if (benchmark.runnerTarget == RunnerTarget.PERSONAL) {
                    "the selected tasks include CODING_JOB tasks but you have no personal runner; set one up under My account or choose the shared runners"
                } else {
                    "the selected tasks include CODING_JOB tasks but no shared runner pool exists; create one in Administration, Runner fleet, or choose your personal runner"
                },
            )
        }
        if (needsLocalLogin && !target.localLogin) {
            throw InvalidStateException("Claude Code login models need your personal runner to use your own login; switch its LLM credentials to Claude Code login under My account")
        }
    }

    override fun cancel(id: BenchmarkId): BenchmarkDetails {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val benchmark = load(id)
        val cancelled = benchmarks.save(benchmark.cancel(clock.now(), user.actor))
        val jobs = benchmarks.findAttemptsByStatus(id, setOf(AttemptStatus.RUNNING, AttemptStatus.JUDGING)).mapNotNull { it.runnerJobId }
        jobs.forEach { jobId -> runCatching { runner.cancel(jobId) } }
        audit.record(entry("BENCHMARK_CANCEL", id, mapOf("name" to benchmark.name, "previousStatus" to benchmark.status.name, "runnerJobsCancelled" to jobs.size)))
        return details(cancelled)
    }

    override fun get(id: BenchmarkId): BenchmarkDetails {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return details(load(id))
    }

    override fun list(): List<BenchmarkDetails> {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return benchmarks.findAll().sortedByDescending { it.createdAt }.map { details(it) }
    }

    override fun delete(id: BenchmarkId) {
        gate.require(EvaluationPermissions.EVALUATION_RUN)
        val benchmark = load(id)
        if (benchmark.status == BenchmarkStatus.RUNNING) throw InvalidStateException("benchmark $id is running; cancel it first")
        benchmarks.delete(id)
        audit.record(entry("BENCHMARK_DELETE", id, mapOf("name" to benchmark.name, "status" to benchmark.status.name)))
    }

    override fun attempts(id: BenchmarkId, taskId: EvalTaskId?, modelProfileId: ModelProfileId?): List<Attempt> {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        load(id)
        return benchmarks.findAttempts(id, taskId, modelProfileId)
            .sortedWith(compareBy<Attempt> { it.taskName }.thenBy { it.repetition }.thenBy { it.modelDisplayName })
    }

    override fun attempt(id: BenchmarkId, attemptId: AttemptId): Attempt {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        val attempt = benchmarks.findAttempt(attemptId) ?: throw NotFoundException("Attempt", attemptId)
        if (attempt.benchmarkId != id) throw NotFoundException("Attempt", attemptId)
        return attempt
    }

    override fun leaderboard(id: BenchmarkId): Leaderboard {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        val benchmark = load(id)
        return LeaderboardCalculator.leaderboard(id, benchmarks.findAttempts(id), benchmark.codingHarness, leaderboardCandidates(benchmark))
    }

    /** ADR 0059: per candidate the tasks it can and cannot do (none for a benchmark of models or without its suite). */
    private fun leaderboardCandidates(benchmark: Benchmark): List<LeaderboardCandidate> {
        if (benchmark.legacy) return emptyList()
        val suite = suites.findById(benchmark.suiteId) ?: return emptyList()
        val tasks = runCatching { BenchmarkExecutionPlanner.tasks(benchmark, suite) }.getOrDefault(emptyList())
        val notApplicable = BenchmarkExecutionPlanner.notApplicable(benchmark, tasks)
        return benchmark.candidates.mapNotNull { c ->
            val model = profiles.findById(c.modelProfileId) ?: return@mapNotNull null
            val missing = notApplicable[c.agentKey to c.modelProfileId] ?: 0
            LeaderboardCandidate(candidateRef(c, model.displayName), model.ref.provider, tasks.size - missing, missing)
        }
    }

    /**
     * ADR 0059: the candidates as the UI shows them. A benchmark of models: per model `model-answer` when it has prompt
     * tasks and the coding harness's agent when it has coding tasks.
     */
    private fun candidateRefs(benchmark: Benchmark, tasks: List<EvalTask>?, models: List<ModelProfile>): List<CandidateRef> {
        val names = models.associate { it.id to it.displayName }
        if (!benchmark.legacy) return benchmark.candidates.map { candidateRef(it, names[it.modelProfileId] ?: it.modelProfileId.toString()) }
        val kinds = tasks?.map { it.kind }?.distinct()?.sorted() ?: listOf(TaskKind.PROMPT)
        return benchmark.modelProfileIds.flatMap { modelId ->
            kinds.map { kind ->
                val (key, name, harness) = CandidateRef.legacyAgent(kind, benchmark.codingHarness)
                CandidateRef(key, name, harness, modelId, names[modelId] ?: modelId.toString())
            }
        }
    }

    private fun candidateRef(c: BenchmarkCandidate, modelName: String) = CandidateRef(c.agentKey, c.agentName, c.harness, c.modelProfileId, modelName)

    @SystemOperation("startup recovery of attempts interrupted by a restart; the benchmarks were started by authorised users")
    override fun recover(): Int {
        var requeued = 0
        for (benchmark in benchmarks.findByStatus(BenchmarkStatus.RUNNING)) {
            val inFlight = benchmarks.findAttemptsByStatus(
                benchmark.id,
                setOf(AttemptStatus.PENDING, AttemptStatus.RUNNING, AttemptStatus.JUDGING),
            )
            inFlight.forEach { attempt ->
                benchmarks.saveAttempt(attempt.requeue())
                executor.submit(attempt.id)
                requeued++
            }
        }
        return requeued
    }

    /** Dashboard tile: callers without `evaluation.view` see 0 instead of a failing landing page. */
    override fun runningBenchmarks(): Int {
        try {
            gate.require(EvaluationPermissions.EVALUATION_VIEW)
        } catch (_: InsufficientPermissionException) {
            return 0
        }
        return benchmarks.countByStatus(BenchmarkStatus.RUNNING)
    }

    override fun recentBenchmarks(limit: Int): List<EvaluationDashboardQuery.BenchmarkSummaryView> {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return benchmarks.findAll().sortedByDescending { it.createdAt }.take(limit).map { b ->
            val attempts = benchmarks.findAttempts(b.id)
            val byId = profiles.findAll().associateBy { it.id }
            EvaluationDashboardQuery.BenchmarkSummaryView(
                id = b.id,
                name = b.name,
                status = b.status,
                progress = BenchmarkProgressCounts.of(attempts),
                totalCost = totalCost(attempts),
                createdAt = b.createdAt,
                finishedAt = b.finishedAt,
                models = b.modelProfileIds.map { id ->
                    val p = byId[id]
                    EvaluationDashboardQuery.ModelSummary(id, p?.displayName ?: id.toString(), p?.ref?.provider)
                },
            )
        }
    }

    private fun load(id: BenchmarkId): Benchmark = benchmarks.findById(id) ?: throw NotFoundException("Benchmark", id)

    private fun entry(operation: String, id: BenchmarkId, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "Benchmark", entityId = id.toString(), details = details)

    private fun resolveModels(ids: List<ModelProfileId>): List<ModelProfile> = ids.map {
        profiles.findById(it) ?: throw NotFoundException("ModelProfile", it)
    }

    private fun details(benchmark: Benchmark): BenchmarkDetails {
        val attempts = benchmarks.findAttempts(benchmark.id)
        val suite = suites.findById(benchmark.suiteId)
        val models = benchmark.modelProfileIds.mapNotNull { profiles.findById(it) }
        val tasks = suite?.let { s -> runCatching { BenchmarkExecutionPlanner.tasks(benchmark, s) }.getOrNull() }
        return BenchmarkDetails(
            benchmark = benchmark,
            suiteName = suite?.name,
            progress = BenchmarkProgressCounts.of(attempts),
            totalCost = totalCost(attempts),
            models = models,
            judge = profiles.findById(benchmark.judgeModelProfileId),
            candidateRefs = candidateRefs(benchmark, tasks, models),
        )
    }

    private fun totalCost(attempts: List<Attempt>): Money = Money.sum(attempts.map { it.cost + it.judgeCost })
}
