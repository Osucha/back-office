package pl.mos.backoffice.projects.adapter.`in`.sse

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import pl.mos.backoffice.projects.adapter.`in`.rest.DomainEventEnvelopeDto
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.platform.EventStream
import pl.mos.backoffice.shared.platform.StoredEvent
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `GET /api/v1/events`: server-sent events across all replicas (ADR 0023). Every domain event, published on
 * any replica, is written to `event_log` and announced by `NOTIFY`; this replica's [EventStream] (the platform
 * LISTEN bridge) delivers it here and it is fanned out as a `DomainEventEnvelope` whose id is the `event_log`
 * id. Authentication is the session cookie (EventSource sends it); the subscriber's `CurrentUser` is captured
 * at subscription and an event whose payload names a `projectId` is only delivered to subscribers holding
 * `project.view` on that project (events without a project, e.g. benchmark progress, go to every subscriber).
 * `Last-Event-ID` (header, or `?lastEventId=` for EventSource reconnects, possibly to another replica) is
 * replayed from `event_log` (retention 24 h) with the same filter; a heartbeat comment is written every 20 s.
 */
@RestController
class EventStreamController(private val json: JsonMapper, private val gate: AuthorizationGate, private val events: EventStream) {

    private class Subscriber(val emitter: SseEmitter, val user: CurrentUser) {
        @Volatile var handle: AutoCloseable? = null
        /** Recently sent ids, so an event racing between replay and live delivery is not sent twice. */
        val sent = LinkedHashSet<Long>()
    }

    private val log = LoggerFactory.getLogger(EventStreamController::class.java)
    private val subscribers = ConcurrentHashMap.newKeySet<Subscriber>()

    @GetMapping("/api/v1/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @RequestHeader(value = "Last-Event-ID", required = false) lastEventIdHeader: String?,
        @RequestParam(value = "lastEventId", required = false) lastEventIdParam: String?,
    ): SseEmitter {
        val lastEventId = lastEventIdHeader?.takeIf { it.isNotBlank() } ?: lastEventIdParam
        val subscriber = Subscriber(SseEmitter(0L), gate.currentUser())
        val emitter = subscriber.emitter
        emitter.onCompletion { drop(subscriber) }
        emitter.onTimeout { drop(subscriber) }
        emitter.onError { drop(subscriber) }
        subscribers += subscriber
        subscriber.handle = events.subscribe { deliver(subscriber, it) }
        lastEventId?.toLongOrNull()?.let { replay(subscriber, it) }
        return emitter
    }

    /** Fans [event] out to every local subscriber allowed to see it (tests inject events here). */
    fun onEvent(event: StoredEvent) = subscribers.forEach { deliver(it, event) }

    @Scheduled(fixedRate = 20_000)
    fun heartbeat() {
        subscribers.forEach { subscriber ->
            try {
                subscriber.emitter.send(SseEmitter.event().comment("heartbeat"))
            } catch (e: Exception) {
                drop(subscriber)
            }
        }
    }

    /** Number of connected clients on this replica (for tests and diagnostics). */
    fun connectedClients(): Int = subscribers.size

    private fun replay(subscriber: Subscriber, lastId: Long) {
        events.replayAfter(lastId).forEach { deliver(subscriber, it) }
    }

    private fun deliver(subscriber: Subscriber, event: StoredEvent) {
        val envelope = DomainEventEnvelopeDto(event.id.toString(), event.type, event.occurredAt, event.payload)
        if (!visible(subscriber.user, envelope)) return
        synchronized(subscriber) {
            if (!subscriber.sent.add(event.id)) return
            if (subscriber.sent.size > SENT_MEMORY) subscriber.sent.iterator().let { it.next(); it.remove() }
            try {
                subscriber.emitter.send(
                    SseEmitter.event()
                        .id(envelope.id)
                        .name(envelope.type)
                        .data(json.writeValueAsString(envelope), MediaType.APPLICATION_JSON),
                )
            } catch (e: Exception) {
                log.debug("sse client dropped: {}", e.message)
                drop(subscriber)
                runCatching { subscriber.emitter.complete() }
            }
        }
    }

    private fun drop(subscriber: Subscriber) {
        subscribers.remove(subscriber)
        runCatching { subscriber.handle?.close() }
    }

    companion object {
        private const val SENT_MEMORY = 1_000

        /**
         * Delivery rule: an `ownerUserId` in the payload (personal connections, ADR 0035) -> that user only; no
         * `projectId` in the payload (or a null one) -> everyone; a project id -> only holders of `project.view` on it;
         * an unparseable id -> nobody (fail closed).
         */
        fun visible(user: CurrentUser, envelope: DomainEventEnvelopeDto): Boolean {
            envelope.payload["ownerUserId"]?.let { owner -> return owner.toString() == user.userId?.value?.toString() }
            val raw = envelope.payload["projectId"] ?: return true
            val projectId = runCatching { UUID.fromString(raw.toString()) }.getOrNull() ?: return false
            return user.can(ProjectsPermissions.PROJECT_VIEW, projectId)
        }
    }
}
