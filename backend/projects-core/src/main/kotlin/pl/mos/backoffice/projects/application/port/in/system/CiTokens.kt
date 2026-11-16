package pl.mos.backoffice.projects.application.port.`in`.system

import pl.mos.backoffice.shared.security.CiTokenIdentity

/**
 * Internal use case behind the shared `CiTokenVerifier` port: resolves a `bo_ci_...` plaintext to its
 * token and project; updates `lastUsedAt`. Called by the identity context's bearer-token filter.
 */
interface VerifyCiToken {
    /** Returns null when the token is malformed, unknown or revoked. */
    fun verify(plaintext: String): CiTokenIdentity?
}
