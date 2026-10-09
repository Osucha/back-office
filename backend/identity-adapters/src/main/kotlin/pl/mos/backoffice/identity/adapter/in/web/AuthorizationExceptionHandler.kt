package pl.mos.backoffice.identity.adapter.`in`.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.AuthenticationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.mos.backoffice.identity.domain.model.IdentityAudit
import pl.mos.backoffice.identity.domain.model.InvalidCredentialsException
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditResult

/**
 * The single place that maps authorisation failures for every controller of every context:
 * - `InsufficientPermissionException` (thrown by `AuthorizationGate.require`) -> 403 problem detail with
 *   `permission` and `projectId`, plus an `ACCESS_DENIED` audit entry written in its own transaction
 *   (the business transaction has already rolled back);
 * - an authentication exception thrown by the gate (no principal) -> 401 problem detail.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthorizationExceptionHandler(private val audit: AuditPort, transactionManager: PlatformTransactionManager) {

    private val log = LoggerFactory.getLogger(AuthorizationExceptionHandler::class.java)
    private val ownTransaction = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    @ExceptionHandler(InsufficientPermissionException::class)
    fun forbidden(e: InsufficientPermissionException, request: HttpServletRequest): ProblemDetail {
        try {
            ownTransaction.executeWithoutResult {
                audit.record(
                    AuditEntry(
                        operation = IdentityAudit.ACCESS_DENIED,
                        entityType = "PERMISSION",
                        entityId = e.permission,
                        result = AuditResult.DENIED,
                        projectId = e.projectId,
                        details = mapOf("permission" to e.permission, "resource" to "${request.method} ${request.requestURI}"),
                    ),
                )
            }
        } catch (failure: Exception) {
            log.error("ACCESS_DENIED not audited permission={} resource={} {}", e.permission, request.requestURI, failure.toString())
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.message ?: "Forbidden").apply {
            title = "Forbidden"
            setProperty("permission", e.permission)
            e.projectId?.let { setProperty("projectId", it.toString()) }
        }
    }

    /** Wrong current password or MFA code in a self-service call (ADR 0021). The reason is never exposed. */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun invalidCredentials(e: InvalidCredentialsException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "The credentials are not valid").apply {
            title = "Unauthorized"
            setProperty("code", "invalid-credentials")
        }

    @ExceptionHandler(AuthenticationException::class)
    fun unauthorized(e: AuthenticationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required: log in or send a valid bearer token").apply {
            title = "Unauthorized"
            setProperty("code", "unauthenticated")
        }
}
