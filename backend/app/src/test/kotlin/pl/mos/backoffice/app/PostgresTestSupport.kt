package pl.mos.backoffice.app

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

/** Shared Testcontainers PostgreSQL for integration tests in the app module. */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestSupport {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("backoffice")
        .withUsername("backoffice")
        .withPassword("backoffice")
}
