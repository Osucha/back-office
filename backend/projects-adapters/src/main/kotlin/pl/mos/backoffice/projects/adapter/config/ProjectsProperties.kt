package pl.mos.backoffice.projects.adapter.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `backoffice.integrations.*` (Jira, Langfuse) as defined in app/src/main/resources/application.yml. */
@ConfigurationProperties(prefix = "backoffice.integrations")
data class IntegrationsProperties(
    val jira: Jira = Jira(),
    val langfuse: Langfuse = Langfuse(),
) {
    data class Jira(
        val url: String = "",
        val email: String = "",
        val apiToken: String = "",
        val acceptanceCriteriaField: String = "",
        /** ADR 0035: the Atlassian API gateway scoped tokens are sent to (`<gateway>/ex/jira/<cloudId>`); overridable for tests. */
        val apiGatewayUrl: String = "https://api.atlassian.com",
    ) {
        val configured: Boolean get() = url.isNotBlank() && email.isNotBlank() && apiToken.isNotBlank()
    }

    data class Langfuse(val enabled: Boolean = false, val url: String = "")
}

/** `backoffice.pipelines.*`: engine concurrency and recovery. */
@ConfigurationProperties(prefix = "backoffice.pipelines")
data class PipelinesProperties(
    val executorThreads: Int = 16,
    val recoverOnStartup: Boolean = true,
)

/** `backoffice.public-url`: base URL the CI script and snippets point at. */
@ConfigurationProperties(prefix = "backoffice")
data class PublicUrlProperties(val publicUrl: String = "http://localhost:8080")

/** Read-only view of `backoffice.runner.*` for the settings screen (the runner module owns the full binding). */
@ConfigurationProperties(prefix = "backoffice.runner")
data class RunnerSettings(
    val mode: String = "DISABLED",
    val image: String = "",
    val kubernetes: Kubernetes = Kubernetes(),
    val defaultTimeout: java.time.Duration = java.time.Duration.ofMinutes(60),
) {
    data class Kubernetes(val namespace: String = "")
}
