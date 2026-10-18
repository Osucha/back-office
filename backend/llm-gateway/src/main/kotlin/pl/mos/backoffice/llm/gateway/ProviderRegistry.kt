package pl.mos.backoffice.llm.gateway

import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider

/** Credentials and endpoint of one provider endpoint. */
data class ProviderSettings(val apiKey: String? = null, val baseUrl: String? = null) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    val hasBaseUrl: Boolean get() = !baseUrl.isNullOrBlank()

    /** Ollama needs only a base URL, OPENAI_COMPATIBLE needs URL and key, the rest need a key. */
    fun usableFor(provider: Provider): Boolean = when (provider) {
        Provider.OLLAMA -> hasBaseUrl
        Provider.OPENAI_COMPATIBLE -> hasBaseUrl && hasKey
        else -> hasKey
    }

    override fun toString(): String = "ProviderSettings(apiKey=${if (hasKey) "***" else "none"}, baseUrl=$baseUrl)"
}

/**
 * ADR 0045: an LLM provider connection added in the UI (`llm_provider_connection`), decrypted. [key] is what a model
 * profile names in `ModelRef.connection`.
 */
data class ProviderConnection(val key: String, val provider: Provider, val settings: ProviderSettings)

/** Enabled provider connections from the database (cached by the implementation). */
fun interface ProviderConnectionSource {
    fun connections(): List<ProviderConnection>

    companion object {
        val NONE = ProviderConnectionSource { emptyList() }
    }
}

/**
 * One usable endpoint: a connection of the database ([id] `db:<key>`) or the environment fallback of a provider type
 * ([id] `env:<TYPE>`). Clients and concurrency limits are kept per [id].
 */
data class ProviderEndpoint(val id: String, val provider: Provider, val settings: ProviderSettings, val connection: String?) {
    /** Changes whenever the endpoint's URL or key changes: a cached client of an older fingerprint is replaced. */
    val fingerprint: String get() = "$id|${settings.baseUrl}|${settings.apiKey?.hashCode()}"
}

/**
 * Which provider endpoints are usable (ADR 0045). A model names a connection ([ModelRef.connection]): that connection
 * of the database, of the model's provider type. Without one: the first enabled connection of that type, else the
 * environment variables of the type (the configuration before ADR 0045, kept as a fallback).
 */
class ProviderRegistry(
    private val environment: Map<Provider, ProviderSettings>,
    private val source: ProviderConnectionSource = ProviderConnectionSource.NONE,
) {

    fun resolve(provider: Provider, connection: String? = null): ProviderEndpoint? {
        val all = source.connections()
        if (connection != null) {
            val c = all.firstOrNull { it.key == connection && it.provider == provider } ?: return null
            return c.takeIf { it.settings.usableFor(provider) }?.let { ProviderEndpoint("db:${it.key}", provider, it.settings, it.key) }
        }
        all.firstOrNull { it.provider == provider && it.settings.usableFor(provider) }?.let { return ProviderEndpoint("db:${it.key}", provider, it.settings, it.key) }
        val env = environment[provider] ?: return null
        return env.takeIf { it.usableFor(provider) }?.let { ProviderEndpoint("env:${provider.name}", provider, it, null) }
    }

    fun resolve(model: ModelRef): ProviderEndpoint? = resolve(model.provider, model.connection)

    /** Settings of the endpoint a model without a connection would use (empty when none). */
    operator fun get(provider: Provider): ProviderSettings = resolve(provider)?.settings ?: ProviderSettings()

    fun isConfigured(provider: Provider): Boolean = resolve(provider) != null

    fun isConfigured(model: ModelRef): Boolean = resolve(model) != null

    fun baseUrl(provider: Provider): String? = get(provider).baseUrl?.takeIf { it.isNotBlank() }

    /** The environment fallback of [provider] (shown read-only in the UI). */
    fun environment(provider: Provider): ProviderSettings? = environment[provider]?.takeIf { it.usableFor(provider) }
}
