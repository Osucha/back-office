package pl.mos.backoffice.runner

import org.springframework.boot.context.properties.ConfigurationProperties
import pl.mos.backoffice.shared.runner.SandboxEnvironment
import pl.mos.backoffice.shared.runner.SandboxIsolation
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.runner.SandboxResources
import pl.mos.backoffice.shared.runner.SandboxTools
import java.time.Duration

/** `backoffice.runner.*`: the agent execution control plane (ADR 0024, docs/guides/runner-fleet.md). */
@ConfigurationProperties(prefix = "backoffice.runner")
data class RunnerProperties(
    /**
     * Base URL (without `/api/v1`) under which runner-controllers and sandboxes reach this API; the LLM gateway
     * URLs in assignments and its host (always allowed egress) derive from it. Blank = `backoffice.public-url`.
     */
    val apiUrl: String = "",
    /** A lease lives this long without a heartbeat (heartbeats every 15 s extend it). The job token expires with it. */
    val leaseDuration: Duration = Duration.ofSeconds(60),
    /** A runner is online when it called the API within this window. */
    val onlineWindow: Duration = Duration.ofSeconds(90),
    /** Upper bound of the lease long poll (`waitSeconds`). */
    val maxLeaseWait: Duration = Duration.ofSeconds(30),
    /** How often a waiting long poll re-checks the queue (other replicas' submissions). */
    val leasePollInterval: Duration = Duration.ofSeconds(1),
    /** A job no runner leased within this time ends TIMED_OUT (`queue-timeout`). */
    val queueTimeout: Duration = Duration.ofHours(24),
    /** Leases that expire before the first heartbeat are handed out again this many times, then the job fails. */
    val maxUnacknowledgedLeases: Int = 5,
    val registrationTokenValidity: Duration = Duration.ofHours(24),
    val uploadUrlTtl: Duration = Duration.ofMinutes(15),
    val maxUploadBytes: Long = 1L shl 30,
    /** Results larger than this (serialised) are written to the BlobStore when one is configured. */
    val inlineResultMaxBytes: Int = 64 * 1024,
    /** `provider/model` patterns every job may call in addition to its own model, e.g. `anthropic/claude-haiku-*`. */
    val extraAllowedModels: List<String> = emptyList(),
    /** Cap on metered LLM proxy calls per agent job (0 = unlimited); stops runaway or hijacked agents (ADR 0029). */
    val maxLlmRequestsPerJob: Long = 2000,
    val leaseScan: LeaseScan = LeaseScan(),
    /** The top layer of every sandbox policy (the platform ceiling). */
    val platformPolicy: PlatformPolicy = PlatformPolicy(),
    /** Personal runners (ADR 0040): images of the generated `docker run` command and limits of a new personal pool. */
    val personal: Personal = Personal(),
) {
    data class Personal(
        val controllerImage: String = "back-office/runner-controller:dev",
        val runnerImage: String = "back-office/runner:dev",
        val egressProxyImage: String = "back-office/egress-proxy:dev",
        /** `allowedImages` of a new personal pool (`*` = any sequence). */
        val allowedImages: List<String> = listOf("back-office/runner:*", "*/back-office/runner:*"),
        val maxConcurrentJobs: Int = 2,
    )

    data class LeaseScan(val enabled: Boolean = true, val interval: Duration = Duration.ofSeconds(10))

    data class PlatformPolicy(
        val isolation: SandboxIsolation = SandboxIsolation.STANDARD,
        val cpu: String = "2",
        val memory: String = "4Gi",
        val ephemeralStorage: String = "10Gi",
        val pids: Int = 2048,
        val timeoutSeconds: Int = 3600,
        val networkAllow: List<String> = listOf(
            "registry.npmjs.org", "repo.maven.apache.org", "repo1.maven.org", "plugins.gradle.org", "services.gradle.org",
            "downloads.gradle.org", "*.gradle.org", "pypi.org", "files.pythonhosted.org", "proxy.golang.org", "crates.io", "static.crates.io",
        ),
        val toolsAllow: List<String> = emptyList(),
        val toolsDeny: List<String> = listOf("curl *", "wget *", "ssh *", "scp *", "docker *", "sudo *"),
        val toolsAsk: List<String> = listOf("rm -rf *"),
        val image: String? = null,
        val maxCostUsd: java.math.BigDecimal? = null,
        val maxTokens: Long? = null,
    ) {
        fun toPolicy() = SandboxPolicy(
            isolation = isolation,
            resources = SandboxResources(cpu, memory, ephemeralStorage, pids),
            timeoutSeconds = timeoutSeconds,
            networkAllow = networkAllow,
            tools = SandboxTools(toolsAllow, toolsDeny, toolsAsk),
            environment = SandboxEnvironment(image = image?.takeIf { it.isNotBlank() }),
            maxCostUsd = maxCostUsd,
            maxTokens = maxTokens,
        )
    }
}
