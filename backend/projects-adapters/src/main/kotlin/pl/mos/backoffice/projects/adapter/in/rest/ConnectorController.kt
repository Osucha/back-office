package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManageConnectors
import pl.mos.backoffice.projects.application.port.`in`.ManageConnectors.ConnectorCommand
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.ConnectorTest
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import java.time.Instant
import java.util.UUID

/**
 * ADR 0064: connectors, `/connectors...` (openapi `Connector`, `ConnectorInput`, `ConnectorTest`). The secret is accepted
 * on create and update and never returned. Permissions are checked by the use cases.
 */
@RestController
@RequestMapping("/api/v1/connectors")
class ConnectorController(private val connectors: ManageConnectors) {

    @GetMapping
    fun list(@RequestParam(required = false) projectId: UUID?): List<ConnectorDto> = connectors.list(projectId?.let(::ProjectId)).map(::toDto)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid body: ConnectorInputDto): ConnectorDto = toDto(connectors.create(toCommand(body)))

    @PutMapping("/{connectorId}")
    fun update(@PathVariable connectorId: UUID, @RequestBody @Valid body: ConnectorInputDto): ConnectorDto =
        toDto(connectors.update(ConnectorId(connectorId), toCommand(body)))

    @DeleteMapping("/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable connectorId: UUID) = connectors.delete(ConnectorId(connectorId))

    @PostMapping("/{connectorId}/test")
    fun test(@PathVariable connectorId: UUID): ConnectorTestDto = toDto(connectors.test(ConnectorId(connectorId)))

    companion object {
        fun toCommand(body: ConnectorInputDto): ConnectorCommand {
            val scope = body.scope!!
            val projectId = when (scope.type) {
                "GLOBAL" -> null
                "PROJECT" -> ProjectId(scope.projectId ?: throw ValidationException("is required for scope PROJECT", "scope.projectId"))
                else -> throw ValidationException("must be GLOBAL or PROJECT", "scope.type")
            }
            return ConnectorCommand(
                key = body.key?.takeIf { it.isNotBlank() },
                name = body.name!!,
                description = body.description.orEmpty(),
                kind = ConnectorKind.entries.firstOrNull { it.name == body.kind } ?: throw ValidationException("must be MCP_HTTP or GITLAB_READ", "kind"),
                projectId = projectId,
                url = body.url,
                auth = body.auth?.let { a -> ConnectorAuth.entries.firstOrNull { it.name == a } ?: throw ValidationException("must be NONE, BEARER or HEADER", "auth") }
                    ?: ConnectorAuth.NONE,
                headerName = body.headerName?.takeIf { it.isNotBlank() },
                secret = body.secret,
                repository = body.repository,
            )
        }

        fun toDto(c: Connector) = ConnectorDto(
            id = c.id.value, key = c.key, name = c.name, description = c.description, kind = c.kind.name,
            scope = ConnectorScopeDto(if (c.platform) "GLOBAL" else "PROJECT", c.projectId?.value),
            url = c.url, auth = c.auth.name, headerName = c.headerName, repository = c.repository,
            hasSecret = c.hasSecret, host = c.host, lastTest = c.lastTest?.let(::toDto),
            createdAt = c.createdAt, updatedAt = c.updatedAt,
            updatedBy = ActorRefDto(c.updatedBy.kind.name, c.updatedBy.userId, c.updatedBy.displayName),
        )

        fun toDto(t: ConnectorTest) = ConnectorTestDto(t.ok, t.message, t.tools, t.testedAt)
    }
}

/** openapi `Scope`. */
data class ConnectorScopeDto(@field:NotBlank val type: String = "GLOBAL", val projectId: UUID? = null)

/** openapi `ConnectorInput`; [secret] is write-only: null keeps the stored one on update, "" removes it. */
data class ConnectorInputDto(
    @field:Pattern(regexp = "^[a-z][a-z0-9-]{0,39}$") val key: String? = null,
    @field:NotBlank @field:Size(max = 200) val name: String? = null,
    @field:Size(max = 2000) val description: String? = null,
    @field:NotBlank val kind: String? = null,
    @field:NotNull @field:Valid val scope: ConnectorScopeDto? = null,
    @field:Size(max = 500) val url: String? = null,
    val auth: String? = null,
    @field:Pattern(regexp = "^([A-Za-z0-9-]{1,64})?$") val headerName: String? = null,
    @field:Size(max = 8192) val secret: String? = null,
    val repository: String? = null,
) {
    override fun toString() = "ConnectorInputDto(key=$key, name=$name, kind=$kind, scope=$scope, url=$url, auth=$auth, secret=${if (secret == null) "null" else "***"})"
}

/** openapi `Connector`. */
data class ConnectorDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val kind: String,
    val scope: ConnectorScopeDto,
    val url: String?,
    val auth: String,
    val headerName: String?,
    val repository: String?,
    val hasSecret: Boolean,
    val host: String?,
    val lastTest: ConnectorTestDto?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto?,
)

/** openapi `ConnectorTest`. */
data class ConnectorTestDto(val ok: Boolean, val message: String?, val tools: List<String>, val testedAt: Instant)
