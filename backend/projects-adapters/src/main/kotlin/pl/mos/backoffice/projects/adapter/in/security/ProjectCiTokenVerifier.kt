package pl.mos.backoffice.projects.adapter.`in`.security

import pl.mos.backoffice.projects.application.port.`in`.VerifyCiToken
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.CiTokenVerifier

/**
 * The shared [CiTokenVerifier] port backed by the project CI tokens of this context. The identity
 * context's bearer-token filter calls it for `Authorization: Bearer bo_ci_...` and turns the result into a
 * `CurrentUser` of kind `CI_TOKEN` holding `ci.execute` and `ci.signal` on that project; the CI endpoints
 * then only call the use cases, which check those permissions through the gate.
 */
class ProjectCiTokenVerifier(private val tokens: VerifyCiToken) : CiTokenVerifier {
    override fun verify(plaintext: String): CiTokenIdentity? = tokens.verify(plaintext)
}
