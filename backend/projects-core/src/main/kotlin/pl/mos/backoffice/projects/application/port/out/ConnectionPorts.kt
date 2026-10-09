package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.SealedSecret

/** Stored connections (ADR 0030). The legacy environment credential is not stored ([LegacyConnectionSource]). */
interface ConnectionRepository {
    fun save(connection: Connection): Connection
    fun findById(id: ConnectionId): Connection?
    fun findPlatform(): List<Connection>
    fun findByProject(projectId: ProjectId): List<Connection>

    /** Personal connections of one user (ADR 0035). */
    fun findByOwner(userId: java.util.UUID): List<Connection>
    fun findAll(): List<Connection>
    fun count(): Long
    fun delete(id: ConnectionId)

    /**
     * Replaces the sealed secret of [id] only when it still is [expected] (a key re-wrap must not overwrite a token
     * rotated in the meantime). Returns whether the row was changed.
     */
    fun replaceSecret(id: ConnectionId, expected: SealedSecret, replacement: SealedSecret): Boolean

    /** Records that the expiry reminder for [expiresAt] was sent (one reminder per expiry date). */
    fun markExpiryReminded(id: ConnectionId, expiresAt: java.time.LocalDate)
}

/** The `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` credential as a read-only platform connection, or null when unset. */
fun interface LegacyConnectionSource {
    fun legacy(): Connection?

    companion object {
        val NONE = LegacyConnectionSource { null }
    }
}

/**
 * Seals secrets with envelope encryption (AES-256-GCM, key-encryption key from configuration). Core code can seal and
 * re-wrap but has no way to decrypt: only the adapters that call the external system open secrets.
 */
interface SecretSealer {
    /** A key-encryption key is configured. */
    val configured: Boolean

    /** Id of the key new secrets are sealed with, or null when none is configured. */
    val activeKeyId: String?

    /** [associatedData] binds the ciphertext to its owner (the connection id); the same value is needed to open it. */
    fun seal(plaintext: String, associatedData: String): SealedSecret

    /** Re-wraps the data key of [sealed] with the active key; null when it already uses the active key. */
    fun rewrap(sealed: SealedSecret, associatedData: String): SealedSecret?

    /** Whether [keyId] can still be opened (active or a configured previous key). */
    fun knows(keyId: String): Boolean
}

/**
 * Creates issue-tracker clients and verification probes for one connection (Jira Cloud REST v3 in the adapter). The
 * adapter opens the secret; core code only passes the connection.
 */
interface IssueTrackerFactory {
    fun open(connection: Connection): IssueTracker

    /**
     * The Jira Cloud id of [siteUrl] from the public `<site>/_edge/tenant_info` (ADR 0035; needed to call the API gateway
     * with a scoped token). Throws `IntegrationException` when the site does not answer with one.
     */
    fun discoverCloudId(siteUrl: String): String

    /** `GET /rest/api/3/myself`, then `GET /rest/api/3/mypermissions?projectKey=` for every key in [projectKeys]. */
    fun probe(connection: Connection, projectKeys: List<String>): JiraProbe
}
