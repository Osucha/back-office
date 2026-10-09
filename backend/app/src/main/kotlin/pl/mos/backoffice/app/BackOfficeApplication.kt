package pl.mos.backoffice.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import pl.mos.backoffice.platform.config.PlatformRoles
import pl.mos.backoffice.platform.migrate.MigrateApplication

/**
 * Composition root. Every adapter module lives under `pl.mos.backoffice`, so one component scan wires
 * the whole application; core modules contain no Spring beans and are instantiated by the
 * `*.adapter.config` classes of their context.
 */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice"])
@ConfigurationPropertiesScan(basePackages = ["pl.mos.backoffice"])
@EnableJpaRepositories(basePackages = ["pl.mos.backoffice"])
@EntityScan(basePackages = ["pl.mos.backoffice"])
@EnableScheduling
@EnableAsync
class BackOfficeApplication

/**
 * `backoffice.role` (ADR 0023; `--backoffice.role=`, `-Dbackoffice.role=` or `BACKOFFICE_ROLE`, default
 * `api,worker`): with `migrate` the database is migrated first in a minimal context; `migrate` alone exits
 * afterwards (the Helm pre-upgrade Job), otherwise the application starts with its other roles.
 */
fun main(args: Array<String>) {
    val roles = PlatformRoles.parse(roleOf(args))
    if (roles.migrate) {
        MigrateApplication.migrate(args)
        if (roles.migrateOnly) return
    }
    runApplication<BackOfficeApplication>(*args)
}

internal fun roleOf(args: Array<String>): String? =
    args.lastOrNull { it.startsWith("--${PlatformRoles.PROPERTY}=") }?.substringAfter('=')
        ?: System.getProperty(PlatformRoles.PROPERTY)
        ?: System.getenv(PlatformRoles.ENV)
