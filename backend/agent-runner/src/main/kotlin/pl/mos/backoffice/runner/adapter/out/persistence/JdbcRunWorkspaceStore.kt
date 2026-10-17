package pl.mos.backoffice.runner.adapter.out.persistence

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.mos.backoffice.runner.application.port.out.RunWorkspaceStore
import pl.mos.backoffice.runner.domain.RunWorkspace
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** `runner_workspace` (V54, ADR 0044). Callers hold the transaction. */
class JdbcRunWorkspaceStore(private val db: NamedParameterJdbcTemplate) : RunWorkspaceStore {

    override fun bind(name: String, runId: UUID, key: String, now: Instant): RunWorkspace {
        db.update(
            """
            INSERT INTO runner_workspace (name, run_id, workspace_key, created_at, last_used_at)
            VALUES (:name, :run, :key, :now, :now)
            ON CONFLICT (name) DO UPDATE SET
                last_used_at  = :now,
                release_after = NULL,
                pool_id       = CASE WHEN runner_workspace.released_at IS NULL THEN runner_workspace.pool_id END,
                runner_id     = CASE WHEN runner_workspace.released_at IS NULL THEN runner_workspace.runner_id END,
                active_job_id = CASE WHEN runner_workspace.released_at IS NULL THEN runner_workspace.active_job_id END,
                created_at    = CASE WHEN runner_workspace.released_at IS NULL THEN runner_workspace.created_at ELSE :now END,
                released_at   = NULL
            """.trimIndent(),
            mapOf("name" to name, "run" to runId, "key" to key, "now" to now.ts()),
        )
        return query("SELECT * FROM runner_workspace WHERE name = :name", mapOf("name" to name)).single()
    }

    override fun assignPool(name: String, poolId: String) {
        db.update(
            """
            UPDATE runner_workspace
               SET runner_id = CASE WHEN pool_id IS NULL OR pool_id = :pool THEN runner_id END,
                   pool_id   = :pool
             WHERE name = :name
            """.trimIndent(),
            mapOf("name" to name, "pool" to poolId),
        )
    }

    // re-evaluated on the updated row when two leases race (row lock): only one claim succeeds
    override fun claim(name: String, runnerId: UUID, jobId: UUID, now: Instant): Boolean = db.update(
        """
        UPDATE runner_workspace
           SET runner_id = :runner, active_job_id = :job, last_used_at = :now
         WHERE name = :name AND released_at IS NULL
           AND (active_job_id IS NULL OR active_job_id = :job)
           AND (runner_id IS NULL OR runner_id = :runner)
        """.trimIndent(),
        mapOf("name" to name, "runner" to runnerId, "job" to jobId, "now" to now.ts()),
    ) == 1

    override fun releaseJob(jobId: UUID, now: Instant) {
        db.update(
            "UPDATE runner_workspace SET active_job_id = NULL, last_used_at = :now WHERE active_job_id = :job",
            mapOf("job" to jobId, "now" to now.ts()),
        )
    }

    override fun scheduleRelease(runId: UUID, at: Instant) {
        db.update(
            "UPDATE runner_workspace SET release_after = :at WHERE run_id = :run AND released_at IS NULL",
            mapOf("run" to runId, "at" to at.ts()),
        )
    }

    override fun lockForSync(runnerId: UUID, names: Collection<String>): List<RunWorkspace> {
        val params = mapOf("runner" to runnerId, "names" to names.ifEmpty { listOf("") })
        return query(
            """
            SELECT * FROM runner_workspace
             WHERE name IN (:names) OR (runner_id = :runner AND released_at IS NULL)
             ORDER BY name
               FOR UPDATE
            """.trimIndent(),
            params,
        )
    }

    override fun markReleased(names: Collection<String>, now: Instant) {
        if (names.isEmpty()) return
        db.update(
            "UPDATE runner_workspace SET released_at = :now, active_job_id = NULL WHERE name IN (:names) AND released_at IS NULL",
            mapOf("names" to names, "now" to now.ts()),
        )
    }

    override fun forget(names: Collection<String>) {
        if (names.isEmpty()) return
        db.update(
            "UPDATE runner_workspace SET runner_id = NULL WHERE name IN (:names) AND active_job_id IS NULL",
            mapOf("names" to names),
        )
    }

    private fun query(sql: String, params: Map<String, Any?>): List<RunWorkspace> = db.query(sql, params) { rs, _ -> row(rs) }

    private fun row(rs: ResultSet) = RunWorkspace(
        name = rs.getString("name"),
        runId = rs.getObject("run_id", UUID::class.java),
        key = rs.getString("workspace_key"),
        poolId = rs.getString("pool_id"),
        runnerId = rs.getObject("runner_id", UUID::class.java),
        activeJobId = rs.getObject("active_job_id", UUID::class.java),
        createdAt = rs.instant("created_at")!!,
        lastUsedAt = rs.instant("last_used_at")!!,
        releaseAfter = rs.instant("release_after"),
        releasedAt = rs.instant("released_at"),
    )

    private fun Instant?.ts(): OffsetDateTime? = this?.atOffset(ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}
