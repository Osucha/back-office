package pl.mos.backoffice.llm.gateway

import pl.mos.backoffice.shared.llm.Provider

/** Credentials and endpoint of one provider as configured on this server. */
data class ProviderSettings(val apiKey: String? = null, val baseUrl: String? = null) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    val hasBaseUrl: Boolean get() = !baseUrl.isNullOrBlank()
}

/** Which providers are usable. Ollama needs only a base URL, OPENAI_COMPATIBLE needs URL and key, the rest need a key. */
class ProviderRegistry(private val settings: Map<Provider, ProviderSettings>) {

    operator fun get(provider: Provider): ProviderSettings = settings[provider] ?: ProviderSettings()

    fun isConfigured(provider: Provider): Boolean {
        val s = get(provider)
        return when (provider) {
            Provider.OLLAMA -> s.hasBaseUrl
            Provider.OPENAI_COMPATIBLE -> s.hasBaseUrl && s.hasKey
            else -> s.hasKey
        }
    }

    fun baseUrl(provider: Provider): String? = get(provider).baseUrl?.takeIf { it.isNotBlank() }
}
