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
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
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
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.ExecutionTarget
import pl.mos.backoffice.shared.runner.RunnerTarget

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
) : ManageBenchmarks, RecoverBenchmarks, EvaluationDashboardQuery {

    override fun create(command: BenchmarkCommand): BenchmarkDetails {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val suite = suites.findById(command.suiteId) ?: throw NotFoundException("TaskSuite", command.suiteId)
        val models = resolveModels(command.modelProfileIds)
        val judge = profiles.findById(command.judgeModelProfileId) ?: throw NotFoundException("ModelProfile", command.judgeModelProfileId)
        val unknownTasks = command.taskIds.filterNot { id -> suite.tasks.any { it.id == id } }
        if (unknownTasks.isNotEmpty()) throw ValidationException("tasks not in suite: ${unknownTasks.joinToString()}", "taskIds")
        val benchmark = benchmarks.save(
            Benchmark.create(
                name = command.name,
                suiteId = suite.id,
                taskIds = command.taskIds,
                modelProfileIds = command.modelProfileIds,
                judgeModelProfileId = judge.id,
                repetitions = command.repetitions,
                codingHarness = command.codingHarness,
                now = clock.now(),
                actor = user.actor,
                runnerTarget = command.runnerTarget,
            ),
        )
        audit.record(
            entry(
                "BENCHMARK_CREATE", benchmark.id,
                mapOf(
                    "name" to benchmark.name, "suiteId" to suite.id.toString(), "models" to models.map { it.displayName },
                    "judge" to judge.displayName, "repetitions" to benchmark.repetitions, "taskIds" to benchmark.taskIds.map { it.toString() },
                    "runnerTarget" to benchmark.runnerTarget.name,
                ),
            ),
        )
        return BenchmarkDetails(benchmark, suite.name, BenchmarkProgressCounts.EMPTY, Money.ZERO, models, judge)
    }

    override fun start(id: BenchmarkId): BenchmarkDetails {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val benchmark = load(id)
        val suite = suites.findById(benchmark.suiteId) ?: throw NotFoundException("TaskSuite", benchmark.suiteId)
        val models = resolveModels(benchmark.modelProfileIds)
        val disabled = models.filterNot { it.enabled }
        if (disabled.isNotEmpty()) throw InvalidStateException("disabled model profiles: ${disabled.joinToString { it.displayName }}")
        val judge = profiles.findById(benchmark.judgeModelProfileId) ?: throw NotFoundException("ModelProfile", benchmark.judgeModelProfileId)
        if (!judge.enabled) throw InvalidStateException("judge profile ${judge.displayName} is disabled")
        val now = clock.now()
        val attempts = BenchmarkExecutionPlanner.plan(benchmark, suite, models, now)
        if (attempts.any { it.kind == TaskKind.CODING_JOB }) requireRunnerTarget(benchmark)
        val started = benchmarks.save(benchmark.start(now, user.actor))
        benchmarks.saveAttempts(attempts)
        attempts.forEach { executor.submit(it.id) }
        audit.record(entry("BENCHMARK_START", id, mapOf("name" to benchmark.name, "attempts" to attempts.size)))
        return details(started)
    }

    override fun executionTargets(): List<ExecutionTarget> {
        val user = gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return runner.executionTargets(user.userId?.value)
    }

    /** ADR 0046: coding jobs need a pool where the benchmark sends them; a personal runner needs its creator. */
    private fun requireRunnerTarget(benchmark: Benchmark) {
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
        load(id)
        return LeaderboardCalculator.leaderboard(id, benchmarks.findAttempts(id))
    }

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
        return BenchmarkDetails(
            benchmark = benchmark,
            suiteName = suites.findById(benchmark.suiteId)?.name,
            progress = BenchmarkProgressCounts.of(attempts),
            totalCost = totalCost(attempts),
            models = benchmark.modelProfileIds.mapNotNull { profiles.findById(it) },
            judge = profiles.findById(benchmark.judgeModelProfileId),
        )
    }

    private fun totalCost(attempts: List<Attempt>): Money = Money.sum(attempts.map { it.cost + it.judgeCost })
}
