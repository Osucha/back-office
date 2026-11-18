package pl.mos.backoffice.runnercontroller.sandbox

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR 0069: the owner's GitHub Copilot login for OpenCode jobs of a personal runner: the `github-copilot` entry of
 * OpenCode's auth file (written by `opencode auth login` / `/connect`, GitHub Copilot device flow), mounted read-only
 * into the runner-controller. [authJson] is `{"github-copilot": <entry>}`, the form OpenCode reads from
 * `OPENCODE_AUTH_CONTENT`; no other entry of the file (API keys of other providers) ever leaves the controller.
 * [hosts] are the hosts OpenCode calls with it (a GitHub Enterprise login: `copilot-api.<domain>`).
 */
class CopilotLogin(val authJson: String, val hosts: List<String>) {
    /** Never prints the login. */
    override fun toString(): String = "CopilotLogin(hosts=$hosts)"

    companion object {
        private val log = LoggerFactory.getLogger(CopilotLogin::class.java)
        private val MAPPER: JsonMapper = JsonMapper.builder().build()

        /** OpenCode's provider id and the auth type its Copilot plugin accepts (verified with OpenCode 1.18.32). */
        const val PROVIDER_ID = "github-copilot"
        private const val OAUTH = "oauth"
        const val DEFAULT_HOST = "api.githubcopilot.com"

        /**
         * The login in [file], or null when the file is missing or unreadable, or holds no usable `github-copilot` OAuth
         * entry. Reasons are logged without any value of the file.
         */
        fun read(file: String, json: JsonMapper): CopilotLogin? {
            if (file.isBlank()) return null
            val path = Path.of(file)
            if (!Files.isRegularFile(path)) return null
            val root = try {
                json.readTree(Files.readString(path))
            } catch (e: Exception) {
                log.warn("OpenCode auth file {} is not readable JSON ({}); no GitHub Copilot login", file, e.javaClass.simpleName)
                return null
            }
            return of(root)
        }

        /** The login of an OpenCode auth document (see [read]). */
        fun of(root: JsonNode?): CopilotLogin? {
            val entry = root?.get(PROVIDER_ID)?.takeIf { it.isObject } ?: return null
            if (entry.get("type")?.takeIf { it.isString }?.asString() != OAUTH) return null
            if (entry.get("refresh")?.takeIf { it.isString }?.asString().isNullOrBlank()) return null
            val enterprise = entry.get("enterpriseUrl")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
            val host = enterprise?.let { "copilot-api." + it.removePrefix("https://").removePrefix("http://").substringBefore('/') } ?: DEFAULT_HOST
            val doc = MAPPER.createObjectNode()
            doc.set(PROVIDER_ID, entry.deepCopy())
            return CopilotLogin(MAPPER.writeValueAsString(doc), listOf(host))
        }
    }
}
