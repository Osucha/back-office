package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections.ScmConnectionCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageScmConnections.ScmConnectionView
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Tokens of project repositories (ADR 0051): `/projects/{projectId}/scm-connections...`. The token is accepted on
 * create, update and test and never returned. Permissions are checked by the use cases.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/scm-connections")
class ScmConnectionController(private val connections: ManageScmConnections) {

    @GetMapping
    fun list(@PathVariable projectId: UUID): List<ScmConnectionDto> = connections.list(ProjectId(projectId)).map(::toDto)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable projectId: UUID, @RequestBody @Valid body: ScmConnectionInputDto): ScmConnectionDto =
        toDto(connections.create(ProjectId(projectId), toCommand(body)))

    @PostMapping("/test")
    fun test(@PathVariable projectId: UUID, @RequestBody @Valid body: ScmConnectionInputDto): ScmVerificationDto =
        toDto(connections.test(ProjectId(projectId), toCommand(body)))

    @PutMapping("/{connectionId}")
    fun update(@PathVariable projectId: UUID, @PathVariable connectionId: UUID, @RequestBody @Valid body: ScmConnectionInputDto): ScmConnectionDto =
        toDto(connections.update(ProjectId(projectId), ScmConnectionId(connectionId), toCommand(body)))

    @DeleteMapping("/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable projectId: UUID, @PathVariable connectionId: UUID) = connections.delete(ProjectId(projectId), ScmConnectionId(connectionId))

    @PostMapping("/{connectionId}/verify")
    fun verify(@PathVariable projectId: UUID, @PathVariable connectionId: UUID): ScmConnectionDto =
        toDto(connections.verify(ProjectId(projectId), ScmConnectionId(connectionId)))

    private fun toCommand(body: ScmConnectionInputDto) = ScmConnectionCommand(
        repositoryKey = body.repositoryKey!!,
        name = body.name!!,
        capabilities = body.capabilities!!.map { name ->
            ScmCapability.entries.firstOrNull { it.name == name } ?: throw ValidationException("unknown capability $name", "capabilities")
        }.toSet(),
        token = body.token,
        secretExpiresAt = body.secretExpiresAt,
        provider = body.provider?.let { p -> ScmProvider.entries.firstOrNull { it.name == p } ?: throw ValidationException("unknown provider $p", "provider") }
            ?: ScmProvider.GITLAB,
    )

    private fun toDto(view: ScmConnectionView): ScmConnectionDto {
        val c = view.connection
        return ScmConnectionDto(
            id = c.id.value, repositoryKey = c.repositoryKey, repositoryExists = view.repositoryExists, name = c.name,
            provider = c.provider.name, capabilities = c.capabilities.sorted().map { it.name }, secretExpiresAt = c.secretExpiresAt,
            expiryStatus = c.expiryStatus().name, readOnly = c.verifiedReadOnly, verification = c.verification?.let(::toDto),
            createdAt = c.createdAt, createdBy = c.createdBy.displayName, updatedAt = c.updatedAt, updatedBy = c.updatedBy.displayName,
        )
    }

    private fun toDto(v: ScmVerification) = ScmVerificationDto(
        status = v.status.name, checkedAt = v.checkedAt, tokenName = v.tokenName, scopes = v.scopes, role = v.role,
        tokenExpiresAt = v.tokenExpiresAt, granted = v.granted.sorted().map { it.name }, missing = v.missing.sorted().map { it.name },
        writePowers = v.writePowers, messages = v.messages.map { ScmMessageDto(it.severity.name, it.code, it.text) },
    )
}

data class ScmConnectionInputDto(
    @field:NotBlank val repositoryKey: String? = null,
    @field:NotBlank @field:Size(max = 80) val name: String? = null,
    val provider: String? = null,
    @field:NotEmpty val capabilities: List<String>? = null,
    @field:Size(max = 4096) val token: String? = null,
    val secretExpiresAt: LocalDate? = null,
)

data class ScmConnectionDto(
    val id: UUID,
    val repositoryKey: String,
    val repositoryExists: Boolean,
    val name: String,
    val provider: String,
    val capabilities: List<String>,
    val secretExpiresAt: LocalDate?,
    val expiryStatus: String,
    val readOnly: Boolean,
    val verification: ScmVerificationDto?,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant,
    val updatedBy: String,
)

data class ScmVerificationDto(
    val status: String,
    val checkedAt: Instant,
    val tokenName: String?,
    val scopes: List<String>?,
    val role: String?,
    val tokenExpiresAt: LocalDate?,
    val granted: List<String>,
    val missing: List<String>,
    val writePowers: List<String>,
    val messages: List<ScmMessageDto>,
)

data class ScmMessageDto(val severity: String, val code: String, val text: String)
