package pl.mos.backoffice.app.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException

/**
 * Maps domain exceptions to RFC 9457 problem details for every controller in every adapter module.
 * `InsufficientPermissionException` (403 with `permission`/`projectId` + ACCESS_DENIED audit entry) and
 * authentication failures (401) are mapped in exactly one place: identity-adapters'
 * `AuthorizationExceptionHandler`, which runs at the highest precedence.
 */
@RestControllerAdvice
class ProblemDetailsHandler {

    private val log = LoggerFactory.getLogger(ProblemDetailsHandler::class.java)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Not found", e.message)

    @ExceptionHandler(InvalidStateException::class)
    fun invalidState(e: InvalidStateException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Invalid state", e.message)

    @ExceptionHandler(ValidationException::class)
    fun validation(e: ValidationException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Validation failed", e.message).apply {
            setProperty("errors", e.errors.map { mapOf("field" to it.field, "message" to it.message) })
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidation(e: MethodArgumentNotValidException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid").apply {
            setProperty(
                "errors",
                e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) },
            )
        }

    @ExceptionHandler(IntegrationException::class)
    fun integration(e: IntegrationException): ProblemDetail {
        log.warn("integration failure system={} retryable={} message={}", e.system, e.retryable, e.message)
        return problem(HttpStatus.BAD_GATEWAY, "Integration failure", e.message).apply {
            setProperty("system", e.system)
            setProperty("retryable", e.retryable)
        }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(e: IllegalArgumentException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Bad request", e.message)

    private fun problem(status: HttpStatus, title: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: title).apply { this.title = title }
}
