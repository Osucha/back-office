package pl.mos.backoffice.projects.adapter

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException

/** Test copy of the `app` module problem-details mapping so REST slice tests see the production status codes. */
@RestControllerAdvice
class TestProblemDetailsHandler {
    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(InvalidStateException::class)
    fun invalidState(e: InvalidStateException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(ValidationException::class, MethodArgumentNotValidException::class, IllegalArgumentException::class)
    fun validation(e: Exception): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(InsufficientPermissionException::class)
    fun forbidden(e: InsufficientPermissionException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(IntegrationException::class)
    fun integration(e: IntegrationException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message)
}
