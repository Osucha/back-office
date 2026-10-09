package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.IntegrationsStatus
import pl.mos.backoffice.projects.application.port.out.IntegrationProbe
import pl.mos.backoffice.projects.application.port.out.IntegrationTestOutcome
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.AuthorizationGate

/**
 * Implements [IntegrationsStatus] from the registered [IntegrationProbe]s (`jira`, `kubernetes`,
 * `langfuse`), the CI script location and the gateway's provider configuration. Never exposes secrets.
 * Both use cases need the platform permission `integrations.view`.
 */
class IntegrationsService(
    probes: List<IntegrationProbe>,
    private val llmGateway: LlmGateway,
    private val publicUrl: String,
    private val appVersion: String,
    private val gate: AuthorizationGate,
) : IntegrationsStatus {

    private val probesByName = probes.associateBy { it.name.lowercase() }

    override fun status(): IntegrationsStatus.Status {
        gate.require(ProjectsPermissions.INTEGRATIONS_VIEW)
        val jira = describe("jira")
        val runner = describe("kubernetes")
        val langfuse = describe("langfuse")
        return IntegrationsStatus.Status(
            jira = IntegrationsStatus.Endpoint(
                configured = jira?.configured ?: false,
                url = jira?.url,
                legacy = jira?.extra?.get("legacy") == "true",
                platformConnections = jira?.extra?.get("platformConnections")?.toIntOrNull() ?: 0,
            ),
            runner = IntegrationsStatus.Runner(
                mode = runner?.extra?.get("mode") ?: "DISABLED",
                image = runner?.extra?.get("image"),
                namespace = runner?.extra?.get("namespace"),
            ),
            langfuse = IntegrationsStatus.Langfuse(langfuse?.configured ?: false, langfuse?.url),
            ci = IntegrationsStatus.Ci(scriptUrl = publicUrl.trimEnd('/') + "/ci/backoffice-ci.sh", version = appVersion),
            providers = Provider.entries.map { provider ->
                IntegrationsStatus.ProviderStatus(provider = provider, configured = runCatching { llmGateway.isConfigured(provider) }.getOrDefault(false))
            },
        )
    }

    override fun test(name: String): IntegrationTestOutcome {
        gate.require(ProjectsPermissions.INTEGRATIONS_VIEW)
        val probe = probesByName[name.lowercase()] ?: throw NotFoundException("Integration", name)
        return try {
            probe.test()
        } catch (e: RuntimeException) {
            IntegrationTestOutcome(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun describe(name: String) = probesByName[name]?.let { runCatching { it.describe() }.getOrNull() }
}
