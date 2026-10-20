package pl.mos.backoffice.identity.adapter.out.persistence

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.instant
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.uuid
import pl.mos.backoffice.identity.application.port.out.AuditLogReader
import pl.mos.backoffice.identity.application.port.out.AuditQuery
import pl.mos.backoffice.identity.application.port.out.AuditRecord
import pl.mos.backoffice.identity.application.port.out.AuditRecordPage
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns an entry's `details` into the jsonb column value. Never throws: control characters are stripped
 * (PostgreSQL rejects `\u0000` in jsonb), content above [MAX_LENGTH] characters is replaced by
 * `{"truncated": true, "preview": "..."}`, and a serialisation failure yields an `{"encodingError": ...}` note.
 */
class AuditDetailsEncoder(private val json: JsonMapper) {

    fun encode(details: Map<String, Any?>): String? {
        if (details.isEmpty()) return null
        return try {
            val text = json.writeValueAsString(clean(details))
            val safe = if (text.length <= MAX_LENGTH) text else json.writeValueAsString(mapOf("truncated" to true, "preview" to take(text, PREVIEW_LENGTH)))
            if (safe.contains("\\u0000")) ERROR_JSON else safe
        } catch (e: Exception) {
            ERROR_JSON
        }
    }

    private fun clean(value: Any?): Any? = when (value) {
        null -> null
        is String -> value.filterNot { it.isISOControl() }
        is Number, is Boolean -> value
        is Enum<*> -> value.name
        is Map<*, *> -> value.entries.associate { (k, v) -> clean(k?.toString() ?: "null") to clean(v) }
        is Iterable<*> -> value.map { clean(it) }
        is Array<*> -> value.map { clean(it) }
        else -> clean(value.toString())
    }

    private fun take(text: String, limit: Int): String {
        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }

    companion object {
        const val MAX_LENGTH = 16 * 1024
        const val PREVIEW_LENGTH = 16 * 1024 - 64
        private const val ERROR_JSON = """{"encodingError":"details could not be encoded"}"""
    }
}

/** Request-scoped context of an audit row; empty on background threads. */
data class AuditOrigin(val correlationId: String?, val sourceIp: String?, val userAgent: String?) {
    companion object {
        const val MDC_KEY = "correlationId"

        fun current(): AuditOrigin {
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            return AuditOrigin(
                correlationId = MDC.get(MDC_KEY) ?: request?.getAttribute(MDC_KEY) as? String,
                sourceIp = request?.remoteAddr,
                userAgent = request?.getHeader("User-Agent")?.take(512),
            )
        }
    }
}

/**
 * [AuditPort] over JDBC (ADR 0018). The insert joins the caller's transaction (rolled back with the business
 * change) inside a savepoint, so a failing audit write never breaks the business operation: it is logged on
 * the `AUDIT` logger and counted in [failures]. Every entry is also logged on `AUDIT` (without details).
 */
class JdbcAuditLog(
    private val jdbc: JdbcClient,
    private val encoder: AuditDetailsEncoder,
    private val clock: Clock,
    private val dataSource: javax.sql.DataSource,
    private val actorResolver: () -> Actor?,
) : AuditPort {

    // JpaTransactionManager refuses PROPAGATION_NESTED, so the savepoint is set on the shared JDBC connection directly
    val failures = AtomicLong()

    override fun record(entry: AuditEntry) {
        val actor = runCatching { entry.actor ?: actorResolver() }.getOrNull() ?: ANONYMOUS
        val origin = runCatching { AuditOrigin.current() }.getOrDefault(AuditOrigin(null, null, null))
        val id = UUID.randomUUID()
        try {
            val insert = {
                jdbc.sql(
                    """
                    INSERT INTO audit_log (event_time, id, actor_kind, actor_id, actor_name, operation, entity_type, entity_id, project_id,
                                           result, details, correlation_id, source_ip, user_agent)
                    VALUES (:eventTime, :id, :actorKind, :actorId, :actorName, :operation, :entityType, :entityId, :projectId,
                            :result, CAST(:details AS jsonb), :correlationId, :sourceIp, :userAgent)
                    """.trimIndent(),
                )
                    .param("eventTime", JdbcSupport.ts(clock.now().truncatedTo(ChronoUnit.MICROS)))
                    .param("id", id).param("actorKind", actor.kindName()).param("actorId", actor.userId)
                    .param("actorName", actor.displayName.take(200)).param("operation", entry.operation).param("entityType", entry.entityType)
                    .param("entityId", entry.entityId).param("projectId", entry.projectId).param("result", entry.result.name)
                    .param("details", encoder.encode(entry.details)).param("correlationId", origin.correlationId)
                    .param("sourceIp", origin.sourceIp).param("userAgent", origin.userAgent)
                    .update()
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) withSavepoint(insert) else insert()
        } catch (e: Exception) {
            failures.incrementAndGet()
            runCatching { log.error("audit write failed operation={} entityType={} entityId={} error={}", entry.operation, entry.entityType, entry.entityId, e.toString()) }
            return
        }
        runCatching {
            log.info(
                "audit operation={} result={} entityType={} entityId={} projectId={} actorKind={} actorId={} correlationId={}",
                entry.operation, entry.result, entry.entityType, entry.entityId, entry.projectId, actor.kindName(), actor.userId, origin.correlationId,
            )
        }
    }

    /** Runs [insert] inside a JDBC savepoint of the caller's transaction; a failure rolls back to it and rethrows. */
    private fun withSavepoint(insert: () -> Any) {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            val savepoint = connection.setSavepoint()
            try {
                insert()
                connection.releaseSavepoint(savepoint)
            } catch (e: Exception) {
                runCatching { connection.rollback(savepoint) }
                throw e
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    private fun Actor.kindName(): String = if (this === ANONYMOUS) "ANONYMOUS" else kind.name

    companion object {
        private val log = LoggerFactory.getLogger("AUDIT")

        /** Stored as actor kind `ANONYMOUS` (e.g. ACCESS_DENIED before authentication). */
        val ANONYMOUS = Actor(PrincipalKind.SYSTEM, null, "anonymous")
    }
}

/** Audit search with an opaque keyset cursor (base64url of `eventTime|id`), newest first (oldest first for `at.asc`). */
class JdbcAuditLogReader(private val jdbc: JdbcClient, private val json: JsonMapper) : AuditLogReader {

    override fun search(query: AuditQuery): AuditRecordPage {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        query.from?.let { where += "event_time >= :from"; params["from"] = JdbcSupport.ts(it)!! }
        query.to?.let { where += "event_time < :to"; params["to"] = JdbcSupport.ts(it)!! }
        query.actorId?.let { where += "actor_id = :actorId"; params["actorId"] = it }
        query.operation?.let { where += "operation = :operation"; params["operation"] = it }
        query.entityType?.let { where += "entity_type = :entityType"; params["entityType"] = it }
        query.entityId?.let { where += "entity_id = :entityId"; params["entityId"] = it }
        query.projectId?.let { where += "project_id = :projectId"; params["projectId"] = it }
        query.result?.let { where += "result = :result"; params["result"] = it.name }
        query.correlationId?.let { where += "correlation_id = :correlationId"; params["correlationId"] = it }
        // at.asc runs oldest first: the keyset comparison and the ORDER BY flip, so the cursor continues in that direction
        val (comparison, direction) = if (query.ascending) ">" to "ASC" else "<" to "DESC"
        query.cursor?.let {
            val (time, id) = decode(it)
            where += "(event_time, id) $comparison (:cursorTime, :cursorId)"
            params["cursorTime"] = JdbcSupport.ts(time)!!
            params["cursorId"] = id
        }
        params["limit"] = query.size + 1
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val rows = jdbc.sql("SELECT * FROM audit_log $clause ORDER BY event_time $direction, id $direction LIMIT :limit").params(params).query { rs, _ ->
            val kind = rs.getString("actor_kind")
            AuditRecord(
                id = rs.uuid("id")!!,
                eventTime = rs.instant("event_time")!!,
                actor = Actor(runCatching { PrincipalKind.valueOf(kind) }.getOrDefault(PrincipalKind.SYSTEM), rs.uuid("actor_id"), rs.getString("actor_name")),
                operation = rs.getString("operation"),
                entityType = rs.getString("entity_type"),
                entityId = rs.getString("entity_id"),
                projectId = rs.uuid("project_id"),
                result = AuditResult.valueOf(rs.getString("result")),
                details = rs.getString("details")?.let { text -> runCatching { json.readValue(text, Map::class.java).mapKeys { it.key.toString() } }.getOrNull() } ?: emptyMap(),
                correlationId = rs.getString("correlation_id"),
                sourceIp = rs.getString("source_ip"),
            )
        }.list()
        val page = rows.take(query.size)
        val next = if (rows.size > query.size) page.last().let { encode(it.eventTime, it.id) } else null
        return AuditRecordPage(page, next)
    }

    private fun encode(time: Instant, id: UUID): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$time|$id".toByteArray())

    private fun decode(cursor: String): Pair<Instant, UUID> = try {
        val (time, id) = String(Base64.getUrlDecoder().decode(cursor)).split("|", limit = 2)
        Instant.parse(time) to UUID.fromString(id)
    } catch (e: Exception) {
        throw ValidationException("invalid cursor", "cursor")
    }
}
