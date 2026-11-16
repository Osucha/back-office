package pl.mos.backoffice.identity.application.port.`in`.system

import pl.mos.backoffice.identity.domain.model.User

/** On startup: built-in roles and the SYSTEM default role (idempotent). */
interface SeedIdentity {
    fun seed()
}

/** Startup: creates the configured local platform admin when no user exists yet. */
interface BootstrapLocalAdmin {
    /** The created user, or `null` when nothing was configured or users already exist. */
    fun bootstrap(): User?
}
