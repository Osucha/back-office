package pl.mos.backoffice.projects.application.port.`in`.system

import pl.mos.backoffice.projects.domain.model.Connection

/**
 * System use case (ADR 0030): the daily job publishes `ConnectionExpiring` for every connection whose token expires
 * within [Connection.EXPIRY_REMINDER_DAYS] days (once per expiry date). Returns the number of reminders.
 */
fun interface RemindExpiringConnections {
    fun remindExpiring(): Int
}

/** System use case: re-wraps every stored secret to the active key-encryption key after a key rotation (ADR 0030). */
fun interface RewrapConnectionSecrets {
    /** Returns the number of re-wrapped secrets. */
    fun rewrapSecrets(): Int
}

/** ADR 0064: startup job of the worker role after a key rotation (like [RewrapScmConnectionSecrets]). */
fun interface RewrapConnectorSecrets {
    fun rewrapConnectorSecrets(): Int
}

/** Startup job of the worker role after a key rotation (like [RewrapConnectionSecrets]). */
fun interface RewrapScmConnectionSecrets {
    fun rewrapScmSecrets(): Int
}
