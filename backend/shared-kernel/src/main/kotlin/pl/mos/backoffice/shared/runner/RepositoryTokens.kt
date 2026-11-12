package pl.mos.backoffice.shared.runner

import java.util.UUID

/**
 * ADR 0051: tokens of project repositories, across contexts. The projects context resolves which token a job gets
 * ([JobCredentialRef] in the signed spec); the runner context releases it sealed to the runner holding the lease and
 * needs the plaintext only at that moment.
 */
fun interface RepositoryTokenSource {
    /** The token named by [ref], or null when it no longer exists or cannot be opened. */
    fun open(ref: String): String?

    /**
     * ADR 0064: the secret of the connector [ref] (its id) with key [connector] (capability `CONNECTOR`), or null when the
     * connector no longer exists, has another key, has no secret or the secret cannot be opened.
     */
    fun openConnector(ref: String, connector: String): String? = null
}

/**
 * ADR 0064: the connectors of an agent as a job carries them: [connectors] for `RunnerJobSpec.connectors`, [credentials]
 * the secrets to name in `RunnerJobSpec.credentials`, [notes] what was skipped and why (step log lines).
 */
data class JobConnectors(
    val connectors: List<RunnerConnector> = emptyList(),
    val credentials: List<JobCredentialRef> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    companion object {
        val NONE = JobConnectors()
    }
}

/**
 * ADR 0064: connectors across contexts (implemented by the projects context, which owns them). Benchmarks give a
 * candidate agent its platform connectors ([projectId] null); a project's own connectors shadow the platform ones.
 * Only `MCP_HTTP` connectors resolve here: `GITLAB_READ` needs a project's repositories and is a workflow step's matter.
 */
fun interface ConnectorCatalog {
    fun mcpConnectors(keys: List<String>, projectId: UUID?): JobConnectors
}
