package pl.mos.backoffice.platform.application.service

import pl.mos.backoffice.platform.application.port.`in`.OperationsOverview
import pl.mos.backoffice.platform.application.port.`in`.OperationsQueries
import pl.mos.backoffice.platform.application.port.`in`.RetryWorkItem
import pl.mos.backoffice.platform.application.port.`in`.WorkItemView
import pl.mos.backoffice.platform.application.port.out.OperationsReadModel
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.platform.AgentJobStatistics
import pl.mos.backoffice.shared.platform.EventStream
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Duration
import java.util.UUID

/**
 * Implements the operations use cases (admin -> operations). Everything needs `admin.settings.manage`
 * (platform administrators); a retry is audited as `WORK_ITEM_RETRY`.
 */
class OperationsService(
    private val readModel: OperationsReadModel,
    private val events: EventStream?,
    private val agentJobs: AgentJobStatistics?,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val staleAfter: Duration,
) : OperationsQueries, RetryWorkItem {

    override fun overview(): OperationsOverview {
        gate.require(PERMISSION)
        val counts = readModel.countsByStatus()
        return OperationsOverview(
            replicas = readModel.replicas(staleAfter),
            workItems = STATUSES.associateWith { counts[it] ?: 0L },
            deadItems = counts["DEAD"] ?: 0L,
            oldestReadyAgeSeconds = readModel.oldestReadyAgeSeconds(),
            agentJobs = agentJobs?.let { runCatching { it.countsByStatus() }.getOrDefault(emptyMap()) } ?: emptyMap(),
            sseSubscribers = events?.subscribers() ?: 0,
        )
    }

    override fun workItems(status: String?, kind: String?, page: Int, size: Int): PageOf<WorkItemView> {
        gate.require(PERMISSION)
        val normalised = status?.uppercase()?.also { if (it !in STATUSES) throw ValidationException("status must be one of $STATUSES") }
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) throw ValidationException("page must be >= 0 and size within 1..$MAX_PAGE_SIZE")
        return readModel.workItems(normalised, kind?.takeIf { it.isNotBlank() }, page, size)
    }

    override fun retry(itemId: UUID) {
        gate.require(PERMISSION)
        val item = readModel.find(itemId) ?: throw NotFoundException("WorkItem", itemId)
        if (item.status != "DEAD") throw InvalidStateException("work item $itemId is ${item.status}; only DEAD items can be retried")
        val requeued = try {
            readModel.requeueDead(itemId)
        } catch (e: IllegalStateException) {
            throw InvalidStateException("work item $itemId cannot be retried: ${e.message}")
        }
        if (!requeued) throw InvalidStateException("work item $itemId is no longer DEAD")
        audit.record(AuditEntry("WORK_ITEM_RETRY", "WorkItem", itemId.toString(), details = mapOf("kind" to item.kind, "dedupeKey" to item.dedupeKey, "lastError" to item.lastError?.take(500))))
    }

    companion object {
        /** Platform administrators (identity's `admin.settings.manage`); no new key needed. */
        const val PERMISSION = "admin.settings.manage"
        val STATUSES = listOf("READY", "LEASED", "DONE", "DEAD")
        const val MAX_PAGE_SIZE = 200
    }
}
