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
import pl.mos.backoffice.projects.application.port.out.ConnectorRepository
import pl.mos.backoffice.projects.domain.model.Connector
import pl.mos.backoffice.projects.domain.model.ConnectorId
import pl.mos.backoffice.projects.domain.model.ConnectorTest
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKind
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** `connector` (V123, ADR 0064). The secret columns hold only sealed values (base64, see [SealedSecret]). */
@Entity
@Table(name = "connector")
class ConnectorEntity(
    @Id var id: UUID,
    @Column(nullable = false) var key: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false, columnDefinition = "text") var description: String,
    @Column(nullable = false) var kind: String,
    @Column(name = "project_id") var projectId: UUID? = null,
    @Column var url: String? = null,
    @Column(nullable = false) var auth: String = "NONE",
    @Column(name = "header_name") var headerName: String? = null,
    @Column var repository: String? = null,
    @Column(name = "secret_key_id") var secretKeyId: String? = null,
    @Column(name = "secret_wrapped_key") var secretWrappedKey: String? = null,
    @Column(name = "secret_ciphertext") var secretCiphertext: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "last_test", columnDefinition = "jsonb") var lastTest: String? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "created_by", columnDefinition = "jsonb") var createdBy: String? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "updated_by", columnDefinition = "jsonb") var updatedBy: String? = null,
)

interface ConnectorJpaRepository : JpaRepository<ConnectorEntity, UUID> {
    fun findByProjectId(projectId: UUID): List<ConnectorEntity>

    fun findByProjectIdIsNull(): List<ConnectorEntity>

    @Modifying
    @Query(
        "update ConnectorEntity c set c.secretKeyId = :keyId, c.secretWrappedKey = :wrapped " +
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

/** [ConnectorTest] as stored in `connector.last_test` (jsonb). */
data class ConnectorTestDoc(val ok: Boolean = false, val message: String? = null, val tools: List<String> = emptyList(), val testedAt: Instant = Instant.EPOCH)

@Component
@Transactional
class ConnectorPersistenceAdapter(
    private val repo: ConnectorJpaRepository,
    private val json: JsonMapper,
    private val mapper: PersistenceMapper,
) : ConnectorRepository {

    override fun save(connector: Connector): Connector = toDomain(repo.save(toEntity(connector)))

    @Transactional(readOnly = true)
    override fun findById(id: ConnectorId): Connector? = repo.findById(id.value).orElse(null)?.let(::toDomain)

    @Transactional(readOnly = true)
    override fun findPlatform(): List<Connector> = repo.findByProjectIdIsNull().map(::toDomain)

    @Transactional(readOnly = true)
    override fun findByProject(projectId: ProjectId): List<Connector> = repo.findByProjectId(projectId.value).map(::toDomain)

    @Transactional(readOnly = true)
    override fun findAll(): List<Connector> = repo.findAll().map(::toDomain)

    override fun delete(id: ConnectorId) = repo.deleteById(id.value)

    override fun replaceSecret(id: ConnectorId, expected: SealedSecret, replacement: SealedSecret): Boolean {
        require(expected.ciphertext == replacement.ciphertext) { "a re-wrap keeps the ciphertext; replace the secret through save()" }
        return repo.replaceWrappedKey(id.value, replacement.keyId, replacement.wrappedKey, expected.wrappedKey, expected.ciphertext) == 1
    }

    private fun toEntity(c: Connector) = ConnectorEntity(
        id = c.id.value, key = c.key, name = c.name, description = c.description, kind = c.kind.name, projectId = c.projectId?.value,
        url = c.url, auth = c.auth.name, headerName = c.headerName, repository = c.repository,
        secretKeyId = c.secret?.keyId, secretWrappedKey = c.secret?.wrappedKey, secretCiphertext = c.secret?.ciphertext,
        lastTest = c.lastTest?.let { json.writeValueAsString(ConnectorTestDoc(it.ok, it.message, it.tools, it.testedAt)) },
        createdAt = c.createdAt, createdBy = json.writeValueAsString(mapper.toDoc(c.createdBy)),
        updatedAt = c.updatedAt, updatedBy = json.writeValueAsString(mapper.toDoc(c.updatedBy)),
    )

    private fun toDomain(e: ConnectorEntity): Connector {
        val secret = if (e.secretKeyId != null && e.secretWrappedKey != null && e.secretCiphertext != null) {
            SealedSecret(e.secretKeyId!!, e.secretWrappedKey!!, e.secretCiphertext!!)
        } else {
            null
        }
        return Connector(
            id = ConnectorId(e.id), key = e.key, name = e.name, description = e.description,
            kind = ConnectorKind.valueOf(e.kind), projectId = e.projectId?.let(::ProjectId),
            url = e.url, auth = ConnectorAuth.entries.firstOrNull { it.name == e.auth } ?: ConnectorAuth.NONE, headerName = e.headerName,
            repository = e.repository, secret = secret,
            lastTest = e.lastTest?.let { json.readValue(it, ConnectorTestDoc::class.java) }?.let { ConnectorTest(it.ok, it.message, it.tools, it.testedAt) },
            createdAt = e.createdAt, createdBy = mapper.toDomain(json.readValue(e.createdBy ?: "{}", ActorDoc::class.java)),
            updatedAt = e.updatedAt, updatedBy = mapper.toDomain(json.readValue(e.updatedBy ?: "{}", ActorDoc::class.java)),
        )
    }
}
