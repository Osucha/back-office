package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import java.time.LocalDate

/**
 * Tokens of a project's repositories (ADR 0051): everyone who sees the project sees the list, never a token; only
 * owners (`project.connections.manage`) add, change, test and delete them. Every save tests the token against the SCM.
 */
interface ManageScmConnections {
    fun list(projectId: ProjectId): List<ScmConnectionView>
    fun create(projectId: ProjectId, command: ScmConnectionCommand): ScmConnectionView
    /** [ScmConnectionCommand.token] null keeps the stored token. */
    fun update(projectId: ProjectId, id: ScmConnectionId, command: ScmConnectionCommand): ScmConnectionView
    fun delete(projectId: ProjectId, id: ScmConnectionId)
    /** Tests the stored token again and keeps the result. */
    fun verify(projectId: ProjectId, id: ScmConnectionId): ScmConnectionView
    /** Tests a token before it is saved; nothing is stored. */
    fun test(projectId: ProjectId, command: ScmConnectionCommand): ScmVerification

    data class ScmConnectionCommand(
        val repositoryKey: String,
        val name: String,
        val capabilities: Set<ScmCapability>,
        val token: String? = null,
        val secretExpiresAt: LocalDate? = null,
        val provider: ScmProvider = ScmProvider.GITLAB,
    )

    /** A connection and whether its repository still exists in the project. */
    data class ScmConnectionView(val connection: ScmConnection, val repositoryExists: Boolean)
}

/** Startup job of the worker role after a key rotation (like [RewrapConnectionSecrets]). */
fun interface RewrapScmConnectionSecrets {
    fun rewrapScmSecrets(): Int
}
