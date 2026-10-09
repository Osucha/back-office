package pl.mos.backoffice.identity.adapter.out.persistence

import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Small helpers shared by the JDBC adapters of the identity context. */
internal object JdbcSupport {
    /** Target id stored for SYSTEM-level grants (the primary key needs a non-null value). */
    val SYSTEM_TARGET: UUID = UUID(0L, 0L)

    fun ts(instant: Instant?): Timestamp? = instant?.let(Timestamp::from)

    fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

    fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

    fun ResultSet.strings(column: String): Set<String> =
        (getArray(column)?.array as? Array<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()

    /** Arrays are written as `string_to_array(:param, ',')`; keys and domains never contain commas. */
    fun csv(values: Collection<String>): String {
        require(values.none { ',' in it }) { "values must not contain commas: $values" }
        return values.sorted().joinToString(",")
    }

    fun actor(rs: ResultSet, prefix: String): Actor =
        Actor(PrincipalKind.valueOf(rs.getString("${prefix}_kind")), rs.uuid("${prefix}_id"), rs.getString("${prefix}_name"))

    fun likePattern(q: String): String = "%" + q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
