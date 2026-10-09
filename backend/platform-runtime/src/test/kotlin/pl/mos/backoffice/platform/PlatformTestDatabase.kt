package pl.mos.backoffice.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/** One PostgreSQL for the platform tests, migrated with the platform's Flyway scripts. */
object PlatformTestDatabase {

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    }

    val dataSource: DataSource by lazy {
        val ds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = POOL
            },
        )
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        ds
    }

    val jdbc: JdbcClient by lazy { JdbcClient.create(dataSource) }

    val json: JsonMapper = JsonMapper.builder().build()

    /** A dedicated connection outside the pool, like the LISTEN bridge uses. */
    fun connect(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    private const val POOL = 20
}
