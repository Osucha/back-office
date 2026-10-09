package pl.mos.backoffice.evaluation.support

import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.ModelProfileId
import java.util.concurrent.ConcurrentHashMap

class InMemoryModelProfileRepository : ModelProfileRepository {
    val store = ConcurrentHashMap<ModelProfileId, ModelProfile>()
    override fun save(profile: ModelProfile): ModelProfile { store[profile.id] = profile; return profile }
    override fun findById(id: ModelProfileId): ModelProfile? = store[id]
    override fun findAll(enabled: Boolean?): List<ModelProfile> = store.values.filter { enabled == null || it.enabled == enabled }
    override fun delete(id: ModelProfileId) { store.remove(id) }
    override fun count(): Long = store.size.toLong()
}

class InMemoryTaskSuiteRepository : TaskSuiteRepository {
    val store = ConcurrentHashMap<TaskSuiteId, TaskSuite>()
    override fun save(suite: TaskSuite): TaskSuite { store[suite.id] = suite; return suite }
    override fun findById(id: TaskSuiteId): TaskSuite? = store[id]
    override fun findAll(): List<TaskSuite> = store.values.toList()
    override fun delete(id: TaskSuiteId) { store.remove(id) }
    override fun count(): Long = store.size.toLong()
}

class InMemoryBenchmarkRepository : BenchmarkRepository {
    val benchmarks = ConcurrentHashMap<BenchmarkId, Benchmark>()
    val attempts = ConcurrentHashMap<AttemptId, Attempt>()

    override fun save(benchmark: Benchmark): Benchmark { benchmarks[benchmark.id] = benchmark; return benchmark }
    override fun findById(id: BenchmarkId): Benchmark? = benchmarks[id]
    override fun findAll(): List<Benchmark> = benchmarks.values.toList()
    override fun findByStatus(status: BenchmarkStatus): List<Benchmark> = benchmarks.values.filter { it.status == status }
    override fun countByStatus(status: BenchmarkStatus): Int = findByStatus(status).size
    override fun finishIfRunning(id: BenchmarkId, status: BenchmarkStatus, finishedAt: java.time.Instant): Boolean {
        var won = false
        benchmarks.computeIfPresent(id) { _, b ->
            if (b.status == BenchmarkStatus.RUNNING) { won = true; b.copy(status = status, finishedAt = finishedAt) } else b
        }
        return won
    }
    override fun existsReferencingModel(modelProfileId: ModelProfileId): Boolean =
        benchmarks.values.any { modelProfileId in it.modelProfileIds || it.judgeModelProfileId == modelProfileId }
    override fun existsForSuiteWithStatus(suiteId: TaskSuiteId, status: BenchmarkStatus): Boolean =
        benchmarks.values.any { it.suiteId == suiteId && it.status == status }
    override fun delete(id: BenchmarkId) {
        benchmarks.remove(id)
        attempts.values.filter { it.benchmarkId == id }.forEach { attempts.remove(it.id) }
    }

    override fun saveAttempts(attempts: List<Attempt>) = attempts.forEach { this.attempts[it.id] = it }
    override fun saveAttempt(attempt: Attempt): Attempt { attempts[attempt.id] = attempt; return attempt }
    override fun findAttempt(id: AttemptId): Attempt? = attempts[id]
    override fun findAttempts(benchmarkId: BenchmarkId, taskId: EvalTaskId?, modelProfileId: ModelProfileId?): List<Attempt> =
        attempts.values.filter {
            it.benchmarkId == benchmarkId && (taskId == null || it.taskId == taskId) && (modelProfileId == null || it.modelProfileId == modelProfileId)
        }
    override fun findAttemptsByStatus(benchmarkId: BenchmarkId, statuses: Set<AttemptStatus>): List<Attempt> =
        attempts.values.filter { it.benchmarkId == benchmarkId && it.status in statuses }
    override fun countAttemptsNotIn(benchmarkId: BenchmarkId, statuses: Set<AttemptStatus>): Int =
        attempts.values.count { it.benchmarkId == benchmarkId && it.status !in statuses }
}
