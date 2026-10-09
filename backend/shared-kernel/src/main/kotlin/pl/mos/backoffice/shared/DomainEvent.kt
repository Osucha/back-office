package pl.mos.backoffice.shared

import java.time.Instant

/**
 * Marker for domain events. `type` is the stable name used on the SSE stream (see openapi.yaml
 * `DomainEventEnvelope.type`). `payload` is what the UI receives; keep it small and serialisable
 * (primitives, strings, maps, lists).
 */
interface DomainEvent {
    val type: String
    val occurredAt: Instant
    val payload: Map<String, Any?>
}

/** Outbound port used by use cases to publish events; adapters fan them out (SSE, logs, metrics). */
fun interface EventPublisher {
    fun publish(event: DomainEvent)

    companion object {
        val NOOP: EventPublisher = EventPublisher { }
    }
}
