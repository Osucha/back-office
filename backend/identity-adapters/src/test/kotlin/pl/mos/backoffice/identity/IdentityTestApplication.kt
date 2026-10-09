package pl.mos.backoffice.identity

import jakarta.servlet.http.Cookie
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.testcontainers.containers.PostgreSQLContainer
import pl.mos.backoffice.identity.adapter.`in`.web.SessionAttributes
import pl.mos.backoffice.identity.application.port.out.GrantRepository
import pl.mos.backoffice.identity.application.port.out.PersonalTokenRepository
import pl.mos.backoffice.identity.application.port.out.UserRepository
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.identity.domain.service.TokenCodec
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.CiTokenVerifier
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Boots the identity context alone with stand-ins for what the app and other contexts provide: `Clock`,
 * the domain problem-details mapping, the other modules' permission contributions and a CI token verifier.
 */
@SpringBootApplication(scanBasePackages = ["pl.mos.backoffice.identity"])
class IdentityTestApplication {

    @Bean
    fun clock(): Clock = Clock.SYSTEM

    /** Keys other modules contribute in the real application (so built-in roles validate). */
    @Bean
    fun otherModulesPermissions() = PermissionCatalogContribution {
        listOf("projects.create", "integrations.view", "library.view", "library.manage", "models.view", "models.manage", "evaluation.view", "evaluation.run", "usage.view")
            .map { PermissionDefinition(it, PermissionScope.PLATFORM, "test", it) } +
            listOf("project.view", "project.manage", "project.ci.manage", "workflow.edit", "run.start", "run.cancel", "run.approve", "run.assign", "run.signal", "library.project.manage", "project.usage.view", "ci.execute", "ci.signal")
                .map { PermissionDefinition(it, PermissionScope.PROJECT, "test", it) }
    }

    @Bean
    fun testCiTokenVerifier(): CiTokenVerifier = CiTokenVerifier { token ->
        if (token == TestIdentity.CI_TOKEN) CiTokenIdentity(UUID.randomUUID(), TestIdentity.CI_PROJECT, "CIPRJ", "gitlab") else null
    }
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
}

/** Creates users, real Spring Session sessions (cookie `BO_SESSION`) and personal tokens for tests. */
@Component
class TestIdentity(
    private val users: UserRepository,
    private val grants: GrantRepository,
    private val tokens: PersonalTokenRepository,
    private val sessions: FindByIndexNameSessionRepository<out Session>,
) {
    fun user(email: String = "u-${UUID.randomUUID()}@example.com", vararg platformRoles: String, status: UserStatus = UserStatus.ACTIVE): User {
        val user = users.save(
            User(UserId.new(), "https://idp.test", "sub-$email", email, email.substringBefore('@'), status, createdAt = Instant.now(), createdBy = Actor.SYSTEM),
        )
        if (platformRoles.isNotEmpty()) grants.save(grants.find(GrantLevel.USER, user.id.value).copy(roles = platformRoles.toSet()))
        return user
    }

    /** A logged-in session; returns the `BO_SESSION` cookie and the raw session id. */
    fun login(user: User, authTime: Instant = Instant.now()): Pair<Cookie, String> {
        @Suppress("UNCHECKED_CAST")
        val repository = sessions as FindByIndexNameSessionRepository<Session>
        val session = repository.createSession()
        session.setAttribute(SessionAttributes.USER_ID, user.id.toString())
        session.setAttribute(SessionAttributes.AUTH_TIME, authTime.toEpochMilli())
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, user.id.toString())
        repository.save(session)
        return Cookie("BO_SESSION", Base64.getEncoder().encodeToString(session.id.toByteArray())) to session.id
    }

    fun pat(user: User, permissions: Set<String> = emptySet(), expiresIn: Duration = Duration.ofDays(1)): String {
        val generated = TokenCodec.generate()
        tokens.save(PersonalAccessToken(TokenId.new(), user.id, "test", generated.hash, generated.prefix, permissions, Instant.now().plus(expiresIn), Instant.now()))
        return generated.plaintext
    }

    fun sessionExists(id: String): Boolean = sessions.findById(id) != null

    companion object {
        const val CI_TOKEN = "bo_ci_test-token-0123456789abcdefghijklmnopqrstuv"
        val CI_PROJECT: UUID = UUID.fromString("00000000-0000-0000-0000-0000000c1c1c")

        /** Any matching cookie/header pair passes the SPA CSRF check. */
        val XSRF = Cookie("XSRF-TOKEN", "test-xsrf-token")
        const val XSRF_HEADER = "X-XSRF-TOKEN"
    }
}
