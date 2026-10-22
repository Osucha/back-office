package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProbe
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.SealedSecret

interface ScmConnectionRepository {
    fun save(connection: ScmConnection): ScmConnection
    fun findById(id: ScmConnectionId): ScmConnection?
    fun findByProject(projectId: ProjectId): List<ScmConnection>
    fun findAll(): List<ScmConnection>
    fun delete(id: ScmConnectionId)
    /** Compare-and-set of the wrapped key after a key rotation; false when the secret changed meanwhile. */
    fun replaceSecret(id: ScmConnectionId, expected: SealedSecret, replacement: SealedSecret): Boolean
}

/** The token whose rights are tested: typed in the form, or the stored one (opened by the adapter only). */
sealed interface ScmToken {
    data class Plain(val value: String) : ScmToken {
        override fun toString() = "ScmToken.Plain(***)"
    }
    data class Stored(val connection: ScmConnection) : ScmToken
}

/**
 * Asks the SCM what a token can do in one repository (ADR 0051 point 2): git read and push over HTTP, the token's
 * scopes and expiry, its role in the project and the default branch's protection. Read-only calls; never throws for
 * a refused or unreachable SCM (that is [ScmProbe.failure]).
 */
fun interface ScmProbePort {
    fun probe(provider: ScmProvider, repository: GitRepository, token: ScmToken): ScmProbe
}
