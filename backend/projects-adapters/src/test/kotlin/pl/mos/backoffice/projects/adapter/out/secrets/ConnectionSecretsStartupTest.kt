package pl.mos.backoffice.projects.adapter.out.secrets

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import pl.mos.backoffice.projects.application.port.`in`.RewrapConnectionSecrets
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.SealedSecret
import java.util.Base64

/** ADR 0030: the api/worker roles refuse to start when stored secrets cannot be opened; the worker re-wraps after a rotation. */
class ConnectionSecretsStartupTest {

    private val k1 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val k2 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    private class Rows(val rows: List<Connection>) : ConnectionRepository {
        override fun save(connection: Connection) = connection
        override fun findById(id: ConnectionId) = rows.firstOrNull { it.id == id }
        override fun findPlatform() = rows
        override fun findByProject(projectId: ProjectId) = emptyList<Connection>()
        override fun findByOwner(userId: java.util.UUID) = emptyList<Connection>()
        override fun findAll() = rows
        override fun count() = rows.size.toLong()
        override fun delete(id: ConnectionId) = Unit
        override fun replaceSecret(id: ConnectionId, expected: SealedSecret, replacement: SealedSecret) = true
        override fun markExpiryReminded(id: ConnectionId, expiresAt: java.time.LocalDate) = Unit
    }

    private fun stored(keyId: String) = Connection(
        id = ConnectionId.new(), scope = ConnectionScope.PLATFORM, projectId = null, type = ConnectionType.JIRA, name = "reader",
        siteUrl = "https://acme.atlassian.net", accountEmail = "reader@acme.com", capabilities = setOf(ConnectionCapability.ISSUE_READ),
        secret = SealedSecret(keyId, "w", "c"),
    )

    private var rewraps = 0

    private fun startup(properties: SecretsProperties, rows: List<Connection>, roles: String = "api,worker") = ConnectionSecretsStartup(
        AesGcmSecretCipher(properties), properties, Rows(rows), RewrapConnectionSecrets { rewraps++; 0 },
        MockEnvironment()
            .withProperty("backoffice.platform.api-enabled", ("api" in roles).toString())
            .withProperty("backoffice.platform.worker-enabled", ("worker" in roles).toString()),
    )

    @Test
    fun `without connections the key is optional`() {
        assertThatCode { startup(SecretsProperties(), emptyList()).run(DefaultApplicationArguments()) }.doesNotThrowAnyException()
    }

    @Test
    fun `connections without the key fail the start with what to set`() {
        assertThatThrownBy { startup(SecretsProperties(), listOf(stored("k1"))).run(DefaultApplicationArguments()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BACKOFFICE_SECRETS_KEY is not set")
            .hasMessageContaining("BACKOFFICE_SECRETS_ALLOW_UNREADABLE")
        // a migrate-only replica does not care
        assertThatCode { startup(SecretsProperties(), listOf(stored("k1")), roles = "migrate").run(DefaultApplicationArguments()) }.doesNotThrowAnyException()
    }

    @Test
    fun `an unknown key id fails unless the previous key is configured or recovery is switched on`() {
        assertThatThrownBy { startup(SecretsProperties(key = k2, keyId = "k2"), listOf(stored("k1"))).run(DefaultApplicationArguments()) }
            .hasMessageContaining("[k1]")
        assertThatCode { startup(SecretsProperties(key = k2, keyId = "k2", previousKeys = "k1:$k1"), listOf(stored("k1"))).run(DefaultApplicationArguments()) }
            .doesNotThrowAnyException()
        assertThat(rewraps).isEqualTo(1)
        assertThatCode { startup(SecretsProperties(key = k2, keyId = "k2", allowUnreadable = true), listOf(stored("k1"))).run(DefaultApplicationArguments()) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `only the worker role re-wraps`() {
        startup(SecretsProperties(key = k1, keyId = "k1"), listOf(stored("k1")), roles = "api").run(DefaultApplicationArguments())
        assertThat(rewraps).isZero()
        startup(SecretsProperties(key = k1, keyId = "k1"), listOf(stored("k1")), roles = "worker").run(DefaultApplicationArguments())
        assertThat(rewraps).isEqualTo(1)
    }
}
