package pl.mos.backoffice.runnercontroller.engine

import pl.mos.backoffice.runnercontroller.security.CredentialKeys
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeClient
import pl.mos.backoffice.runnercontroller.protocol.RunnerCapabilities
import pl.mos.backoffice.runnercontroller.protocol.RunnerCredentialStore
import pl.mos.backoffice.runnercontroller.protocol.RunnerCredentials
import pl.mos.backoffice.runnercontroller.protocol.RunnerRegistration

/** Holds the runner token for the HTTP client and (ADR 0051) the key pair job tokens are sealed to. */
class RunnerTokenHolder {
    @Volatile var credentials: RunnerCredentials? = null
    @Volatile var keys: CredentialKeys? = null
    fun token(): String? = credentials?.runnerToken
}

/**
 * First start: registers with the single-use registration token and persists the runner token
 * (Kubernetes Secret or file). Later starts reuse the stored token.
 */
class RunnerRegistrar(
    private val client: BackOfficeClient,
    private val store: RunnerCredentialStore,
    private val holder: RunnerTokenHolder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ensureRegistered(registrationToken: String, name: String, version: String, cluster: String?, capabilities: RunnerCapabilities): RunnerCredentials {
        store.load()?.let {
            log.info("using stored runner credentials (runner {}, pool {})", it.runnerId, it.poolId)
            return withKeys(it)
        }
        require(registrationToken.isNotBlank()) {
            "no stored runner token and no registration token (runner-controller.back-office.registration-token / RUNNER_REGISTRATION_TOKEN)"
        }
        val registered = client.register(RunnerRegistration(registrationToken, name, version, cluster, capabilities))
        require(registered.runnerToken.isNotBlank()) { "registration returned no runner token" }
        val credentials = RunnerCredentials(registered.runnerId, registered.runnerToken, registered.poolId)
        store.save(credentials)
        log.info("registered as runner {} in pool {}", registered.runnerId, registered.poolId)
        return withKeys(credentials)
    }

    /**
     * ADR 0051: keeps (or creates and stores) the key pair and registers its public key on every start, so a Back Office
     * that lost it or a new key reaches it. An older Back Office without the endpoint only gets a warning: its jobs
     * name no project tokens.
     */
    private fun withKeys(credentials: RunnerCredentials): RunnerCredentials {
        val existing = CredentialKeys.restore(credentials.privateKey, credentials.publicKey)
        val keys = existing ?: CredentialKeys.generate()
        val stored = if (existing == null) credentials.copy(privateKey = keys.exportPrivateKey(), publicKey = keys.publicKey).also(store::save) else credentials
        holder.credentials = stored
        holder.keys = keys
        runCatching { client.setKey(keys.publicKey) }
            .onSuccess { log.info("registered the public key for sealed credentials {}", keys.publicKey) }
            .onFailure { log.warn("could not register the public key for sealed credentials ({}); jobs with project tokens will fail", it.message) }
        return stored
    }
}
