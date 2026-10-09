package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.application.port.out.IntegrationTestOutcome
import pl.mos.backoffice.shared.llm.Provider

/** Use case: report which integrations are configured (never secrets) and test one of them. */
interface IntegrationsStatus {
    fun status(): Status
    fun test(name: String): IntegrationTestOutcome

    data class Status(
        val jira: Endpoint,
        val runner: Runner,
        val langfuse: Langfuse,
        val ci: Ci,
        val providers: List<ProviderStatus>,
    )

    /** `legacy`: the JIRA_* environment credential is set; `platformConnections`: stored platform connections (ADR 0030). */
    data class Endpoint(val configured: Boolean, val url: String?, val legacy: Boolean = false, val platformConnections: Int = 0)
    data class Runner(val mode: String, val image: String?, val namespace: String?)
    data class Langfuse(val enabled: Boolean, val url: String?)
    data class Ci(val scriptUrl: String, val version: String)
    data class ProviderStatus(val provider: Provider, val configured: Boolean, val baseUrl: String? = null)
}
