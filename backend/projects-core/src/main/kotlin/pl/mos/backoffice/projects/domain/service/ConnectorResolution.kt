package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionSelector
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.runner.JobConnectors
import pl.mos.backoffice.shared.runner.JobCredentialRef
import pl.mos.backoffice.shared.runner.RunnerConnector

/**
 * ADR 0064: the connectors an agent's job gets. Each key of the agent is looked up in the run's project, then the
 * platform; a key found in neither is skipped with a note (the agent audit reports it as `UNKNOWN_CONNECTOR`).
 *
 * - `MCP_HTTP`: listed with its URL, auth type and header name; a secret (auth `BEARER` / `HEADER`) is named in the
 *   credentials with capability `CONNECTOR` (ref = connector id). One whose auth needs a secret it does not have is skipped.
 * - `GITLAB_READ`: the repository (the connector's, else the job's primary one) must be a repository of the job, else the
 *   connector is skipped; it gets the least-privileged verified read-only `CI_READ` token of that repository (ADR 0051).
 *   Without one the job cannot be planned ([Result.failure]): a token with write powers never reaches an agent.
 */
object ConnectorResolution {

    /** One job repository as the resolution needs it. */
    data class Repository(val key: String, val primary: Boolean)

    /** [connectors] for the job, or the [failure] that stops the step before any job exists. */
    data class Result(val connectors: JobConnectors, val failure: String? = null)

    /** The connector of [key] a project sees: its own first, then the platform's; null when neither has one. */
    fun lookup(key: String, project: List<Connector>, platform: List<Connector>): Connector? =
        project.firstOrNull { it.key == key } ?: platform.firstOrNull { it.key == key }

    fun resolve(
        keys: List<String>,
        project: List<Connector>,
        platform: List<Connector>,
        repositories: List<Repository>,
        scmConnections: List<ScmConnection>,
    ): Result {
        val connectors = mutableListOf<RunnerConnector>()
        val credentials = mutableListOf<JobCredentialRef>()
        val notes = mutableListOf<String>()
        keys.distinct().forEach { key ->
            val connector = lookup(key, project, platform)
            if (connector == null) {
                notes += "connector $key is defined neither in this project nor on the platform; skipped"
                return@forEach
            }
            val scope = if (connector.platform) "platform" else "project"
            when (connector.kind) {
                ConnectorKind.MCP_HTTP -> {
                    if (connector.auth != ConnectorAuth.NONE && connector.secret == null) {
                        notes += "connector $key ($scope) uses auth ${connector.auth} but has no secret; skipped"
                        return@forEach
                    }
                    connectors += RunnerConnector(
                        key, ConnectorKind.MCP_HTTP, url = connector.url, auth = connector.auth,
                        headerName = connector.headerName.takeIf { connector.auth == ConnectorAuth.HEADER },
                    )
                    if (connector.auth != ConnectorAuth.NONE) credentials += JobCredentialRef(connector.id.toString(), "", JobCredentialRef.CONNECTOR, key)
                    notes += "connector $key ($scope): MCP server ${connector.host ?: connector.url}" +
                        (if (connector.auth != ConnectorAuth.NONE) ", secret delivered sealed to the runner" else "") +
                        "; the sandbox egress policy decides whether it is reachable"
                }
                ConnectorKind.GITLAB_READ -> {
                    val repositoryKey = connector.repository ?: repositories.firstOrNull { it.primary }?.key
                    if (repositoryKey == null || repositories.none { it.key == repositoryKey }) {
                        notes += "connector $key ($scope): repository ${repositoryKey ?: "(primary)"} is not a source of this step; skipped"
                        return@forEach
                    }
                    val token = ScmConnectionSelector.select(scmConnections, repositoryKey, setOf(ScmCapability.CI_READ), readOnly = true)
                        ?: return Result(
                            JobConnectors(connectors, credentials, notes),
                            "connector $key needs a read-only token with CI_READ for repository '$repositoryKey': add one under the project's " +
                                "Sources tab, Repository access tokens (role Reporter, scopes read_api and read_repository), and test it",
                        )
                    connectors += RunnerConnector(key, ConnectorKind.GITLAB_READ, repository = repositoryKey)
                    val ref = JobCredentialRef(token.id.toString(), repositoryKey, JobCredentialRef.CI_READ)
                    if (ref !in credentials) credentials += ref
                    notes += "connector $key ($scope): glab for repository $repositoryKey with the read-only project token \"${token.name}\" (CI_READ), delivered sealed to the runner"
                }
            }
        }
        return Result(JobConnectors(connectors, credentials, notes))
    }
}
