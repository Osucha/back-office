package pl.mos.backoffice.projects.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.ConnectionVerification
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectPermissionCheck
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerificationMessage
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.model.VerifiedAccount
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `connection` (V107, ADR 0030). The secret columns hold only sealed values (base64, see [SealedSecret]). */
@Entity
@Table(name = "connection")
class ConnectionEntity(
    @Id var id: UUID,
    @Column(nullable = false) var scope: String,
    @Column(name = "project_id") var projectId: UUID? = null,
    @Column(nullable = false) var type: String,
    @Column(nullable = false) var name: String,
    @Column(name = "site_url", nullable = false) var siteUrl: String,
    @Column(name = "account_email", nullable = false) var accountEmail: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var capabilities: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "allowed_project_keys", nullable = false, columnDefinition = "jsonb") var allowedProjectKeys: String,
    @Column(name = "verify_project_key") var verifyProjectKey: String? = null,
    @Column(name = "secret_key_id", nullable = false) var secretKeyId: String,
    @Column(name = "secret_wrapped_key", nullable = false) var secretWrappedKey: String,
    @Column(name = "secret_ciphertext", nullable = false) var secretCiphertext: String,
    @Column(name = "secret_rotated_at") var secretRotatedAt: Instant? = null,
    @Column(name = "secret_expires_at") var secretExpiresAt: LocalDate? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var verification: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "created_by", columnDefinition = "jsonb") var createdBy: String? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "updated_by", columnDefinition = "jsonb") var updatedBy: String? = null,
    /** V108: the expiry date the last reminder was sent for. */
    @Column(name = "expiry_reminded_for") var expiryRemindedFor: LocalDate? = null,
    /** V110 (ADR 0035): owner of a personal (USER) connection and their name when it was created. */
    @Column(name = "owner_user_id") var ownerUserId: UUID? = null,
    @Column(name = "owner_name") var ownerName: String? = null,
    /** V110: CLASSIC or SCOPED, the cloud id and declared scopes of scoped tokens, and the classic-token acknowledgement. */
    @Column(name = "token_kind", nullable = false) var tokenKind: String = "CLASSIC",
    @Column(name = "cloud_id") var cloudId: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "token_scopes", nullable = false, columnDefinition = "jsonb") var tokenScopes: String = "[]",
    @Column(name = "classic_acknowledged", nullable = false) var classicAcknowledged: Boolean = false,
)

interface ConnectionJpaRepository : JpaRepository<ConnectionEntity, UUID> {
    fun findByScope(scope: String): List<ConnectionEntity>
    fun findByProjectId(projectId: UUID): List<ConnectionEntity>
    fun findByOwnerUserId(ownerUserId: UUID): List<ConnectionEntity>

    @Modifying
    @Query(
        "update ConnectionEntity c set c.secretKeyId = :keyId, c.secretWrappedKey = :wrapped " +
            "where c.id = :id and c.secretWrappedKey = :expectedWrapped and c.secretCiphertext = :expectedCiphertext",
    )
    fun replaceWrappedKey(
        @Param("id") id: UUID,
        @Param("keyId") keyId: String,
        @Param("wrapped") wrapped: String,
        @Param("expectedWrapped") expectedWrapped: String,
        @Param("expectedCiphertext") expectedCiphertext: String,
    ): Int

    @Modifying
    @Query("update ConnectionEntity c set c.expiryRemindedFor = :expires where c.id = :id")
    fun markExpiryReminded(@Param("id") id: UUID, @Param("expires") expires: LocalDate): Int
}

/** Verification as stored in `connection.verification` (jsonb). */
data class VerificationDoc(
    val status: String,
    val checkedAt: Instant,
    val account: AccountDoc? = null,
    val projects: List<ProjectCheckDoc> = emptyList(),
    val messages: List<MessageDoc> = emptyList(),
)
data class AccountDoc(val accountId: String = "", val displayName: String = "", val emailAddress: String? = null)
data class ProjectCheckDoc(val projectKey: String, val granted: List<String> = emptyList(), val missing: List<String> = emptyList(), val excess: List<String> = emptyList())
data class MessageDoc(val severity: String, val code: String, val text: String, val projectKey: String? = null, val permissions: List<String> = emptyList())

@Component
@Transactional
class ConnectionPersistenceAdapter(
    private val repo: ConnectionJpaRepository,
    private val json: JsonMapper,
    private val mapper: PersistenceMapper,
) : ConnectionRepository {

    private val stringList = object : TypeReference<List<String>>() {}

    override fun save(connection: Connection): Connection {
        require(!connection.legacy) { "the legacy connection is not stored" }
        return toDomain(repo.save(toEntity(connection)))
    }

    @Transactional(readOnly = true)
    override fun findById(id: ConnectionId): Connection? = repo.findById(id.value).orElse(null)?.let(::toDomain)

    @Transactional(readOnly = true)
    override fun findPlatform(): List<Connection> = repo.findByScope(ConnectionScope.PLATFORM.name).map(::toDomain)

    @Transactional(readOnly = true)
    override fun findByProject(projectId: ProjectId): List<Connection> = repo.findByProjectId(projectId.value).map(::toDomain)

    @Transactional(readOnly = true)
    override fun findByOwner(userId: UUID): List<Connection> = repo.findByOwnerUserId(userId).map(::toDomain)

    @Transactional(readOnly = true)
    override fun findAll(): List<Connection> = repo.findAll().map(::toDomain)

    @Transactional(readOnly = true)
    override fun count(): Long = repo.count()

    override fun delete(id: ConnectionId) = repo.deleteById(id.value)

    override fun replaceSecret(id: ConnectionId, expected: SealedSecret, replacement: SealedSecret): Boolean {
        require(expected.ciphertext == replacement.ciphertext) { "a re-wrap keeps the ciphertext; rotate the secret through save()" }
        return repo.replaceWrappedKey(id.value, replacement.keyId, replacement.wrappedKey, expected.wrappedKey, expected.ciphertext) == 1
    }

    override fun markExpiryReminded(id: ConnectionId, expiresAt: LocalDate) {
        repo.markExpiryReminded(id.value, expiresAt)
    }

    private fun toEntity(c: Connection): ConnectionEntity {
        val secret = c.secret ?: throw IllegalArgumentException("connection ${c.id} has no sealed secret")
        val now = Instant.now()
        return ConnectionEntity(
            id = c.id.value, scope = c.scope.name, projectId = c.projectId?.value, type = c.type.name, name = c.name, siteUrl = c.siteUrl,
            accountEmail = c.accountEmail, capabilities = json.writeValueAsString(c.capabilities.sorted().map { it.name }),
            allowedProjectKeys = json.writeValueAsString(c.allowedProjectKeys), verifyProjectKey = c.verifyProjectKey,
            secretKeyId = secret.keyId, secretWrappedKey = secret.wrappedKey, secretCiphertext = secret.ciphertext,
            secretRotatedAt = c.secretRotatedAt, secretExpiresAt = c.secretExpiresAt,
            verification = c.verification?.let { json.writeValueAsString(toDoc(it)) },
            createdAt = c.createdAt ?: now, createdBy = c.createdBy?.let { json.writeValueAsString(mapper.toDoc(it)) },
            updatedAt = c.updatedAt ?: c.createdAt ?: now, updatedBy = c.updatedBy?.let { json.writeValueAsString(mapper.toDoc(it)) },
            expiryRemindedFor = c.expiryRemindedFor,
            ownerUserId = c.ownerUserId, ownerName = c.ownerName, tokenKind = c.tokenKind.name, cloudId = c.cloudId,
            tokenScopes = json.writeValueAsString(c.tokenScopes), classicAcknowledged = c.classicTokenAcknowledged,
        )
    }

    private fun toDomain(e: ConnectionEntity): Connection = Connection(
        id = ConnectionId(e.id),
        scope = ConnectionScope.valueOf(e.scope),
        projectId = e.projectId?.let(::ProjectId),
        type = ConnectionType.valueOf(e.type),
        name = e.name,
        siteUrl = e.siteUrl,
        accountEmail = e.accountEmail,
        // unknown capability names (written by a newer version) are ignored rather than failing the read
        capabilities = json.readValue(e.capabilities, stringList).mapNotNull { n -> ConnectionCapability.entries.firstOrNull { it.name == n } }.toSet(),
        allowedProjectKeys = json.readValue(e.allowedProjectKeys, stringList),
        verifyProjectKey = e.verifyProjectKey,
        secret = SealedSecret(e.secretKeyId, e.secretWrappedKey, e.secretCiphertext),
        secretRotatedAt = e.secretRotatedAt,
        secretExpiresAt = e.secretExpiresAt,
        createdAt = e.createdAt,
        createdBy = e.createdBy?.let { mapper.toDomain(json.readValue(it, ActorDoc::class.java)) },
        updatedAt = e.updatedAt,
        updatedBy = e.updatedBy?.let { mapper.toDomain(json.readValue(it, ActorDoc::class.java)) },
        verification = e.verification?.let { toDomain(json.readValue(it, VerificationDoc::class.java)) },
        expiryRemindedFor = e.expiryRemindedFor,
        ownerUserId = e.ownerUserId,
        ownerName = e.ownerName,
        tokenKind = JiraTokenKind.entries.firstOrNull { it.name == e.tokenKind } ?: JiraTokenKind.CLASSIC,
        cloudId = e.cloudId,
        tokenScopes = json.readValue(e.tokenScopes, stringList),
        classicTokenAcknowledged = e.classicAcknowledged,
    )

    private fun toDoc(v: ConnectionVerification) = VerificationDoc(
        status = v.status.name,
        checkedAt = v.checkedAt,
        account = v.account?.let { AccountDoc(it.accountId, it.displayName, it.emailAddress) },
        projects = v.projects.map { p -> ProjectCheckDoc(p.projectKey, p.granted.names(), p.missing.names(), p.excess.names()) },
        messages = v.messages.map { m -> MessageDoc(m.severity.name, m.code.name, m.text, m.projectKey, m.permissions.map { it.name }) },
    )

    private fun toDomain(d: VerificationDoc) = ConnectionVerification(
        status = VerificationStatus.valueOf(d.status),
        checkedAt = d.checkedAt,
        account = d.account?.let { VerifiedAccount(it.accountId, it.displayName, it.emailAddress) },
        projects = d.projects.map { p -> ProjectPermissionCheck(p.projectKey, p.granted.permissions(), p.missing.permissions(), p.excess.permissions()) },
        messages = d.messages.map { m ->
            VerificationMessage(
                MessageSeverity.valueOf(m.severity),
                VerificationCode.entries.firstOrNull { it.name == m.code } ?: VerificationCode.HTTP_ERROR,
                m.text, m.projectKey, m.permissions.permissions().sorted(),
            )
        },
    )

    private fun Set<JiraPermission>.names(): List<String> = sorted().map { it.name }

    private fun List<String>.permissions(): Set<JiraPermission> = mapNotNullTo(sortedSetOf()) { n -> JiraPermission.entries.firstOrNull { it.name == n } }
}
