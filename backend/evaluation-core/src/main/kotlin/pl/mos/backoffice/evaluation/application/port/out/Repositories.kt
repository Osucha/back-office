package pl.mos.backoffice.evaluation.application.port.out

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

/** Persistence of the model catalogue. */
interface ModelProfileRepository {
    fun save(profile: ModelProfile): ModelProfile
    fun findById(id: ModelProfileId): ModelProfile?
    fun findAll(enabled: Boolean? = null): List<ModelProfile>
    fun delete(id: ModelProfileId)
    fun count(): Long
}

/** Persistence of task suites; tasks are saved together with their suite. */
interface TaskSuiteRepository {
    fun save(suite: TaskSuite): TaskSuite
    fun findById(id: TaskSuiteId): TaskSuite?
    fun findAll(): List<TaskSuite>
    fun delete(id: TaskSuiteId)
    fun count(): Long
}

/**
 * Persistence of benchmarks and their attempts. Attempts are stored individually so concurrent
 * executors update disjoint rows.
 */
interface BenchmarkRepository {
    fun save(benchmark: Benchmark): Benchmark
    fun findById(id: BenchmarkId): Benchmark?
    fun findAll(): List<Benchmark>
    fun findByStatus(status: BenchmarkStatus): List<Benchmark>
    fun countByStatus(status: BenchmarkStatus): Int
    fun existsReferencingModel(modelProfileId: ModelProfileId): Boolean
    fun existsForSuiteWithStatus(suiteId: TaskSuiteId, status: BenchmarkStatus): Boolean
    /**
     * Atomically moves a RUNNING benchmark to [status]; false when it is no longer RUNNING (another worker
     * finished it first). Attempts run on several workers, so "the last attempt" can be seen by two of them.
     */
    fun finishIfRunning(id: BenchmarkId, status: BenchmarkStatus, finishedAt: java.time.Instant): Boolean
    /** Deletes the benchmark and its attempts. */
    fun delete(id: BenchmarkId)

    fun saveAttempts(attempts: List<Attempt>)
    fun saveAttempt(attempt: Attempt): Attempt
    fun findAttempt(id: AttemptId): Attempt?
    fun findAttempts(benchmarkId: BenchmarkId, taskId: EvalTaskId? = null, modelProfileId: ModelProfileId? = null): List<Attempt>
    fun findAttemptsByStatus(benchmarkId: BenchmarkId, statuses: Set<AttemptStatus>): List<Attempt>
    fun countAttemptsNotIn(benchmarkId: BenchmarkId, statuses: Set<AttemptStatus>): Int
}
