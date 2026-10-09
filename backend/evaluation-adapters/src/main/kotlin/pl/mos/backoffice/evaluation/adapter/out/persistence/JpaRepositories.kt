package pl.mos.backoffice.evaluation.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import java.util.UUID

interface ModelProfileJpaRepository : JpaRepository<ModelProfileEntity, UUID> {
    fun findAllByEnabled(enabled: Boolean): List<ModelProfileEntity>
}

interface TaskSuiteJpaRepository : JpaRepository<TaskSuiteEntity, UUID>

interface EvalTaskJpaRepository : JpaRepository<EvalTaskEntity, UUID> {
    fun findAllBySuiteIdOrderByPosition(suiteId: UUID): List<EvalTaskEntity>
    fun findAllBySuiteIdIn(suiteIds: Collection<UUID>): List<EvalTaskEntity>

    @Modifying
    @Query("delete from EvalTaskEntity t where t.suiteId = :suiteId and t.id not in :keep")
    fun deleteBySuiteIdAndIdNotIn(suiteId: UUID, keep: Collection<UUID>)

    @Modifying
    @Query("delete from EvalTaskEntity t where t.suiteId = :suiteId")
    fun deleteBySuiteId(suiteId: UUID)
}

interface BenchmarkJpaRepository : JpaRepository<BenchmarkEntity, UUID> {
    fun findAllByStatus(status: BenchmarkStatus): List<BenchmarkEntity>
    fun countByStatus(status: BenchmarkStatus): Long
    fun existsBySuiteIdAndStatus(suiteId: UUID, status: BenchmarkStatus): Boolean

    /** Compare-and-set from RUNNING: exactly one of several concurrent last attempts wins (returns 1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BenchmarkEntity b set b.status = :status, b.finishedAt = :at where b.id = :id and b.status = :running")
    fun finishIfStatus(id: UUID, status: BenchmarkStatus, at: java.time.Instant, running: BenchmarkStatus): Int

    @Query(
        value = "select exists(select 1 from benchmark b where b.judge_model_profile_id = :id or b.model_profile_ids @> to_jsonb(cast(:idText as text)))",
        nativeQuery = true,
    )
    fun existsReferencingModel(id: UUID, idText: String): Boolean
}

interface BenchmarkAttemptJpaRepository : JpaRepository<BenchmarkAttemptEntity, UUID> {
    fun findAllByBenchmarkId(benchmarkId: UUID): List<BenchmarkAttemptEntity>
    fun findAllByBenchmarkIdAndStatusIn(benchmarkId: UUID, statuses: Collection<AttemptStatus>): List<BenchmarkAttemptEntity>
    fun countByBenchmarkIdAndStatusNotIn(benchmarkId: UUID, statuses: Collection<AttemptStatus>): Long

    @Modifying
    @Query("delete from BenchmarkAttemptEntity a where a.benchmarkId = :benchmarkId")
    fun deleteByBenchmarkId(benchmarkId: UUID)
}
