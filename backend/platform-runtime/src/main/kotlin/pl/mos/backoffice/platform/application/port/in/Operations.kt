package pl.mos.backoffice.platform.application.port.`in`

import pl.mos.backoffice.shared.usage.PageOf
import java.time.Instant
import java.util.UUID

data class ReplicaView(val instance: String, val roles: List<String>, val version: String?, val lastSeenAt: Instant, val stale: Boolean)

data class WorkItemView(
    val id: UUID,
    val kind: String,
    val dedupeKey: String?,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val runAfter: Instant,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OperationsOverview(
    val replicas: List<ReplicaView>,
    /** READY / LEASED / DONE / DEAD counts. */
    val workItems: Map<String, Long>,
    val deadItems: Long,
    val oldestReadyAgeSeconds: Long,
    /** Per status, when the runner control plane contributes it; empty otherwise. */
    val agentJobs: Map<String, Int>,
    /** SSE subscribers connected to the replica that answered. */
    val sseSubscribers: Int,
)

/** Use case (admin -> operations, `admin.settings.manage`): what the backbone is doing. */
interface OperationsQueries {
    fun overview(): OperationsOverview
    fun workItems(status: String?, kind: String?, page: Int, size: Int): PageOf<WorkItemView>
}

/** Use case: put a DEAD work item back to READY with a fresh attempt budget (audited `WORK_ITEM_RETRY`). */
interface RetryWorkItem {
    fun retry(itemId: UUID)
}
