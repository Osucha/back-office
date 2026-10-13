package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.IssueTrackerBinding
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectCiToken
import pl.mos.backoffice.projects.domain.model.ProjectCiTokenId
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.service.CiSnippet
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.security.CiTokenIdentity

/**
 * Use case: CRUD of projects. Creating a project (`projects.create`) makes the creator its OWNER. A project with
 * at least one repository gets the default SDLC workflows ("Issue to reviewed MR", "CI code review", "Change
 * request (delta)") and the `code-review` CI entrypoint (BLOCKING); a project without repositories gets the
 * starter "LLM task with review" (ADR 0034) and gets the SDLC workflows once it receives its first repository.
 * `list` returns only projects the caller may view.
 */
interface ManageProjects {
    fun create(command: ProjectCommand): Project
    fun update(id: ProjectId, command: ProjectCommand): Project
    fun delete(id: ProjectId)
    fun get(id: ProjectId): Project
    fun list(): List<Project>
    fun stats(id: ProjectId): ProjectStats
    fun entrypoints(id: ProjectId): List<CiEntrypoint>
    /** Replaces the entrypoints; every workflow must belong to the project. */
    fun replaceEntrypoints(id: ProjectId, entrypoints: List<CiEntrypoint>): List<CiEntrypoint>
    fun ciSnippet(id: ProjectId): CiSnippet

    /** ADR 0036: whether the project can use Spec Kit and which seeded Spec Kit workflows it lacks (`project.view`). */
    fun specKitStatus(id: ProjectId): SpecKitStatus

    /**
     * ADR 0036: seeds the Spec Kit workflows the project lacks ("Add Spec Kit workflows"); needs `workflow.edit`, Spec
     * Kit on for the project and the platform, and at least one repository. Audited `PROJECT_SPEC_KIT_SEEDED`.
     */
    fun addSpecKitWorkflows(id: ProjectId): List<pl.mos.backoffice.projects.domain.model.WorkflowDefinition>

    data class SpecKitStatus(val enabled: Boolean, val allowedByPlatform: Boolean, val missingWorkflows: List<String>) {
        val effective: Boolean get() = enabled && allowedByPlatform
    }

    data class ProjectCommand(
        /** Short code; null or blank = generated from the name on create, unchanged on update. */
        val key: String?,
        val name: String,
        val description: String = "",
        /** Zero or more repositories (ADR 0034); keys unique, at most one primary. */
        val repositories: List<GitRepository> = emptyList(),
        val issueTracker: IssueTrackerBinding = IssueTrackerBinding(),
        val constitution: String = "",
        val defaults: ProjectDefaults = ProjectDefaults(),
        /** ADR 0036: Spec Kit for this project; null = off on create, unchanged on update. */
        val specKit: Boolean? = null,
        /** ADR 0038: create a private project (only on create; ignored on update). */
        val personal: Boolean = false,
    )

    data class ProjectStats(val workflows: Int, val activeRuns: Int, val openApprovals: Int, val ciExecutionsLast7Days: Int, val costLast30Days: Money)
}

/** Use case: issue, list and revoke project CI tokens. The plaintext is returned exactly once. */
interface ManageCiTokens {
    fun create(projectId: ProjectId, name: String): CreatedToken
    fun list(projectId: ProjectId): List<ProjectCiToken>
    fun revoke(projectId: ProjectId, tokenId: ProjectCiTokenId)

    data class CreatedToken(val token: ProjectCiToken, val plaintext: String)
}

/**
 * Internal use case behind the shared `CiTokenVerifier` port: resolves a `bo_ci_...` plaintext to its
 * token and project; updates `lastUsedAt`. Called by the identity context's bearer-token filter.
 */
interface VerifyCiToken {
    /** Returns null when the token is malformed, unknown or revoked. */
    fun verify(plaintext: String): CiTokenIdentity?
}
