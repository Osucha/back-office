package pl.mos.backoffice.platform.application.port.out

import pl.mos.backoffice.platform.application.port.`in`.ReplicaView
import pl.mos.backoffice.platform.application.port.`in`.WorkItemView
import pl.mos.backoffice.shared.usage.PageOf
import java.time.Duration
import java.util.UUID

/** Read side of the queue and the instance registry, plus the one operator mutation. */
interface OperationsReadModel {
    fun replicas(staleAfter: Duration): List<ReplicaView>
    fun countsByStatus(): Map<String, Long>
    fun oldestReadyAgeSeconds(): Long
    fun workItems(status: String?, kind: String?, page: Int, size: Int): PageOf<WorkItemView>
    fun find(itemId: UUID): WorkItemView?
    /** DEAD -> READY; false when the item is not DEAD. Throws `IllegalStateException` when a live duplicate exists. */
    fun requeueDead(itemId: UUID): Boolean
}
