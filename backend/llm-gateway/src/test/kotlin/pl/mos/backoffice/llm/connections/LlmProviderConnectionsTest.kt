package pl.mos.backoffice.llm.connections

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.llm.gateway.ProviderRegistry
import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.SealedValue
import pl.mos.backoffice.shared.security.SecretVault
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** ADR 0045: provider connections managed in Back Office, with environment variables as the fallback. */
class LlmProviderConnectionsTest {

    private val store = object : LlmProviderConnectionStore {
        val rows = LinkedHashMap<UUID, LlmProviderConnection>()
        override fun findAll() = rows.values.toList()
        override fun findById(id: UUID) = rows[id]
        override fun save(connection: LlmProviderConnection) = connection.also { rows[it.id] = it }
        override fun delete(id: UUID) { rows.remove(id) }
    }

    /** Reversible fake: the "ciphertext" is the plaintext reversed, bound to the associated data. */
    private val vault = object : SecretVault {
        override val configured = true
        override fun seal(plaintext: String, associatedData: String) = SealedValue("k1", associatedData, plaintext.reversed())
        override fun open(sealed: SealedValue, associatedData: String): String {
            require(sealed.wrappedKey == associatedData) { "wrong owner" }
            return sealed.ciphertext.reversed()
        }
    }
    private val cache = CachedProviderConnections(store, vault)
    private val environment = mapOf(
        Provider.ANTHROPIC to ProviderSettings(apiKey = "env-anthropic"),
        Provider.MISTRAL to ProviderSettings(),
    )
    private val registry = ProviderRegistry(environment, cache)
    private val audit = RecordingAuditPort()

    private fun service(vararg permissions: String) = LlmProviderService(
        store, vault, FakeAuthorizationGate.permitting(*permissions), audit, Clock { Instant.parse("2026-10-18T10:00:00Z") }, cache, registry,
        ProviderModelLister(JsonMapper.builder().build()),
    )

    private val admin = service(LlmProviderService.MANAGE, LlmProviderService.VIEW)

    @Test
    fun `connections are resolved by key, else the first of the type, else the environment`() {
        assertThat(registry.resolve(Provider.ANTHROPIC)!!.id).isEqualTo("env:ANTHROPIC")
        assertThat(registry.isConfigured(Provider.MISTRAL)).isFalse()

        admin.create(LlmProviderCommand("openrouter", "OpenRouter", Provider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1/", apiKey = "sk-or"))
        admin.create(LlmProviderCommand("groq", "Groq", Provider.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", apiKey = "gsk"))
        admin.create(LlmProviderCommand("work-anthropic", "Anthropic", Provider.ANTHROPIC, apiKey = "sk-ant"))

        val groq = registry.resolve(ModelRef(Provider.OPENAI_COMPATIBLE, "llama", connection = "groq"))!!
        assertThat(groq.settings).isEqualTo(ProviderSettings(apiKey = "gsk", baseUrl = "https://api.groq.com/openai/v1"))
        assertThat(registry.resolve(Provider.OPENAI_COMPATIBLE)!!.connection).isIn("openrouter", "groq")
        // a connection of the database wins over the environment; a key of another type resolves to nothing
        assertThat(registry.resolve(Provider.ANTHROPIC)!!.settings.apiKey).isEqualTo("sk-ant")
        assertThat(registry.resolve(Provider.ANTHROPIC, "groq")).isNull()
        assertThat(store.rows.values.first { it.key == "openrouter" }.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
    }

    @Test
    fun `keys are sealed, never listed, kept on update unless replaced or removed`() {
        val created = admin.create(LlmProviderCommand("openrouter", "OpenRouter", Provider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", apiKey = "sk-or"))
        val row = store.rows.getValue(created.id!!)
        assertThat(row.apiKey!!.ciphertext).isNotEqualTo("sk-or")
        assertThat(admin.list().first { it.key == "openrouter" }.hasApiKey).isTrue()

        admin.update(created.id!!, LlmProviderCommand("openrouter", "OpenRouter free", Provider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1"))
        assertThat(registry.resolve(Provider.OPENAI_COMPATIBLE, "openrouter")!!.settings.apiKey).isEqualTo("sk-or")
        admin.update(created.id!!, LlmProviderCommand("openrouter", "OpenRouter", Provider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", clearApiKey = true))
        assertThat(registry.resolve(Provider.OPENAI_COMPATIBLE, "openrouter")).isNull()
        assertThat(audit.entries.map { it.operation }).containsExactly("LLM_PROVIDER_CREATE", "LLM_PROVIDER_UPDATE", "LLM_PROVIDER_UPDATE")
        assertThat(audit.entries.flatMap { it.details.values }.map { it.toString() }).noneMatch { it.contains("sk-or") }
    }

    @Test
    fun `a Claude subscription token is refused as an Anthropic API key (ADR 0048)`() {
        assertThatThrownBy { admin.create(LlmProviderCommand("anthropic", "Anthropic", Provider.ANTHROPIC, apiKey = " sk-ant-oat01-abc")) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("subscription token")
        val created = admin.create(LlmProviderCommand("anthropic", "Anthropic", Provider.ANTHROPIC, apiKey = "sk-ant-api03-abc"))
        assertThatThrownBy { admin.update(created.id!!, LlmProviderCommand("anthropic", "Anthropic", Provider.ANTHROPIC, apiKey = "sk-ant-oat01-abc")) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `key and type are fixed, keys unique, only admins change, environment endpoints listed read only`() {
        val created = admin.create(LlmProviderCommand("ollama", "Ollama", Provider.OLLAMA, "http://localhost:11434"))
        assertThatThrownBy { admin.create(LlmProviderCommand("ollama", "Again", Provider.OLLAMA, "http://x")) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { admin.update(created.id!!, LlmProviderCommand("other", "Ollama", Provider.OLLAMA)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { admin.update(created.id!!, LlmProviderCommand("ollama", "Ollama", Provider.OPENAI)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service(LlmProviderService.VIEW).create(LlmProviderCommand("x1", "X", Provider.OPENAI, apiKey = "k")) }
            .isInstanceOf(InsufficientPermissionException::class.java)

        val listed = service(LlmProviderService.VIEW).list()
        assertThat(listed.filter { it.source == ConnectionSource.ENVIRONMENT }.map { it.provider }).containsExactly(Provider.ANTHROPIC)
        assertThat(listed.first { it.key == "ollama" }.usable).isTrue()

        admin.delete(created.id!!)
        assertThat(registry.isConfigured(Provider.OLLAMA)).isFalse()
    }
}
