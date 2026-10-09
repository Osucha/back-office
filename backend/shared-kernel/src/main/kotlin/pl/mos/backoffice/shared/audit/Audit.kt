package pl.mos.backoffice.shared.audit

import java.util.UUID

enum class AuditResult { SUCCESS, DENIED, FAILURE }

/**
 * One audit record as seen by a use case (ADR 0018). The adapter adds who (from the security context), when
 * (from the clock), the correlation id and the source address. `operation` is a code-side catalogue value
 * (`USER_UPDATE`, `RUN_APPROVE`...), stored as text so new codes need no migration.
 */
data class AuditEntry(
    val operation: String,
    val entityType: String,
    val entityId: String?,
    val result: AuditResult = AuditResult.SUCCESS,
    val projectId: UUID? = null,
    /** Small JSON-able map; for updates use [AuditableChanges.changed] to record `{field: {old, new}}`. */
    val details: Map<String, Any?> = emptyMap(),
    /**
     * Explicit actor; `null` (the default) means "whoever is in the security context". Only set where no
     * principal exists yet, e.g. `LOGIN_SUCCESS` / `LOGIN_FAILURE` recorded while the login is being resolved.
     */
    val actor: pl.mos.backoffice.shared.security.Actor? = null,
)

/**
 * Write-only port: there is no read or delete method, which is how immutability is expressed in code.
 * Implementations must write in the caller's transaction and must never throw on bad `details`.
 */
fun interface AuditPort {
    fun record(entry: AuditEntry)

    companion object {
        val NOOP: AuditPort = AuditPort { }
    }
}

/** Helpers for "what changed" diffs that the domain declares. */
object AuditableChanges {
    /** Keeps only the fields whose old and new values differ: `{field: {"old": x, "new": y}}`. */
    fun changed(vararg fields: Pair<String, Pair<Any?, Any?>>): Map<String, Any?> =
        fields.filter { (_, values) -> values.first != values.second }
            .associate { (name, values) -> name to mapOf("old" to values.first, "new" to values.second) }
}
