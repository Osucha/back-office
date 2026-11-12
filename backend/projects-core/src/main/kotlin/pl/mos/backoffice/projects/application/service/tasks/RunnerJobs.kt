package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.domain.model.DiffStat
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RepositoryResult
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionSelector
import pl.mos.backoffice.projects.domain.service.StepRepository
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.JobCredentialRef
import pl.mos.backoffice.shared.runner.JobRepository
import pl.mos.backoffice.shared.runner.RunnerResult
import java.util.UUID

/** What agent jobs and delivery jobs (ADR 0063) share: project tokens, routing and the per-repository result. */
internal object RunnerJobs {

    /** The job cannot be planned (sources, branch, tools); the node fails with [message] before any job is submitted. */
    class PlanException(message: String) : RuntimeException(message)

    /**
     * ADR 0051: the least-privileged project token of each repository for its git access: READ only a verified
     * read-only token (a push token is never handed to a job that only reads, ADR 0031), PUSH_MR one that may push.
     * A repository without one gets nothing here and the runner pool's own token is used (deprecated fallback).
     */
    fun credentialsOf(scmConnections: ScmConnectionRepository?, projectId: ProjectId, repos: List<JobRepository>): List<Pair<JobCredentialRef, ScmConnection>> {
        val connections = scmConnections?.findByProject(projectId).orEmpty()
        if (connections.isEmpty()) return emptyList()
        return repos.mapNotNull { r ->
            val (needed, readOnly) = when (r.access) {
                GitAccess.READ -> ScmCapability.GIT_READ to true
                GitAccess.PUSH_MR -> ScmCapability.PUSH_BRANCH to false
                GitAccess.NONE -> return@mapNotNull null
            }
            ScmConnectionSelector.select(connections, r.key, setOf(needed), readOnly)?.let { JobCredentialRef(it.id.toString(), r.key, needed.name) to it }
        }
    }

    /** Logs which token each repository of a job gets (or the deprecated pool fallback). */
    fun logCredentials(context: NodeContext, repos: List<JobRepository>, credentials: List<Pair<JobCredentialRef, ScmConnection>>) {
        repos.filter { it.access != GitAccess.NONE }.forEach { r ->
            val match = credentials.firstOrNull { it.first.repository == r.key }
            context.log(
                "repository ${r.key}: " + (match?.let { (ref, token) -> "project token \"${token.name}\" (${ref.capability}), delivered sealed to the runner" }
                    ?: "no fitting project token, the runner pool's own token is used (deprecated, ADR 0051)"),
            )
        }
    }

    /** ADR 0040: steps of a private project started by its owner go to the owner's personal runner, when they have one. */
    fun personalRunnerOf(context: NodeContext): UUID? =
        if (context.project.personal) ActingUser.startedBy(context.run)?.userId else null

    /**
     * One [RepositoryResult] per repository of the job: from `result.repositories` (ADR 0034) by key, or - older
     * runners - the legacy top-level fields for the primary entry (pushed when it had PUSH_MR and the job succeeded).
     */
    fun repositoryResults(repositories: List<StepRepository>, result: RunnerResult, succeeded: Boolean): List<RepositoryResult> {
        val byKey = result.repositories.associateBy { it.key }
        return repositories.map { r ->
            val reported = byKey[r.key]
            val legacy = reported == null && result.repositories.isEmpty() && r.primary
            RepositoryResult(
                key = r.key,
                url = r.repository.url,
                purpose = r.repository.purpose,
                access = r.access,
                primary = r.primary,
                branch = reported?.branch ?: if (legacy) result.branch else null,
                commitSha = reported?.commitSha ?: if (legacy) result.commitSha else null,
                diffStat = (reported?.diffStat ?: if (legacy) result.diffStat else null)?.let { DiffStat(it.files, it.insertions, it.deletions) },
                mergeRequestUrl = reported?.mergeRequestUrl ?: if (legacy) result.mergeRequestUrl else null,
                pushed = reported?.pushed ?: (legacy && r.access == GitAccess.PUSH_MR && succeeded),
                error = reported?.error,
            )
        }
    }
}
