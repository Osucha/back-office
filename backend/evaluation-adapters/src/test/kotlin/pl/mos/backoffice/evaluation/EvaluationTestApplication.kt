package pl.mos.backoffice.evaluation

import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.testcontainers.containers.PostgreSQLContainer
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal boot for evaluation-adapters integration tests: this context only, with stand-ins for the
 * beans other modules provide (`LlmGateway`, `AgentRunner`, `Clock`, `EventPublisher`) and the same
 * problem-details mapping the `app` module applies.
 */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.evaluation"])
@EnableJpaRepositories(basePackages = ["pl.mos.backoffice.evaluation"])
@EntityScan(basePackages = ["pl.mos.backoffice.evaluation"])
class EvaluationTestApplication {

    @Bean
    fun clock(): Clock = Clock.SYSTEM

    @Bean
    fun recordedEvents(): CopyOnWriteArrayList<DomainEvent> = CopyOnWriteArrayList()

    @Bean
    fun eventPublisher(recorded: CopyOnWriteArrayList<DomainEvent>): EventPublisher = EventPublisher { recorded += it }

    @Bean
    fun llmGateway(): LlmGateway = mockk<LlmGateway>().also { gateway ->
        every { gateway.isConfigured(any()) } answers { firstArg<Provider>() == Provider.ANTHROPIC }
    }

    @Bean
    fun agentRunner(): AgentRunner = mockk<AgentRunner>(relaxed = true).also {
        every { it.isAvailable() } returns true
    }

    /** Holds every evaluation permission by default; tests switch `user` to check denials. */
    @Bean
    fun authorizationGate(): FakeAuthorizationGate = FakeAuthorizationGate.permitting(
        EvaluationPermissions.MODELS_VIEW, EvaluationPermissions.MODELS_MANAGE, EvaluationPermissions.EVALUATION_VIEW, EvaluationPermissions.EVALUATION_RUN,
    )

    @Bean
    fun auditPort(): RecordingAuditPort = RecordingAuditPort()
}

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestSupport {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
}

@RestControllerAdvice
class TestProblemDetailsHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(InvalidStateException::class)
    fun invalidState(e: InvalidStateException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(ValidationException::class)
    fun validation(e: ValidationException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(InsufficientPermissionException::class)
    fun forbidden(e: InsufficientPermissionException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(IntegrationException::class)
    fun integration(e: IntegrationException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message)
}
