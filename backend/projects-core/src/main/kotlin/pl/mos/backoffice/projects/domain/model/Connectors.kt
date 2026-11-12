package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.ConnectorAuth
import pl.mos.backoffice.shared.runner.ConnectorKeys
import pl.mos.backoffice.shared.runner.ConnectorKind
import pl.mos.backoffice.shared.security.Actor
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/** Identifier of a [Connector] (ADR 0064). */
@JvmInline
value class ConnectorId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ConnectorId(UUID.randomUUID())
        fun of(value: String) = ConnectorId(UUID.fromString(value))
    }
}

/** ADR 0064: the result of the last test of a connector from Back Office; [tools] = what an MCP server announced. */
data class ConnectorTest(val ok: Boolean, val message: String?, val tools: List<String> = emptyList(), val testedAt: Instant)

/**
 * ADR 0064: a tool agents may use. Platform scope ([projectId] null; administrators, every project) or project scope
 * (project owners); a project connector shadows the platform connector with the same [key].
 *
 * - `MCP_HTTP`: a remote MCP server ([url], streamable HTTP) and how it authenticates ([auth], for `HEADER` the
 *   [headerName]); the [secret] is stored only sealed (associated data [secretAssociatedData]) and never returned;
 * - `GITLAB_READ`: glab with the project's verified read-only `CI_READ` token of [repository] (null = the primary
 *   repository); project scope only, no URL, no secret of its own (ADR 0051).
 */
data class Connector(
    val id: ConnectorId,
    val key: String,
    val name: String,
    val description: String,
    val kind: ConnectorKind,
    val projectId: ProjectId?,
    val url: String? = null,
    val auth: ConnectorAuth = ConnectorAuth.NONE,
    val headerName: String? = null,
    val repository: String? = null,
    val secret: SealedSecret? = null,
    val lastTest: ConnectorTest? = null,
    val createdAt: Instant,
    val createdBy: Actor,
    val updatedAt: Instant,
    val updatedBy: Actor,
) {
    init {
        if (!ConnectorKeys.PATTERN.matches(key)) throw ValidationException("must match ${ConnectorKeys.PATTERN.pattern}", "key")
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        if (name.length > MAX_NAME_LENGTH) throw ValidationException("at most $MAX_NAME_LENGTH characters", "name")
        if (description.length > MAX_DESCRIPTION_LENGTH) throw ValidationException("at most $MAX_DESCRIPTION_LENGTH characters", "description")
        when (kind) {
            ConnectorKind.MCP_HTTP -> {
                validUrl(url)
                if (auth == ConnectorAuth.HEADER) {
                    val header = headerName ?: throw ValidationException("is required for auth HEADER", "headerName")
                    if (!HEADER_NAME.matches(header)) throw ValidationException("must match ${HEADER_NAME.pattern}", "headerName")
                    if (header.lowercase() in RESERVED_HEADERS) throw ValidationException("$header is set by the HTTP client; choose another header", "headerName")
                } else if (headerName != null) {
                    throw ValidationException("is only for auth HEADER", "headerName")
                }
                if (auth == ConnectorAuth.NONE && secret != null) throw ValidationException("auth NONE sends no secret", "secret")
                if (repository != null) throw ValidationException("is only for GITLAB_READ connectors", "repository")
            }
            ConnectorKind.GITLAB_READ -> {
                if (projectId == null) throw ValidationException("GITLAB_READ connectors belong to a project (they use its repository tokens)", "scope")
                if (url != null) throw ValidationException("is only for MCP_HTTP connectors", "url")
                if (auth != ConnectorAuth.NONE || headerName != null || secret != null) {
                    throw ValidationException("GITLAB_READ uses the project's read-only CI_READ token, not a secret of its own", "auth")
                }
                if (repository != null && repository.isBlank()) throw ValidationException("must not be blank (omit it for the primary repository)", "repository")
            }
        }
    }

    val platform: Boolean get() = projectId == null

    val hasSecret: Boolean get() = secret != null

    /** `MCP_HTTP`: the host the sandbox egress policy must allow (ADR 0024). */
    val host: String? get() = url?.let { runCatching { URI(it).host }.getOrNull() }

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2_000
        const val MAX_URL_LENGTH = 500
        const val MAX_SECRET_LENGTH = 8_192
        val HEADER_NAME: Regex = Regex("^[A-Za-z0-9-]{1,64}$")

        /** Headers the MCP client sets itself; a secret in one of them would be lost or break the request. */
        private val RESERVED_HEADERS = setOf(
            "host", "content-length", "content-type", "accept", "connection", "transfer-encoding", "expect", "upgrade", "user-agent",
            "mcp-session-id", "mcp-protocol-version",
        )

        /** Binds the sealed secret to its connector. */
        fun secretAssociatedData(id: ConnectorId): String = "connector:$id"

        /** Throws unless [url] is an http:// or https:// URL with a host, at most [MAX_URL_LENGTH] characters. */
        fun validUrl(url: String?) {
            val value = url ?: throw ValidationException("is required for MCP_HTTP", "url")
            if (value.length > MAX_URL_LENGTH) throw ValidationException("at most $MAX_URL_LENGTH characters", "url")
            val lower = value.lowercase()
            val ok = (lower.startsWith("http://") || lower.startsWith("https://")) && value.none { it.isWhitespace() } &&
                runCatching { URI(value).host }.getOrNull()?.isNotBlank() == true
            if (!ok) throw ValidationException("must be an http:// or https:// URL", "url")
        }

        /**
         * A valid key from a display name: diacritics dropped, everything but letters and digits becomes `-`, a leading
         * digit or nothing usable gets the prefix `connector`; at most 36 characters (room for a `-<n>` suffix).
         */
        fun keyFromName(name: String): String {
            val ascii = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            var slug = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            if (slug.isEmpty() || !slug.first().isLetter()) slug = if (slug.isEmpty()) KEY_PREFIX else "$KEY_PREFIX-$slug"
            return slug.take(MAX_KEY_BASE).trimEnd('-')
        }

        /** [base] when [taken] says it is free, else the first free `base-2`, `base-3`, ... */
        fun uniqueKey(base: String, taken: (String) -> Boolean): String {
            if (!taken(base)) return base
            var n = 2
            while (taken("$base-$n")) n++
            return "$base-$n"
        }

        private const val KEY_PREFIX = "connector"
        private const val MAX_KEY_BASE = 36
    }
}
