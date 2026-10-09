package pl.mos.backoffice.projects

import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.application.port.out.IssueTracker
import pl.mos.backoffice.projects.application.port.out.IssueTrackerFactory
import pl.mos.backoffice.projects.application.port.out.SecretSealer
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.IssueSnapshot
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectProbe
import pl.mos.backoffice.projects.domain.model.SealedSecret
import pl.mos.backoffice.projects.domain.model.VerifiedAccount
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import java.util.concurrent.CopyOnWriteArrayList

/** Test doubles and builders for connections (ADR 0030). */
object ConnectionFakes {

    fun connection(
        name: String,
        capabilities: Set<ConnectionCapability>,
        projectId: ProjectId? = null,
        allowed: List<String> = emptyList(),
        legacy: Boolean = false,
        id: ConnectionId = if (legacy) ConnectionId.LEGACY_JIRA else ConnectionId.new(),
    ) = Connection(
        id = id,
        scope = if (projectId == null) ConnectionScope.PLATFORM else ConnectionScope.PROJECT,
        projectId = projectId,
        type = ConnectionType.JIRA,
        name = name,
        siteUrl = "https://acme.atlassian.net",
        accountEmail = "bot@acme.com",
        capabilities = capabilities,
        allowedProjectKeys = allowed,
        secret = if (legacy) null else SealedSecret("k1", "wrapped-$name", "cipher-$name"),
        createdAt = Fixtures.now,
        updatedAt = Fixtures.now,
        legacy = legacy,
    )

    /** A personal connection (ADR 0035) of [owner]. */
    fun personal(name: String, capabilities: Set<ConnectionCapability>, owner: java.util.UUID, ownerName: String = "Anna Nowak", allowed: List<String> = emptyList()) =
        connection(name, capabilities, allowed = allowed).copy(scope = ConnectionScope.USER, ownerUserId = owner, ownerName = ownerName)

    val READ = setOf(ConnectionCapability.ISSUE_READ)
    val WRITE = setOf(ConnectionCapability.ISSUE_COMMENT, ConnectionCapability.ISSUE_TRANSITION, ConnectionCapability.ISSUE_READ)
    val ALL = ConnectionCapability.entries.toSet()

    class InMemoryConnections : ConnectionRepository {
        val rows = linkedMapOf<ConnectionId, Connection>()
        override fun save(connection: Connection) = connection.also { rows[it.id] = it }
        override fun findById(id: ConnectionId) = rows[id]
        override fun findPlatform() = rows.values.filter { it.scope == ConnectionScope.PLATFORM }
        override fun findByProject(projectId: ProjectId) = rows.values.filter { it.projectId == projectId }
        override fun findByOwner(userId: java.util.UUID) = rows.values.filter { it.ownerUserId == userId }
        override fun findAll() = rows.values.toList()
        override fun count() = rows.size.toLong()
        override fun delete(id: ConnectionId) {
            rows.remove(id)
        }
        override fun replaceSecret(id: ConnectionId, expected: SealedSecret, replacement: SealedSecret): Boolean {
            val current = rows[id] ?: return false
            if (current.secret != expected) return false
            rows[id] = current.copy(secret = replacement)
            return true
        }
        override fun markExpiryReminded(id: ConnectionId, expiresAt: java.time.LocalDate) {
            rows[id]?.let { rows[id] = it.copy(expiryRemindedFor = expiresAt) }
        }
    }

    /** Seals as `sealed(<plaintext>)` so tests can prove the plaintext never leaves through other channels. */
    class FakeSealer(override var configured: Boolean = true, var keyId: String = "k1") : SecretSealer {
        val sealedPlaintexts = mutableListOf<String>()
        override val activeKeyId: String? get() = keyId.takeIf { configured }
        override fun seal(plaintext: String, associatedData: String): SealedSecret {
            sealedPlaintexts += plaintext
            return SealedSecret(keyId, "wrapped:$associatedData", "ciphertext-${plaintext.length}")
        }
        override fun rewrap(sealed: SealedSecret, associatedData: String): SealedSecret? =
            if (sealed.keyId == keyId) null else sealed.copy(keyId = keyId, wrappedKey = "rewrapped:$associatedData")
        override fun knows(keyId: String) = keyId == this.keyId
    }

    /** Answers probes with [granted] per project and records which clients were opened. */
    class FakeTrackers(var granted: Map<String, Set<JiraPermission>> = emptyMap(), var account: VerifiedAccount? = VerifiedAccount("acc-1", "Reader Bot", "bot@acme.com")) : IssueTrackerFactory {
        val opened = CopyOnWriteArrayList<Connection>()
        val calls = CopyOnWriteArrayList<String>()
        val probed = CopyOnWriteArrayList<List<String>>()
        /** (issue key, exact body) of every comment written. */
        val comments = CopyOnWriteArrayList<Pair<String, String>>()
        val watchedJql = CopyOnWriteArrayList<String>()
        var watchResult: List<WatchedIssue> = emptyList()
        var watchFailure: RuntimeException? = null
        /** ADR 0035: the cloud id `_edge/tenant_info` answers; null = discovery fails. */
        var cloudId: String? = "11111111-2222-3333-4444-555555555555"
        val discovered = CopyOnWriteArrayList<String>()
        /** Endpoint checks answered for scoped tokens. */
        var scopeProbes: List<pl.mos.backoffice.projects.domain.model.ScopeProbe> = emptyList()

        override fun discoverCloudId(siteUrl: String): String {
            discovered += siteUrl
            return cloudId ?: throw pl.mos.backoffice.shared.IntegrationException("jira", "$siteUrl/_edge/tenant_info answered HTTP 404")
        }

        override fun open(connection: Connection): IssueTracker {
            opened += connection
            return object : IssueTracker {
                override fun isConfigured() = true
                override fun fetch(issueKey: String): IssueSnapshot {
                    calls += "fetch $issueKey"
                    return IssueSnapshot(key = issueKey, summary = "Summary of $issueKey", description = "")
                }
                override fun search(jql: String, limit: Int): List<IssueSnapshot> {
                    calls += "search $jql"
                    return emptyList()
                }
                override fun comment(issueKey: String, body: String) {
                    calls += "comment $issueKey"
                    comments += issueKey to body
                }
                override fun watch(jql: String, limit: Int): List<WatchedIssue> {
                    watchedJql += jql
                    watchFailure?.let { throw it }
                    return watchResult
                }
                override fun transition(issueKey: String, toStatus: String) {
                    calls += "transition $issueKey $toStatus"
                }
            }
        }

        override fun probe(connection: Connection, projectKeys: List<String>): JiraProbe {
            probed += projectKeys
            return JiraProbe(
                account, null, projectKeys.map { ProjectProbe(it, granted[it] ?: emptySet()) },
                if (connection.tokenKind == pl.mos.backoffice.projects.domain.model.JiraTokenKind.SCOPED) scopeProbes else emptyList(),
            )
        }
    }
}
