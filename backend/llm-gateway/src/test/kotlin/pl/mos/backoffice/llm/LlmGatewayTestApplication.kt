package pl.mos.backoffice.llm

import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.testcontainers.containers.PostgreSQLContainer
import pl.mos.backoffice.llm.usage.LlmPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.llm.ModelCatalog

/** Minimal boot for llm-gateway integration tests: this module plus stand-ins for beans the `app` module provides. */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.llm"])
@EnableJpaRepositories(basePackages = ["pl.mos.backoffice.llm"])
@EntityScan(basePackages = ["pl.mos.backoffice.llm"])
class LlmGatewayTestApplication {

    @Bean
    fun clock(): Clock = Clock.SYSTEM

    @Bean
    fun authorizationGate(): FakeAuthorizationGate = FakeAuthorizationGate.permitting(LlmPermissions.USAGE_VIEW)

    @Bean
    fun modelCatalog(): ModelCatalog = mockk<ModelCatalog>().also {
        every { it.findPricing(any()) } returns null
        every { it.find(any()) } returns null
        every { it.findAllEnabled() } returns emptyList()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestSupport {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
}
