package pl.mos.backoffice.evaluation.adapter.out.persistence

import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.usage.Pricing

/** `ModelProfileRepository` on JPA. */
@Repository
@Transactional
class ModelProfilePersistenceAdapter(
    private val jpa: ModelProfileJpaRepository,
    private val mappers: EvaluationMappers,
) : ModelProfileRepository {
    override fun save(profile: ModelProfile): ModelProfile = mappers.toDomain(jpa.save(mappers.toEntity(profile)))
    override fun findById(id: ModelProfileId): ModelProfile? = jpa.findById(id.value).map(mappers::toDomain).orElse(null)
    override fun findAll(enabled: Boolean?): List<ModelProfile> =
        (if (enabled == null) jpa.findAll() else jpa.findAllByEnabled(enabled)).map(mappers::toDomain)
    override fun delete(id: ModelProfileId) = jpa.deleteById(id.value)
    override fun count(): Long = jpa.count()
}

/**
 * `ModelCatalog` (shared port) backed by the same table: how the Projects context and the LLM gateway
 * see the catalogue. `findPricing` matches provider and model id, preferring enabled profiles.
 */
@Repository
@Transactional(readOnly = true)
class ModelCatalogAdapter(
    private val jpa: ModelProfileJpaRepository,
    private val mappers: EvaluationMappers,
) : ModelCatalog {
    // ADR 0050: login models are visible (with their runtime): coding steps of private projects may use them, LLM steps
    // refuse them; the gateway never prices them
    override fun find(id: ModelProfileId): ModelProfileSnapshot? = jpa.findById(id.value).map { mappers.toDomain(it).toSnapshot() }.orElse(null)
    override fun findAllEnabled(): List<ModelProfileSnapshot> = jpa.findAllByEnabled(true).map { mappers.toDomain(it).toSnapshot() }
    override fun findPricing(ref: ModelRef): Pricing? = jpa.findAll()
        .filter { it.runtime == ModelRuntime.API && it.provider == ref.provider && it.modelId == ref.modelId }
        .sortedByDescending { it.enabled }
        .firstOrNull()
        ?.let { mappers.toDomain(it).pricing }
}

/** `TaskSuiteRepository` on JPA; tasks are stored in `eval_task` and reconciled on every suite save. */
@Repository
@Transactional
class TaskSuitePersistenceAdapter(
    private val suites: TaskSuiteJpaRepository,
    private val tasks: EvalTaskJpaRepository,
    private val mappers: EvaluationMappers,
) : TaskSuiteRepository {

    override fun save(suite: TaskSuite): TaskSuite {
        suites.save(mappers.toEntity(suite))
        if (suite.tasks.isEmpty()) {
            tasks.deleteBySuiteId(suite.id.value)
        } else {
            tasks.deleteBySuiteIdAndIdNotIn(suite.id.value, suite.tasks.map { it.id.value })
            tasks.saveAll(suite.tasks.mapIndexed { index, task -> mappers.toEntity(task, suite.id, index) })
        }
        return suite
    }

    override fun findById(id: TaskSuiteId): TaskSuite? =
        suites.findById(id.value).map { mappers.toDomain(it, tasks.findAllBySuiteIdOrderByPosition(it.id)) }.orElse(null)

    override fun findAll(): List<TaskSuite> {
        val all = suites.findAll()
        if (all.isEmpty()) return emptyList()
        val tasksBySuite = tasks.findAllBySuiteIdIn(all.map { it.id }).groupBy { it.suiteId }
        return all.map { mappers.toDomain(it, tasksBySuite[it.id].orEmpty()) }
    }

    override fun delete(id: TaskSuiteId) {
        tasks.deleteBySuiteId(id.value)
        suites.deleteById(id.value)
    }

    override fun count(): Long = suites.count()
}

/** `BenchmarkRepository` on JPA; attempts live in `benchmark_attempt` and are saved row by row. */
@Repository
@Transactional
class BenchmarkPersistenceAdapter(
    private val benchmarks: BenchmarkJpaRepository,
    private val attempts: BenchmarkAttemptJpaRepository,
    private val mappers: EvaluationMappers,
) : BenchmarkRepository {

    override fun save(benchmark: Benchmark): Benchmark = mappers.toDomain(benchmarks.save(mappers.toEntity(benchmark)))
    override fun findById(id: BenchmarkId): Benchmark? = benchmarks.findById(id.value).map(mappers::toDomain).orElse(null)
    override fun findAll(): List<Benchmark> = benchmarks.findAll().map(mappers::toDomain)
    override fun findByStatus(status: BenchmarkStatus): List<Benchmark> = benchmarks.findAllByStatus(status).map(mappers::toDomain)
    override fun countByStatus(status: BenchmarkStatus): Int = benchmarks.countByStatus(status).toInt()
    override fun existsReferencingModel(modelProfileId: ModelProfileId): Boolean =
        benchmarks.existsReferencingModel(modelProfileId.value, modelProfileId.value.toString())
    override fun existsForSuiteWithStatus(suiteId: TaskSuiteId, status: BenchmarkStatus): Boolean =
        benchmarks.existsBySuiteIdAndStatus(suiteId.value, status)

    override fun finishIfRunning(id: BenchmarkId, status: BenchmarkStatus, finishedAt: java.time.Instant): Boolean =
        benchmarks.finishIfStatus(id.value, status, finishedAt, BenchmarkStatus.RUNNING) == 1

    override fun delete(id: BenchmarkId) {
        attempts.deleteByBenchmarkId(id.value)
        benchmarks.deleteById(id.value)
    }

    override fun saveAttempts(attempts: List<Attempt>) {
        this.attempts.saveAll(attempts.map(mappers::toEntity))
    }

    override fun saveAttempt(attempt: Attempt): Attempt = mappers.toDomain(attempts.save(mappers.toEntity(attempt)))
    override fun findAttempt(id: AttemptId): Attempt? = attempts.findById(id.value).map(mappers::toDomain).orElse(null)

    override fun findAttempts(benchmarkId: BenchmarkId, taskId: EvalTaskId?, modelProfileId: ModelProfileId?): List<Attempt> =
        attempts.findAllByBenchmarkId(benchmarkId.value)
            .asSequence()
            .filter { taskId == null || it.taskId == taskId.value }
            .filter { modelProfileId == null || it.modelProfileId == modelProfileId.value }
            .map(mappers::toDomain)
            .toList()

    override fun findAttemptsByStatus(benchmarkId: BenchmarkId, statuses: Set<AttemptStatus>): List<Attempt> =
        attempts.findAllByBenchmarkIdAndStatusIn(benchmarkId.value, statuses).map(mappers::toDomain)

    override fun countAttemptsNotIn(benchmarkId: BenchmarkId, statuses: Set<AttemptStatus>): Int =
        attempts.countByBenchmarkIdAndStatusNotIn(benchmarkId.value, statuses).toInt()
}
