package pl.mos.backoffice.runnercontroller.engine

import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeClient
import pl.mos.backoffice.runnercontroller.protocol.RunnerCapabilities
import pl.mos.backoffice.runnercontroller.protocol.RunnerCredentialStore
import pl.mos.backoffice.runnercontroller.protocol.RunnerCredentials
import pl.mos.backoffice.runnercontroller.protocol.RunnerRegistration

/** Holds the runner token for the HTTP client. */
class RunnerTokenHolder {
    @Volatile var credentials: RunnerCredentials? = null
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
            holder.credentials = it
            log.info("using stored runner credentials (runner {}, pool {})", it.runnerId, it.poolId)
            return it
        }
        require(registrationToken.isNotBlank()) {
            "no stored runner token and no registration token (runner-controller.back-office.registration-token / RUNNER_REGISTRATION_TOKEN)"
        }
        val registered = client.register(RunnerRegistration(registrationToken, name, version, cluster, capabilities))
        require(registered.runnerToken.isNotBlank()) { "registration returned no runner token" }
        val credentials = RunnerCredentials(registered.runnerId, registered.runnerToken, registered.poolId)
        store.save(credentials)
        holder.credentials = credentials
        log.info("registered as runner {} in pool {}", registered.runnerId, registered.poolId)
        return credentials
    }
}
