package pl.mos.backoffice.platform.migrate

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration

/**
 * The `migrate` role (ADR 0023): a minimal context with only the data source and Flyway, which applies every
 * module's `db/migration` scripts (same `spring.flyway.*` settings as the application, `enabled` forced on)
 * and closes. Run by the Helm pre-install/pre-upgrade Job; application pods never migrate. Deliberately not a
 * `@Configuration`, so the application's component scan never picks it up.
 */
@ImportAutoConfiguration(DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class)
class MigrateApplication {

    data class Result(val applied: Int, val current: String?)

    companion object {
        private val log = LoggerFactory.getLogger(MigrateApplication::class.java)

        /**
         * Appended as the last command-line argument: command-line properties outrank the environment and
         * application.yml (`spring.flyway.enabled=${BACKOFFICE_FLYWAY_ENABLED:false}`, `SPRING_FLYWAY_ENABLED=false`
         * from the app ConfigMap), so the migrate role always migrates. Default properties would lose to both.
         */
        private const val FORCE_FLYWAY = "--spring.flyway.enabled=true"

        /** Migrates the database configured by [args] / the environment and returns what is applied now. */
        fun migrate(args: Array<String>, vararg extraProperties: String): Result {
            val context = SpringApplicationBuilder(MigrateApplication::class.java)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", *extraProperties)
                .run(*args, FORCE_FLYWAY)
            context.use {
                val flyway = it.getBean(Flyway::class.java)
                val info = flyway.info()
                val result = Result(info.applied().size, info.current()?.version?.version)
                log.info("migrate role: database is at version {} ({} migrations applied)", result.current, result.applied)
                return result
            }
        }
    }
}
