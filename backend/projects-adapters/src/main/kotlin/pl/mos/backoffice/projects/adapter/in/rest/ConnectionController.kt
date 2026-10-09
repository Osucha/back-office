package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
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
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections.ConnectionCommand
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.JiraScopes
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.ConnectionVerification
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.service.ConnectionChoice
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Connection registry (ADR 0030): `/admin/connections...` (platform) and `/projects/{projectId}/connections...`
 * (project). The secret is accepted on create, update and rotation and never returned: [ConnectionDto] has no field
 * for it. Permissions are checked by the use cases.
 */
@RestController
@RequestMapping("/api/v1")
class ConnectionController(
    private val connections: ManageConnections,
    private val personal: ManagePersonalConnections,
    private val projectJira: ManageProjectJiraConnections,
    private val dtos: ConnectionDtos,
) {

    private fun toDto(c: Connection) = dtos.toDto(c)

    private fun toDto(choice: ConnectionChoice) = dtos.toDto(choice)

    /** Connections whose token expires within 14 days (or expired) that the caller may manage ("Your turn" card). */
    @GetMapping("/connections/expiring")
    fun expiring(): List<ConnectionDto> = connections.expiring().map { toDto(it) }

    /** ADR 0035: the Atlassian scopes a scoped token needs per capability (classic and granular options). */
    @GetMapping("/connections/jira-scopes")
    fun jiraScopes(): JiraScopesDto = JiraScopesDto(
        verification = JiraScopes.VERIFICATION.map { JiraScopeOptionDto(it.kind.name, it.required.sorted(), it.recommended.sorted()) },
        capabilities = ConnectionCapability.entries.associate { c ->
            c.name to JiraScopes.options(c).map { JiraScopeOptionDto(it.kind.name, it.required.sorted(), it.recommended.sorted()) }
        },
    )

    // ---- personal (ADR 0035)

    @GetMapping("/me/connections")
    fun listMine(): List<ConnectionDto> = personal.listMine().map { toDto(it) }

    @PostMapping("/me/connections")
    @ResponseStatus(HttpStatus.CREATED)
    fun createMine(@RequestBody @Valid body: ConnectionInputDto): ConnectionDto = toDto(personal.createMine(toCommand(body)))

    @PostMapping("/me/connections/test")
    fun testMine(@RequestBody @Valid body: ConnectionTestRequestDto): ConnectionVerificationDto =
        dtos.verificationDto(personal.testMine(toCommand(body.connection!!), body.connectionId?.let(::ConnectionId), body.projectKey))

    @GetMapping("/me/connections/{connectionId}")
    fun getMine(@PathVariable connectionId: UUID): ConnectionDto = toDto(personal.getMine(ConnectionId(connectionId)))

    @PutMapping("/me/connections/{connectionId}")
    fun updateMine(@PathVariable connectionId: UUID, @RequestBody @Valid body: ConnectionInputDto): ConnectionDto =
        toDto(personal.updateMine(ConnectionId(connectionId), toCommand(body)))

    @DeleteMapping("/me/connections/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMine(@PathVariable connectionId: UUID) = personal.deleteMine(ConnectionId(connectionId))

    @PutMapping("/me/connections/{connectionId}/secret")
    fun rotateMine(@PathVariable connectionId: UUID, @RequestBody @Valid body: ConnectionSecretInputDto): ConnectionDto =
        toDto(personal.rotateMine(ConnectionId(connectionId), body.secret!!, body.secretExpiresAt))

    @PostMapping("/me/connections/{connectionId}/verify")
    fun verifyMine(@PathVariable connectionId: UUID, @RequestBody(required = false) body: ConnectionVerifyRequestDto?): ConnectionDto =
        toDto(personal.verifyMine(ConnectionId(connectionId), body?.projectKey))

    /** Administrators: metadata of every personal connection (never usable, never with the account e-mail). */
    @GetMapping("/admin/connections/personal")
    fun listAllPersonal(): List<PersonalConnectionSummaryDto> = personal.listAllPersonal().map { dtos.summary(it) }

    // ---- the project's system connection and list (ADR 0035)

    @GetMapping("/projects/{projectId}/jira-connections")
    fun projectJiraConnections(@PathVariable projectId: UUID): ProjectJiraConnectionsDto = dtos.toDto(projectJira.jiraConnections(ProjectId(projectId)))

    @PutMapping("/projects/{projectId}/jira-connections")
    fun updateProjectJiraConnections(@PathVariable projectId: UUID, @RequestBody body: ProjectJiraConnectionsInputDto): ProjectJiraConnectionsDto =
        dtos.toDto(
            projectJira.updateJiraConnections(
                ProjectId(projectId),
                ProjectJiraConnections(body.systemConnectionId?.let(::ConnectionId), body.additionalConnectionIds.orEmpty().map(::ConnectionId)),
            ),
        )

    // ---- platform

    @GetMapping("/admin/connections")
    fun listPlatform(): List<ConnectionDto> = connections.listPlatform().map { toDto(it) }

    @PostMapping("/admin/connections")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPlatform(@RequestBody @Valid body: ConnectionInputDto): ConnectionDto = toDto(connections.create(null, toCommand(body)))

    @GetMapping("/admin/connections/{connectionId}")
    fun getPlatform(@PathVariable connectionId: UUID): ConnectionDto = toDto(connections.get(null, ConnectionId(connectionId)))

    @PutMapping("/admin/connections/{connectionId}")
    fun updatePlatform(@PathVariable connectionId: UUID, @RequestBody @Valid body: ConnectionInputDto): ConnectionDto =
        toDto(connections.update(null, ConnectionId(connectionId), toCommand(body)))

    @DeleteMapping("/admin/connections/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePlatform(@PathVariable connectionId: UUID) = connections.delete(null, ConnectionId(connectionId))

    @PutMapping("/admin/connections/{connectionId}/secret")
    fun rotatePlatform(@PathVariable connectionId: UUID, @RequestBody @Valid body: ConnectionSecretInputDto): ConnectionDto =
        toDto(connections.rotateSecret(null, ConnectionId(connectionId), body.secret!!, body.secretExpiresAt))

    /** "Test before saving": verifies a draft without storing it (the secret is sealed in memory only). */
    @PostMapping("/admin/connections/test")
    fun testPlatform(@RequestBody @Valid body: ConnectionTestRequestDto): ConnectionVerificationDto =
        dtos.verificationDto(connections.test(null, toCommand(body.connection!!), body.connectionId?.let(::ConnectionId), body.projectKey))

    @PostMapping("/admin/connections/{connectionId}/verify")
    fun verifyPlatform(@PathVariable connectionId: UUID, @RequestBody(required = false) body: ConnectionVerifyRequestDto?): ConnectionDto =
        toDto(connections.verify(null, ConnectionId(connectionId), body?.projectKey))

    // ---- project

    @GetMapping("/projects/{projectId}/connections")
    fun listProject(@PathVariable projectId: UUID): List<ConnectionDto> = connections.listProject(ProjectId(projectId)).map { toDto(it) }

    @PostMapping("/projects/{projectId}/connections")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProject(@PathVariable projectId: UUID, @RequestBody @Valid body: ConnectionInputDto): ConnectionDto =
        toDto(connections.create(ProjectId(projectId), toCommand(body)))

    @PostMapping("/projects/{projectId}/connections/test")
    fun testProject(@PathVariable projectId: UUID, @RequestBody @Valid body: ConnectionTestRequestDto): ConnectionVerificationDto =
        dtos.verificationDto(connections.test(ProjectId(projectId), toCommand(body.connection!!), body.connectionId?.let(::ConnectionId), body.projectKey))

    @GetMapping("/projects/{projectId}/connections/usable")
    fun usable(@PathVariable projectId: UUID): List<ConnectionDto> = connections.usable(ProjectId(projectId)).map { toDto(it) }

    @GetMapping("/projects/{projectId}/connections/resolve")
    fun resolve(
        @PathVariable projectId: UUID,
        @RequestParam capabilities: List<String>,
        @RequestParam(required = false) connectionId: UUID?,
        @RequestParam(required = false) jiraProjectKey: String?,
    ): ConnectionResolutionDto {
        val required = capabilities(capabilities.flatMap { it.split(',') }, "capabilities")
        return toDto(connections.resolve(ProjectId(projectId), required, connectionId?.let(::ConnectionId), jiraProjectKey))
    }

    @GetMapping("/projects/{projectId}/connections/{connectionId}")
    fun getProject(@PathVariable projectId: UUID, @PathVariable connectionId: UUID): ConnectionDto =
        toDto(connections.get(ProjectId(projectId), ConnectionId(connectionId)))

    @PutMapping("/projects/{projectId}/connections/{connectionId}")
    fun updateProject(@PathVariable projectId: UUID, @PathVariable connectionId: UUID, @RequestBody @Valid body: ConnectionInputDto): ConnectionDto =
        toDto(connections.update(ProjectId(projectId), ConnectionId(connectionId), toCommand(body)))

    @DeleteMapping("/projects/{projectId}/connections/{connectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProject(@PathVariable projectId: UUID, @PathVariable connectionId: UUID) = connections.delete(ProjectId(projectId), ConnectionId(connectionId))

    @PutMapping("/projects/{projectId}/connections/{connectionId}/secret")
    fun rotateProject(@PathVariable projectId: UUID, @PathVariable connectionId: UUID, @RequestBody @Valid body: ConnectionSecretInputDto): ConnectionDto =
        toDto(connections.rotateSecret(ProjectId(projectId), ConnectionId(connectionId), body.secret!!, body.secretExpiresAt))

    @PostMapping("/projects/{projectId}/connections/{connectionId}/verify")
    fun verifyProject(@PathVariable projectId: UUID, @PathVariable connectionId: UUID, @RequestBody(required = false) body: ConnectionVerifyRequestDto?): ConnectionDto =
        toDto(connections.verify(ProjectId(projectId), ConnectionId(connectionId), body?.projectKey))

    // ---- mapping

    private fun toCommand(body: ConnectionInputDto): ConnectionCommand {
        val type = ConnectionType.entries.firstOrNull { it.name == body.type?.trim()?.uppercase() }
            ?: throw ValidationException("must be one of ${ConnectionType.entries}", "type")
        return ConnectionCommand(
            type = type,
            name = body.name!!,
            siteUrl = body.siteUrl!!,
            accountEmail = body.accountEmail!!,
            secret = body.secret,
            capabilities = capabilities(body.capabilities.orEmpty(), "capabilities"),
            allowedProjectKeys = body.allowedProjectKeys.orEmpty(),
            secretExpiresAt = body.secretExpiresAt,
            verifyProjectKey = body.verifyProjectKey,
            tokenKind = body.tokenKind?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { k ->
                JiraTokenKind.entries.firstOrNull { it.name == k } ?: throw ValidationException("must be one of ${JiraTokenKind.entries}", "tokenKind")
            } ?: JiraTokenKind.CLASSIC,
            cloudId = body.cloudId,
            tokenScopes = body.tokenScopes.orEmpty(),
            acknowledgeClassicToken = body.acknowledgeClassicToken == true,
        )
    }

    private fun capabilities(values: List<String>, field: String): Set<ConnectionCapability> = values.map { it.trim() }.filter { it.isNotEmpty() }.map { value ->
        ConnectionCapability.entries.firstOrNull { it.name == value.uppercase() }
            ?: throw ValidationException("unknown capability $value; allowed: ${ConnectionCapability.entries.joinToString()}", field)
    }.toSet()
}

/** Maps connections, verifications and selections to DTOs (shared by the connection and the intake endpoints). */
@Component
class ConnectionDtos(private val mapper: RestMapper, private val clock: Clock) {

    fun toDto(c: Connection) = ConnectionDto(
        id = c.id.value,
        scope = c.scope.name,
        projectId = c.projectId?.value,
        type = c.type.name,
        name = c.name,
        siteUrl = c.siteUrl,
        accountEmail = c.accountEmail,
        capabilities = c.capabilities.sorted().map { it.name },
        allowedProjectKeys = c.allowedProjectKeys,
        verifyProjectKey = c.verifyProjectKey,
        secretSet = c.secret != null || c.legacy,
        secretRotatedAt = c.secretRotatedAt,
        secretExpiresAt = c.secretExpiresAt,
        expiryStatus = c.expiryStatus(clock.now()).name,
        legacy = c.legacy,
        createdAt = c.createdAt,
        createdBy = mapper.actor(c.createdBy),
        updatedAt = c.updatedAt,
        updatedBy = mapper.actor(c.updatedBy),
        verification = c.verification?.let { verificationDto(it) },
        tokenKind = c.tokenKind.name,
        cloudId = c.cloudId,
        tokenScopes = c.tokenScopes,
        classicTokenAcknowledged = c.classicTokenAcknowledged,
        ownerUserId = c.ownerUserId,
        ownerName = c.ownerName,
    )

    /** ADR 0035: what administrators see of a personal connection. */
    fun summary(c: Connection) = PersonalConnectionSummaryDto(
        id = c.id.value, name = c.name, siteUrl = c.siteUrl, capabilities = c.capabilities.sorted().map { it.name }, tokenKind = c.tokenKind.name,
        ownerUserId = c.ownerUserId, ownerName = c.ownerName, secretExpiresAt = c.secretExpiresAt, expiryStatus = c.expiryStatus(clock.now()).name,
        verificationStatus = c.verification?.status?.name, createdAt = c.createdAt, updatedAt = c.updatedAt,
    )

    fun toDto(view: ManageProjectJiraConnections.ProjectJiraConnectionsView) = ProjectJiraConnectionsDto(
        systemConnectionId = view.settings.systemConnectionId?.value,
        additionalConnectionIds = view.settings.additionalConnectionIds.map { it.value },
        systemConnection = view.system?.let { toDto(it) },
        additionalConnections = view.additional.map { toDto(it) },
        available = view.available.map { toDto(it) },
    )

    fun verificationDto(v: ConnectionVerification) = ConnectionVerificationDto(
        status = v.status.name,
        checkedAt = v.checkedAt,
        account = v.account?.let { ConnectionAccountDto(it.accountId, it.displayName, it.emailAddress) },
        projects = v.projects.map { p ->
            ConnectionProjectCheckDto(p.projectKey, p.granted.sorted().map { it.name }, p.missing.sorted().map { it.name }, p.excess.sorted().map { it.name })
        },
        missing = v.missing.map { it.name },
        excess = v.excess.map { it.name },
        messages = v.messages.map { m -> ConnectionMessageDto(m.severity.name, m.code.name, m.projectKey, m.permissions.map { it.name }, m.text) },
    )

    fun toDto(choice: ConnectionChoice) = ConnectionResolutionDto(
        status = choice.status.name,
        required = choice.required.sorted().map { it.name },
        connection = choice.connection?.let { toDto(it) },
        pinned = choice.pinned,
        excess = choice.excess.sorted().map { it.name },
        message = choice.message,
        mode = choice.mode.name,
        fallback = choice.fallback,
    )
}

data class JiraScopeOptionDto(val kind: String, val required: List<String>, val recommended: List<String>)
data class JiraScopesDto(val verification: List<JiraScopeOptionDto>, val capabilities: Map<String, List<JiraScopeOptionDto>>)

data class PersonalConnectionSummaryDto(
    val id: UUID,
    val name: String,
    val siteUrl: String,
    val capabilities: List<String>,
    val tokenKind: String,
    val ownerUserId: UUID?,
    val ownerName: String?,
    val secretExpiresAt: LocalDate?,
    val expiryStatus: String,
    val verificationStatus: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class ProjectJiraConnectionsInputDto(val systemConnectionId: UUID? = null, val additionalConnectionIds: List<UUID>? = null)

data class ProjectJiraConnectionsDto(
    val systemConnectionId: UUID?,
    val additionalConnectionIds: List<UUID>,
    val systemConnection: ConnectionDto?,
    val additionalConnections: List<ConnectionDto>,
    val available: List<ConnectionDto>,
)

/** "Test before saving": the draft ([connection]); [connectionId] = an existing connection whose stored secret is used when the draft has none. */
data class ConnectionTestRequestDto(
    @field:NotNull @field:Valid val connection: ConnectionInputDto? = null,
    val connectionId: UUID? = null,
    val projectKey: String? = null,
)

/** Input of create/update. [secret] is write-only: required on create, null on update keeps the stored one. */
data class ConnectionInputDto(
    @field:NotBlank val type: String? = null,
    @field:NotBlank @field:Size(max = Connection.MAX_NAME) val name: String? = null,
    @field:NotBlank @field:Size(max = 300) val siteUrl: String? = null,
    @field:NotBlank @field:Size(max = 254) val accountEmail: String? = null,
    @field:Size(max = 4096) val secret: String? = null,
    @field:NotEmpty val capabilities: List<String>? = null,
    val allowedProjectKeys: List<String>? = null,
    val secretExpiresAt: LocalDate? = null,
    val verifyProjectKey: String? = null,
    val tokenKind: String? = null,
    @field:Size(max = 64) val cloudId: String? = null,
    @field:Size(max = 100) val tokenScopes: List<String>? = null,
    val acknowledgeClassicToken: Boolean? = null,
) {
    override fun toString(): String = "ConnectionInputDto(type=$type, name=$name, siteUrl=$siteUrl, capabilities=$capabilities, secret=${if (secret == null) "null" else "***"})"
}

data class ConnectionSecretInputDto(
    @field:NotNull @field:NotBlank @field:Size(max = 4096) val secret: String? = null,
    val secretExpiresAt: LocalDate? = null,
) {
    override fun toString(): String = "ConnectionSecretInputDto(secret=***, secretExpiresAt=$secretExpiresAt)"
}

data class ConnectionVerifyRequestDto(val projectKey: String? = null)

/** A connection as the API returns it: deliberately without any secret material. */
data class ConnectionDto(
    val id: UUID,
    val scope: String,
    val projectId: UUID?,
    val type: String,
    val name: String,
    val siteUrl: String,
    val accountEmail: String,
    val capabilities: List<String>,
    val allowedProjectKeys: List<String>,
    val verifyProjectKey: String?,
    val secretSet: Boolean,
    val secretRotatedAt: Instant?,
    val secretExpiresAt: LocalDate?,
    val expiryStatus: String,
    val legacy: Boolean,
    val createdAt: Instant?,
    val createdBy: ActorRefDto?,
    val updatedAt: Instant?,
    val updatedBy: ActorRefDto?,
    val verification: ConnectionVerificationDto?,
    val tokenKind: String = "CLASSIC",
    val cloudId: String? = null,
    val tokenScopes: List<String> = emptyList(),
    val classicTokenAcknowledged: Boolean = false,
    val ownerUserId: UUID? = null,
    val ownerName: String? = null,
)

data class ConnectionVerificationDto(
    val status: String,
    val checkedAt: Instant,
    val account: ConnectionAccountDto?,
    val projects: List<ConnectionProjectCheckDto>,
    val missing: List<String>,
    val excess: List<String>,
    val messages: List<ConnectionMessageDto>,
)

data class ConnectionAccountDto(val accountId: String, val displayName: String, val emailAddress: String?)
data class ConnectionProjectCheckDto(val projectKey: String, val granted: List<String>, val missing: List<String>, val excess: List<String>)
data class ConnectionMessageDto(val severity: String, val code: String, val projectKey: String?, val permissions: List<String>, val text: String)

data class ConnectionResolutionDto(
    val status: String,
    val required: List<String>,
    val connection: ConnectionDto?,
    val pinned: Boolean,
    val excess: List<String>,
    val message: String,
    val mode: String = "SYSTEM",
    val fallback: Boolean = false,
)
