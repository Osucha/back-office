package pl.mos.backoffice.app.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `backoffice.security.*`. Authentication lives in the identity context (`backoffice.auth.*`, ADR 0016); the
 * former single-owner API keys (`api-key`, `ci-api-key`, ADR 0009) are gone.
 */
@ConfigurationProperties(prefix = "backoffice.security")
data class SecurityProperties(
    /** Secret for HMAC runner callback tokens - validated inside the agent-runner module. */
    val runnerTokenSecret: String = "",
    /** Only needed when the UI is served from another origin than the API (the BFF is same-origin by default). */
    val corsAllowedOrigins: List<String> = listOf("http://localhost:4200"),
)
