package pl.mos.backoffice.runner.domain

import java.time.Instant
import java.util.UUID

/**
 * ADR 0044: the persistent workspace of a run (`runner_workspace`). It lives on [runnerId] (the runner that first leased
 * one of its jobs) in [poolId] and is used by one job at a time ([activeJobId]). [releaseAfter] is set when the run
 * ends; [releasedAt] once released (its runner deletes the volume; a later job starts a new, empty one).
 */
data class RunWorkspace(
    val name: String,
    val runId: UUID,
    val key: String,
    val poolId: String?,
    val runnerId: UUID?,
    val activeJobId: UUID?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val releaseAfter: Instant?,
    val releasedAt: Instant?,
) {
    val released: Boolean get() = releasedAt != null
}

/**
 * What a runner does with the workspaces it reports (`POST /runner/v2/workspaces/sync`) and what Back Office records:
 * [delete] = volumes the runner removes, [release] = rows marked released now, [forget] = rows of this runner whose
 * volume is gone (the next job starts on any runner of the pool with an empty workspace).
 */
data class WorkspaceSyncDecision(val delete: List<String>, val release: List<String>, val forget: List<String>)

object WorkspaceSync {
    /**
     * [held] = volume names the runner [runnerId] holds; [rows] = the workspace rows named in [held] plus the rows on
     * this runner that are not released. A volume is deleted when Back Office does not know it, when it is released or
     * due (and unused), or when its workspace now belongs elsewhere.
     */
    fun decide(runnerId: UUID, held: Collection<String>, rows: Collection<RunWorkspace>, now: Instant): WorkspaceSyncDecision {
        val byName = rows.associateBy { it.name }
        val delete = mutableListOf<String>()
        val release = mutableListOf<String>()
        for (name in held.distinct()) {
            val row = byName[name]
            when {
                row == null || row.released -> delete += name
                row.runnerId != runnerId -> delete += name
                due(row, now) -> {
                    release += name
                    delete += name
                }
            }
        }
        // due rows of this runner whose volume is already gone are released too; unused rows without a volume are forgotten
        val heldSet = held.toSet()
        val forget = mutableListOf<String>()
        for (row in rows) {
            if (row.name in heldSet || row.runnerId != runnerId || row.released) continue
            if (due(row, now)) release += row.name else if (row.activeJobId == null) forget += row.name
        }
        return WorkspaceSyncDecision(delete, release, forget)
    }

    private fun due(row: RunWorkspace, now: Instant): Boolean =
        row.activeJobId == null && row.releaseAfter != null && !row.releaseAfter.isAfter(now)
}
