package pl.mos.backoffice.projects.adapter.out.settings

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import pl.mos.backoffice.projects.adapter.config.IntegrationsProperties
import pl.mos.backoffice.projects.adapter.config.RunnerSettings
import pl.mos.backoffice.projects.application.port.out.IntegrationDescriptor
import pl.mos.backoffice.projects.application.port.out.IntegrationProbe
import pl.mos.backoffice.projects.application.port.out.IntegrationTestOutcome
import pl.mos.backoffice.shared.runner.AgentRunner
import java.time.Duration

/** `kubernetes` probe: runner mode/image/namespace from properties; test = `AgentRunner.isAvailable()`. */
@Component
class RunnerProbe(private val runner: AgentRunner, private val settings: RunnerSettings) : IntegrationProbe {
    override val name = "kubernetes"

    override fun describe() = IntegrationDescriptor(
        name = name,
        configured = !settings.mode.equals("DISABLED", ignoreCase = true),
        url = null,
        extra = mapOf(
            "mode" to settings.mode.uppercase(),
            "image" to settings.image.takeIf { it.isNotBlank() },
            "namespace" to settings.kubernetes.namespace.takeIf { it.isNotBlank() },
        ),
    )

    override fun test(): IntegrationTestOutcome {
        val started = System.nanoTime()
        val available = runner.isAvailable()
        val latency = (System.nanoTime() - started) / 1_000_000
        return if (available) {
            IntegrationTestOutcome(true, "runner backend ${settings.mode.uppercase()} reachable", latency)
        } else {
            IntegrationTestOutcome(false, "runner backend ${settings.mode.uppercase()} not available", latency)
        }
    }
}

/** `langfuse` probe: enabled flag and URL from properties; test = HTTP GET of the base URL. */
@Component
class LangfuseProbe(private val props: IntegrationsProperties, restClientBuilder: RestClient.Builder) : IntegrationProbe {
    override val name = "langfuse"
    private val client = restClientBuilder.clone().build()

    override fun describe() = IntegrationDescriptor(name, props.langfuse.enabled, props.langfuse.url.takeIf { it.isNotBlank() })

    override fun test(): IntegrationTestOutcome {
        if (!props.langfuse.enabled || props.langfuse.url.isBlank()) return IntegrationTestOutcome(false, "Langfuse is not enabled (LANGFUSE_ENABLED / LANGFUSE_URL)")
        val started = System.nanoTime()
        return try {
            val status = client.get().uri(props.langfuse.url.trimEnd('/') + "/api/public/health").exchange { _, response -> response.statusCode.value() }
            val latency = (System.nanoTime() - started) / 1_000_000
            if (status in 200..399) IntegrationTestOutcome(true, "Langfuse reachable (HTTP $status)", latency)
            else IntegrationTestOutcome(false, "Langfuse answered HTTP $status", latency)
        } catch (e: RuntimeException) {
            IntegrationTestOutcome(false, "Langfuse unreachable: ${e.message}", (System.nanoTime() - started) / 1_000_000)
        }
    }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
