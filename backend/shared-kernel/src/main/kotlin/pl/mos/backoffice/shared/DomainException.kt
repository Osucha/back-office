package pl.mos.backoffice.shared

/**
 * Base class for errors raised by the domain and application layers. The REST layer maps subclasses to
 * RFC 9457 problem details: NotFound -> 404, InvalidState -> 409, Validation -> 400, Integration -> 502,
 * InsufficientPermission -> 403 (and an ACCESS_DENIED audit entry).
 */
sealed class DomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NotFoundException(val resource: String, val id: Any) :
    DomainException("$resource $id not found")

class InvalidStateException(message: String) : DomainException(message)

class ValidationException(val errors: List<FieldError>) :
    DomainException(errors.joinToString("; ") { (if (it.field != null) "${it.field}: " else "") + it.message }) {
    constructor(message: String, field: String? = null) : this(listOf(FieldError(field, message)))

    data class FieldError(val field: String?, val message: String)
}

/** A call to an external system (LLM provider, GitLab, Jira, Kubernetes) failed. */
class IntegrationException(val system: String, message: String, cause: Throwable? = null, val retryable: Boolean = false) :
    DomainException("$system: $message", cause)

/** The caller lacks [permission] (on [projectId] when project-scoped). Mapped to 403. */
class InsufficientPermissionException(val permission: String, val projectId: java.util.UUID? = null) :
    DomainException("missing permission $permission" + (projectId?.let { " on project $it" } ?: ""))
