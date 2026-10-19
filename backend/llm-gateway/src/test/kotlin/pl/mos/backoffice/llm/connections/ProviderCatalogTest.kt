package pl.mos.backoffice.llm.connections

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.llm.gateway.ProviderRegistry
import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.SealedValue
import pl.mos.backoffice.shared.security.SecretVault
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** ADR 0045: the Models page offers the models of the connected providers, with prices where the provider publishes them. */
class ProviderCatalogTest {

    private val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/models") { exchange ->
            calls.incrementAndGet()
            val body = """
                {"data": [
                  {"id": "vendor/paid-model", "name": "Vendor: Paid", "context_length": 128000,
                   "pricing": {"prompt": "0.0000025", "completion": "0.00001"}},
                  {"id": "google/gemma-4-31b-it:free", "name": "Gemma 4 (free)", "context_length": 32768,
                   "pricing": {"prompt": "0", "completion": "0"}},
                  {"id": "plain-model"}
                ]}
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}/api/v1"

    @AfterEach
    fun stop() = server.stop(0)

    private val lister = ProviderModelLister(JsonMapper.builder().build())

    @Test
    fun `OpenRouter style entries carry prices per million tokens, context and the free flag`() {
        val models = lister.list(Provider.OPENAI_COMPATIBLE, ProviderSettings(apiKey = "k", baseUrl = baseUrl))

        assertThat(models.ok).isTrue()
        assertThat(models.models).containsExactly("google/gemma-4-31b-it:free", "plain-model", "vendor/paid-model")
        val paid = models.details.first { it.id == "vendor/paid-model" }
        assertThat(paid.name).isEqualTo("Vendor: Paid")
        assertThat(paid.contextWindow).isEqualTo(128000)
        assertThat(paid.inputPerMTok).isEqualByComparingTo(BigDecimal("2.5"))
        assertThat(paid.outputPerMTok).isEqualByComparingTo(BigDecimal("10"))
        assertThat(paid.free).isFalse()
        assertThat(models.details.first { it.id.endsWith(":free") }.free).isTrue()
        val plain = models.details.first { it.id == "plain-model" }
        assertThat(plain.inputPerMTok).isNull()
        assertThat(plain.free).isFalse()
    }

    @Test
    fun `the catalogue lists usable connections, cached until refreshed, for model viewers`() {
        val store = object : LlmProviderConnectionStore {
            val rows = LinkedHashMap<UUID, LlmProviderConnection>()
            override fun findAll() = rows.values.toList()
            override fun findById(id: UUID) = rows[id]
            override fun save(connection: LlmProviderConnection) = connection.also { rows[it.id] = it }
            override fun delete(id: UUID) { rows.remove(id) }
        }
        val vault = object : SecretVault {
            override val configured = true
            override fun seal(plaintext: String, associatedData: String) = SealedValue("k1", associatedData, plaintext.reversed())
            override fun open(sealed: SealedValue, associatedData: String) = sealed.ciphertext.reversed()
        }
        val cache = CachedProviderConnections(store, vault)
        val registry = ProviderRegistry(emptyMap(), cache)
        fun service(vararg permissions: String) = LlmProviderService(
            store, vault, FakeAuthorizationGate.permitting(*permissions), RecordingAuditPort(),
            Clock { Instant.parse("2026-10-19T10:00:00Z") }, cache, registry, lister,
        )
        val admin = service(LlmProviderService.MANAGE, LlmProviderService.VIEW)
        admin.create(LlmProviderCommand("openrouter", "OpenRouter", Provider.OPENAI_COMPATIBLE, baseUrl, apiKey = "sk-or"))
        admin.create(LlmProviderCommand("off", "Disabled", Provider.OPENAI, apiKey = "sk", enabled = false))

        val viewer = service(LlmProviderService.VIEW)
        val sections = viewer.catalog(refresh = false)
        assertThat(sections).hasSize(1)
        assertThat(sections.single().connection).isEqualTo("openrouter")
        assertThat(sections.single().name).isEqualTo("OpenRouter")
        assertThat(sections.single().models.filter { it.free }.map { it.id }).containsExactly("google/gemma-4-31b-it:free")

        viewer.catalog(refresh = false)
        assertThat(calls.get()).isEqualTo(1)
        viewer.catalog(refresh = true)
        assertThat(calls.get()).isEqualTo(2)

        assertThatThrownBy { service().catalog(false) }.isInstanceOf(InsufficientPermissionException::class.java)
    }
}
