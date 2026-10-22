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
import pl.mos.backoffice.projects.application.port.out.ScmConnectionRepository
import pl.mos.backoffice.projects.domain.model.MessageSeverity
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmMessage
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.projects.domain.model.ScmVerification
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `scm_connection` (V119, ADR 0051). The secret columns hold only sealed values (base64, see [SealedSecret]). */
@Entity
@Table(name = "scm_connection")
class ScmConnectionEntity(
    @Id var id: UUID,
    @Column(name = "project_id", nullable = false) var projectId: UUID,
    @Column(name = "repository_key", nullable = false) var repositoryKey: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var provider: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") var capabilities: String,
    @Column(name = "secret_key_id", nullable = false) var secretKeyId: String,
    @Column(name = "secret_wrapped_key", nullable = false) var secretWrappedKey: String,
    @Column(name = "secret_ciphertext", nullable = false) var secretCiphertext: String,
    @Column(name = "secret_expires_at") var secretExpiresAt: LocalDate? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var verification: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "created_by", columnDefinition = "jsonb") var createdBy: String? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "updated_by", columnDefinition = "jsonb") var updatedBy: String? = null,
)

interface ScmConnectionJpaRepository : JpaRepository<ScmConnectionEntity, UUID> {
    fun findByProjectId(projectId: UUID): List<ScmConnectionEntity>

    @Modifying
    @Query(
        "update ScmConnectionEntity c set c.secretKeyId = :keyId, c.secretWrappedKey = :wrapped " +
            "where c.id = :id and c.secretWrappedKey = :expectedWrapped and c.secretCiphertext = :expectedCiphertext",
    )
    fun replaceWrappedKey(
        @Param("id") id: UUID,
        @Param("keyId") keyId: String,
        @Param("wrapped") wrapped: String,
        @Param("expectedWrapped") expectedWrapped: String,
        @Param("expectedCiphertext") expectedCiphertext: String,
    ): Int
}

/** Verification as stored in `scm_connection.verification` (jsonb). */
data class ScmVerificationDoc(
    val status: String,
    val checkedAt: Instant,
    val tokenName: String? = null,
    val scopes: List<String>? = null,
    val role: String? = null,
    val tokenExpiresAt: LocalDate? = null,
    val granted: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val writePowers: List<String> = emptyList(),
    val messages: List<ScmMessageDoc> = emptyList(),
)
data class ScmMessageDoc(val severity: String, val code: String, val text: String)

@Component
@Transactional
class ScmConnectionPersistenceAdapter(
    private val repo: ScmConnectionJpaRepository,
    private val json: JsonMapper,
    private val mapper: PersistenceMapper,
) : ScmConnectionRepository {

    private val stringList = object : TypeReference<List<String>>() {}

    override fun save(connection: ScmConnection): ScmConnection = toDomain(repo.save(toEntity(connection)))

    @Transactional(readOnly = true)
    override fun findById(id: ScmConnectionId): ScmConnection? = repo.findById(id.value).orElse(null)?.let(::toDomain)

    @Transactional(readOnly = true)
    override fun findByProject(projectId: ProjectId): List<ScmConnection> = repo.findByProjectId(projectId.value).map(::toDomain)

    @Transactional(readOnly = true)
    override fun findAll(): List<ScmConnection> = repo.findAll().map(::toDomain)

    override fun delete(id: ScmConnectionId) = repo.deleteById(id.value)

    override fun replaceSecret(id: ScmConnectionId, expected: SealedSecret, replacement: SealedSecret): Boolean {
        require(expected.ciphertext == replacement.ciphertext) { "a re-wrap keeps the ciphertext; replace the token through save()" }
        return repo.replaceWrappedKey(id.value, replacement.keyId, replacement.wrappedKey, expected.wrappedKey, expected.ciphertext) == 1
    }

    private fun toEntity(c: ScmConnection) = ScmConnectionEntity(
        id = c.id.value, projectId = c.projectId.value, repositoryKey = c.repositoryKey, name = c.name, provider = c.provider.name,
        capabilities = json.writeValueAsString(c.capabilities.sorted().map { it.name }),
        secretKeyId = c.secret.keyId, secretWrappedKey = c.secret.wrappedKey, secretCiphertext = c.secret.ciphertext,
        secretExpiresAt = c.secretExpiresAt, verification = c.verification?.let { json.writeValueAsString(toDoc(it)) },
        createdAt = c.createdAt, createdBy = json.writeValueAsString(mapper.toDoc(c.createdBy)),
        updatedAt = c.updatedAt, updatedBy = json.writeValueAsString(mapper.toDoc(c.updatedBy)),
    )

    private fun toDomain(e: ScmConnectionEntity) = ScmConnection(
        id = ScmConnectionId(e.id), projectId = ProjectId(e.projectId), repositoryKey = e.repositoryKey, name = e.name,
        provider = ScmProvider.valueOf(e.provider),
        capabilities = json.readValue(e.capabilities, stringList).mapNotNull { n -> ScmCapability.entries.firstOrNull { it.name == n } }.toSet(),
        secret = SealedSecret(e.secretKeyId, e.secretWrappedKey, e.secretCiphertext), secretExpiresAt = e.secretExpiresAt,
        verification = e.verification?.let { toDomain(json.readValue(it, ScmVerificationDoc::class.java)) },
        createdAt = e.createdAt, createdBy = mapper.toDomain(json.readValue(e.createdBy ?: "{}", ActorDoc::class.java)),
        updatedAt = e.updatedAt, updatedBy = mapper.toDomain(json.readValue(e.updatedBy ?: "{}", ActorDoc::class.java)),
    )

    private fun toDoc(v: ScmVerification) = ScmVerificationDoc(
        v.status.name, v.checkedAt, v.tokenName, v.scopes, v.role, v.tokenExpiresAt, v.granted.map { it.name }.sorted(),
        v.missing.map { it.name }.sorted(), v.writePowers, v.messages.map { ScmMessageDoc(it.severity.name, it.code, it.text) },
    )

    private fun capabilities(names: List<String>) = names.mapNotNull { n -> ScmCapability.entries.firstOrNull { it.name == n } }.toSet()

    private fun toDomain(d: ScmVerificationDoc) = ScmVerification(
        VerificationStatus.valueOf(d.status), d.checkedAt, d.tokenName, d.scopes, d.role, d.tokenExpiresAt,
        capabilities(d.granted), capabilities(d.missing), d.writePowers,
        d.messages.map { ScmMessage(MessageSeverity.valueOf(it.severity), it.code, it.text) },
    )
}
