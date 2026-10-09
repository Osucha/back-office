package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageCiTokens
import pl.mos.backoffice.projects.application.port.`in`.ManageCiTokens.CreatedToken
import pl.mos.backoffice.projects.application.port.`in`.VerifyCiToken
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.TokenHasher
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.SystemOperation

/**
 * Implements [ManageCiTokens] (`project.ci.manage`) and [VerifyCiToken] (docs/architecture/05-integrations.md):
 * tokens are `bo_ci_` + 40 url-safe random characters, stored as SHA-256; verification updates
 * `lastUsedAt` and rejects revoked tokens. Creation and revocation are audited.
 */
class CiTokenService(
    private val tokens: ProjectCiTokenRepository,
    private val projects: ProjectRepository,
    private val hasher: TokenHasher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManageCiTokens, VerifyCiToken {

    override fun create(projectId: ProjectId, name: String): CreatedToken {
        val actor = gate.require(ProjectsPermissions.PROJECT_CI_MANAGE, projectId.value).actor
        projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        val plaintext = hasher.generate(ProjectCiToken.PREFIX, ProjectCiToken.RANDOM_LENGTH)
        val token = tokens.save(
            ProjectCiToken(
                id = ProjectCiTokenId.new(),
                projectId = projectId,
                name = name.trim(),
                tokenHash = hasher.hash(plaintext),
                prefix = plaintext.take(ProjectCiToken.DISPLAY_PREFIX_LENGTH),
                createdAt = clock.now(),
                createdBy = actor,
            ),
        )
        audit.record(AuditEntry("CI_TOKEN_CREATE", ENTITY, token.id.toString(), projectId = projectId.value, details = mapOf("name" to token.name, "prefix" to token.prefix)))
        return CreatedToken(token, plaintext)
    }

    override fun list(projectId: ProjectId): List<ProjectCiToken> {
        gate.require(ProjectsPermissions.PROJECT_CI_MANAGE, projectId.value)
        projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        return tokens.findByProjectId(projectId).sortedByDescending { it.createdAt }
    }

    override fun revoke(projectId: ProjectId, tokenId: ProjectCiTokenId) {
        gate.require(ProjectsPermissions.PROJECT_CI_MANAGE, projectId.value)
        val token = tokens.findById(tokenId)?.takeIf { it.projectId == projectId } ?: throw NotFoundException("CiToken", tokenId)
        if (token.revoked) return
        tokens.save(token.revoke(clock.now()))
        audit.record(AuditEntry("CI_TOKEN_REVOKE", ENTITY, token.id.toString(), projectId = projectId.value, details = mapOf("name" to token.name, "prefix" to token.prefix)))
    }

    @SystemOperation("called by the identity bearer-token filter before any principal exists")
    override fun verify(plaintext: String): CiTokenIdentity? {
        val candidate = plaintext.trim().takeIf { it.startsWith(ProjectCiToken.PREFIX) } ?: return null
        val token = tokens.findByHash(hasher.hash(candidate)) ?: return null
        if (token.revoked) return null
        val project = projects.findById(token.projectId) ?: return null
        tokens.save(token.used(clock.now()))
        return CiTokenIdentity(token.id.value, project.id.value, project.key, token.name)
    }

    companion object {
        const val ENTITY = "CiToken"
    }
}
