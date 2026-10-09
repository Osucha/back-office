package pl.mos.backoffice.platform.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Registered in `META-INF/spring.factories`. Turns `backoffice.role` (default `api,worker`) into the flags the
 * modules condition their beans on, with lowest precedence so an explicit property still wins:
 *
 * - `backoffice.platform.api-enabled`: REST/SSE controllers answer (a worker-only replica keeps its web server
 *   for `/actuator/...` probes and metrics only);
 * - `backoffice.platform.worker-enabled`: the work-queue consumer, schedulers, sweeps and recovery scans run.
 */
class PlatformRoleEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val roles = PlatformRoles.parse(environment.getProperty(PlatformRoles.PROPERTY))
        environment.propertySources.addLast(
            MapPropertySource(
                SOURCE_NAME,
                mapOf(
                    API_ENABLED to roles.api.toString(),
                    WORKER_ENABLED to roles.worker.toString(),
                ),
            ),
        )
    }

    companion object {
        const val SOURCE_NAME = "backoffice-platform-roles"
        const val API_ENABLED = "backoffice.platform.api-enabled"
        const val WORKER_ENABLED = "backoffice.platform.worker-enabled"
    }
}
