package pl.mos.backoffice.projects.adapter.out.secrets

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.RewrapConnectionSecrets
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository

/**
 * Startup checks of the connection secrets (ADR 0030), in the api and worker roles:
 *
 * - connections exist but `BACKOFFICE_SECRETS_KEY` is missing, or a stored secret names a key id that is configured
 *   neither as the active nor as a previous key -> startup fails with a message saying what to set (unless the
 *   recovery switch `BACKOFFICE_SECRETS_ALLOW_UNREADABLE` is on, then it only warns);
 * - worker role: secrets wrapped with a previous key are re-wrapped to the active key (audited per connection).
 */
@Component
class ConnectionSecretsStartup(
    private val cipher: AesGcmSecretCipher,
    private val properties: SecretsProperties,
    private val connections: ConnectionRepository,
    private val rewrap: RewrapConnectionSecrets,
    private val environment: Environment,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(ConnectionSecretsStartup::class.java)

    override fun run(args: ApplicationArguments) {
        val api = environment.getProperty(API_ENABLED, Boolean::class.java, true)
        val worker = environment.getProperty(WORKER_ENABLED, Boolean::class.java, true)
        if (!api && !worker) return
        check()
        if (worker && cipher.configured) {
            val count = rewrap.rewrapSecrets()
            if (count > 0) log.info("re-wrapped {} connection secrets to key {}", count, cipher.activeKeyId)
        }
    }

    /** Fails (or warns with the recovery switch) when stored secrets cannot be opened with the configured keys. */
    fun check() {
        val stored = connections.findAll()
        if (stored.isEmpty()) return
        val problem = when {
            !cipher.configured ->
                "${stored.size} connections with encrypted secrets exist but BACKOFFICE_SECRETS_KEY is not set"
            else -> {
                val unknown = stored.mapNotNull { it.secret?.keyId }.filterNot(cipher::knows).toSortedSet()
                if (unknown.isEmpty()) null
                else "connection secrets are sealed with key ids $unknown, which are configured neither in BACKOFFICE_SECRETS_KEY (id ${cipher.activeKeyId}) " +
                    "nor in BACKOFFICE_SECRETS_PREVIOUS_KEYS"
            }
        } ?: return
        val advice = "$problem. Set the key the secrets were sealed with (docs/guides/configuration.md, \"Connection secrets\"). " +
            "If the key is lost, start once with BACKOFFICE_SECRETS_ALLOW_UNREADABLE=true and a new key, replace every connection token, then remove the switch."
        if (properties.allowUnreadable) {
            log.warn("{} (starting anyway: BACKOFFICE_SECRETS_ALLOW_UNREADABLE=true)", problem)
        } else {
            throw IllegalStateException(advice)
        }
    }

    companion object {
        const val API_ENABLED = "backoffice.platform.api-enabled"
        const val WORKER_ENABLED = "backoffice.platform.worker-enabled"
    }
}
