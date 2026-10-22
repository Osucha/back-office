package pl.mos.backoffice.llm.connections

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.llm.gateway.ProviderConnection
import pl.mos.backoffice.llm.gateway.ProviderConnectionSource
import pl.mos.backoffice.llm.gateway.ProviderRegistry
import pl.mos.backoffice.llm.gateway.ProviderSettings
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.SealedValue
import pl.mos.backoffice.shared.security.SecretVault
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import pl.mos.backoffice.llm.gateway.ConnectionLimits

/**
 * ADR 0045: an LLM provider connection added in Back Office: a provider type, an optional base URL and an API key
 * sealed with the installation's secret key. Model profiles name it by [key]; several connections of one type are
 * possible (OpenRouter and Groq as OPENAI_COMPATIBLE, two Anthropic organisations, ...).
 */
data class LlmProviderConnection(
    val id: UUID,
    val key: String,
    val name: String,
    val provider: Provider,
    val baseUrl: String?,
    val apiKey: SealedValue?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: Actor?,
    /** ADR 0046: optional limits Back Office keeps for this connection (null = none). */
    val requestsPerMinute: Int? = null,
    val requestsPerDay: Int? = null,
    /** ADR 0050 step 2b: the owner of a personal connection (their own API key); null = a platform connection. */
    val ownerUserId: UUID? = null,
) {
    val personal: Boolean get() = ownerUserId != null

    /** Platform connections are listed to everyone who may read models, a personal one only to its owner. */
    fun visibleTo(userId: UUID?): Boolean = ownerUserId == null || ownerUserId == userId

    init {
        if (!KEY.matches(key)) throw ValidationException("2-40 lower case letters, digits or hyphens, starting with a letter", "key")
        if (requestsPerMinute != null && requestsPerMinute <= 0) throw ValidationException("must be positive", "requestsPerMinute")
        if (requestsPerDay != null && requestsPerDay <= 0) throw ValidationException("must be positive", "requestsPerDay")
        if (name.isBlank()) throw ValidationException("must not be blank", "name")
        baseUrl?.let { if (!(it.startsWith("https://") || it.startsWith("http://"))) throw ValidationException("must be an http(s) URL", "baseUrl") }
    }

    companion object {
        val KEY = Regex("^[a-z][a-z0-9-]{1,39}$")

        /** Binds the sealed API key to its connection. */
        fun associatedData(id: UUID) = "llm-provider:$id"
    }
}

interface LlmProviderConnectionStore {
    fun findAll(): List<LlmProviderConnection>
    fun findById(id: UUID): LlmProviderConnection?
    fun save(connection: LlmProviderConnection): LlmProviderConnection
    fun delete(id: UUID)
}

/** `llm_provider_connection` (V6) over JDBC. */
class JdbcLlmProviderConnectionStore(private val jdbc: JdbcClient) : LlmProviderConnectionStore {

    override fun findAll(): List<LlmProviderConnection> =
        jdbc.sql("SELECT * FROM llm_provider_connection ORDER BY provider, key").query { rs, _ -> map(rs) }.list()

    override fun findById(id: UUID): LlmProviderConnection? =
        jdbc.sql("SELECT * FROM llm_provider_connection WHERE id = :id").param("id", id).query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun save(connection: LlmProviderConnection): LlmProviderConnection {
        jdbc.sql(
            """
            INSERT INTO llm_provider_connection (id, key, name, provider, base_url, secret_key_id, secret_wrapped_key, secret_ciphertext,
                                                 enabled, created_at, updated_at, updated_by_kind, updated_by_id, updated_by_name,
                                                 requests_per_minute, requests_per_day, owner_user_id)
            VALUES (:id, :key, :name, :provider, :baseUrl, :keyId, :wrapped, :cipher, :enabled, :created, :updated, :byKind, :byId, :byName,
                    :rpm, :rpd, :owner)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, base_url = EXCLUDED.base_url, secret_key_id = EXCLUDED.secret_key_id,
                secret_wrapped_key = EXCLUDED.secret_wrapped_key, secret_ciphertext = EXCLUDED.secret_ciphertext, enabled = EXCLUDED.enabled,
                updated_at = EXCLUDED.updated_at, updated_by_kind = EXCLUDED.updated_by_kind, updated_by_id = EXCLUDED.updated_by_id,
                updated_by_name = EXCLUDED.updated_by_name, requests_per_minute = EXCLUDED.requests_per_minute,
                requests_per_day = EXCLUDED.requests_per_day
            """.trimIndent(),
        )
            .param("id", connection.id).param("key", connection.key).param("name", connection.name).param("provider", connection.provider.name)
            .param("baseUrl", connection.baseUrl).param("keyId", connection.apiKey?.keyId).param("wrapped", connection.apiKey?.wrappedKey)
            .param("cipher", connection.apiKey?.ciphertext).param("enabled", connection.enabled)
            .param("created", connection.createdAt.atOffset(ZoneOffset.UTC)).param("updated", connection.updatedAt.atOffset(ZoneOffset.UTC))
            .param("byKind", connection.updatedBy?.kind?.name).param("byId", connection.updatedBy?.userId).param("byName", connection.updatedBy?.displayName)
            .param("rpm", connection.requestsPerMinute).param("rpd", connection.requestsPerDay).param("owner", connection.ownerUserId)
            .update()
        return connection
    }

    override fun delete(id: UUID) {
        jdbc.sql("DELETE FROM llm_provider_connection WHERE id = :id").param("id", id).update()
    }

    private fun map(rs: ResultSet): LlmProviderConnection {
        val keyId = rs.getString("secret_key_id")
        return LlmProviderConnection(
            id = rs.getObject("id", UUID::class.java),
            key = rs.getString("key"),
            name = rs.getString("name"),
            provider = Provider.valueOf(rs.getString("provider")),
            baseUrl = rs.getString("base_url"),
            apiKey = keyId?.let { SealedValue(it, rs.getString("secret_wrapped_key"), rs.getString("secret_ciphertext")) },
            enabled = rs.getBoolean("enabled"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            updatedBy = rs.getString("updated_by_name")?.let { name ->
                Actor(PrincipalKind.entries.firstOrNull { it.name == rs.getString("updated_by_kind") } ?: PrincipalKind.SYSTEM, rs.getObject("updated_by_id", UUID::class.java), name)
            },
            requestsPerMinute = rs.getObject("requests_per_minute") as Int?,
            requestsPerDay = rs.getObject("requests_per_day") as Int?,
            ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
        )
    }
}

/**
 * The enabled connections with their API keys opened, for the [ProviderRegistry]. Cached for [ttl] (other replicas see
 * a change within it) and refreshed at once after a change on this replica ([invalidate]).
 */
class CachedProviderConnections(
    private val store: LlmProviderConnectionStore,
    private val vault: SecretVault?,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
) : ProviderConnectionSource {
    private val log = LoggerFactory.getLogger(CachedProviderConnections::class.java)
    private val cache = AtomicReference<Pair<Instant, List<ProviderConnection>>?>(null)

    override fun connections(): List<ProviderConnection> {
        val cached = cache.get()
        if (cached != null && cached.first.plus(ttl).isAfter(now())) return cached.second
        val fresh = runCatching { load() }.getOrElse { e ->
            log.warn("LLM provider connections could not be read: {}", e.toString())
            cached?.second ?: emptyList()
        }
        cache.set(now() to fresh)
        return fresh
    }

    fun invalidate() = cache.set(null)

    private fun load(): List<ProviderConnection> = store.findAll().filter { it.enabled }.map { c ->
        ProviderConnection(c.key, c.provider, ProviderSettings(apiKey = open(c), baseUrl = c.baseUrl), ConnectionLimits(c.requestsPerMinute, c.requestsPerDay), c.ownerUserId)
    }

    private fun open(c: LlmProviderConnection): String? {
        val sealed = c.apiKey ?: return null
        val v = vault?.takeIf { it.configured }
        if (v == null) {
            log.warn("LLM provider connection {}: no secret key configured (BACKOFFICE_SECRETS_KEY), its API key cannot be read", c.key)
            return null
        }
        return runCatching { v.open(sealed, LlmProviderConnection.associatedData(c.id)) }
            .onFailure { log.warn("LLM provider connection {}: API key cannot be opened ({})", c.key, it.message) }
            .getOrNull()
    }
}

/** Where a provider endpoint shown in the UI comes from. */
enum class ConnectionSource { DATABASE, ENVIRONMENT }

data class LlmProviderView(
    val id: UUID?,
    val key: String?,
    val name: String,
    val provider: Provider,
    val baseUrl: String?,
    val hasApiKey: Boolean,
    val enabled: Boolean,
    /** Usable now: enabled, with what its type needs (key and / or base URL) and a readable key. */
    val usable: Boolean,
    val source: ConnectionSource,
    val updatedAt: Instant?,
    val updatedBy: Actor?,
    val requestsPerMinute: Int? = null,
    val requestsPerDay: Int? = null,
    /** Requests counted today (UTC) against [requestsPerDay]; null without limits. */
    val requestsToday: Int? = null,
    /** ADR 0050 step 2b: the caller's own connection (their API key), invisible to everybody else. */
    val personal: Boolean = false,
)

data class LlmProviderCommand(
    val key: String,
    val name: String,
    val provider: Provider,
    val baseUrl: String? = null,
    /** New API key; null keeps the stored one. */
    val apiKey: String? = null,
    /** Removes the stored API key (e.g. Ollama or a vLLM without authentication). */
    val clearApiKey: Boolean = false,
    val enabled: Boolean = true,
    val requestsPerMinute: Int? = null,
    val requestsPerDay: Int? = null,
    /** ADR 0050 step 2b: create the caller's own connection (`models.view`) instead of a platform one. Ignored on update. */
    val personal: Boolean = false,
)

/** One model a provider offers; prices in USD per million tokens when the provider publishes them (OpenRouter). */
data class ProviderModelInfo(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val inputPerMTok: java.math.BigDecimal? = null,
    val outputPerMTok: java.math.BigDecimal? = null,
    val free: Boolean = false,
)

data class ProviderModels(val ok: Boolean, val models: List<String>, val error: String?, val details: List<ProviderModelInfo> = emptyList())

/** The models of one usable endpoint (a connection, or an environment fallback), for the catalogue of the Models page. */
data class CatalogSection(
    val connection: String?,
    val name: String,
    val provider: Provider,
    val source: ConnectionSource,
    val ok: Boolean,
    val error: String?,
    val models: List<ProviderModelInfo>,
    /** ADR 0050 step 2b: the caller's own connection: profiles of its models are personal. */
    val personal: Boolean = false,
)

/**
 * ADR 0045: LLM provider connections managed in the UI (`admin.settings.manage`; listed with `models.view`). API keys
 * are sealed with the installation's secret key and never returned. Endpoints from environment variables are listed
 * read-only as the fallback they are. ADR 0050 step 2b: every user with `models.view` may add personal connections
 * (their own API keys); only the owner sees, changes and uses them, administrators included.
 */
class LlmProviderService(
    private val store: LlmProviderConnectionStore,
    private val vault: SecretVault?,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    private val cache: CachedProviderConnections,
    private val registry: ProviderRegistry,
    private val lister: ProviderModelLister,
    private val quota: ConnectionQuota = ConnectionQuota.NONE,
) {

    fun list(): List<LlmProviderView> {
        val user = gate.require(VIEW)
        val usable = cache.connections().map { it.key to it.provider }.filter { (key, provider) -> registry.resolve(provider, key) != null }.toSet()
        val today = quota.usedToday()
        val own = store.findAll().filter { it.visibleTo(user.userId?.value) }.map { c ->
            LlmProviderView(
                c.id, c.key, c.name, c.provider, c.baseUrl, c.apiKey != null, c.enabled, (c.key to c.provider) in usable, ConnectionSource.DATABASE,
                c.updatedAt, c.updatedBy, c.requestsPerMinute, c.requestsPerDay, today[c.key] ?: 0, c.personal,
            )
        }
        val env = Provider.entries.mapNotNull { p ->
            registry.environment(p)?.let { s ->
                LlmProviderView(null, null, "${p.name.lowercase()} (environment)", p, s.baseUrl, s.hasKey, true, true, ConnectionSource.ENVIRONMENT, null, null)
            }
        }
        return own + env
    }

    fun create(command: LlmProviderCommand): LlmProviderView {
        val user = gate.require(if (command.personal) VIEW else MANAGE)
        val actor = user.actor
        val owner = if (command.personal) user.userId?.value ?: throw ValidationException("a personal connection needs a signed-in user", "personal") else null
        val key = command.key.trim()
        rejectSubscriptionToken(command)
        if (store.findAll().any { it.key == key }) throw ValidationException("a provider connection $key already exists", "key")
        val id = UUID.randomUUID()
        val now = clock.now()
        val saved = store.save(
            LlmProviderConnection(
                id, key, command.name.trim(), command.provider, baseUrl(command), command.apiKey?.trim()?.ifEmpty { null }?.let { seal(id, it) },
                command.enabled, now, now, actor, command.requestsPerMinute, command.requestsPerDay, owner,
            ),
        )
        cache.invalidate()
        audit.record(AuditEntry("LLM_PROVIDER_CREATE", ENTITY, id.toString(), details = mapOf("key" to key, "provider" to saved.provider.name, "baseUrl" to saved.baseUrl, "apiKey" to (saved.apiKey != null), "requestsPerMinute" to saved.requestsPerMinute, "requestsPerDay" to saved.requestsPerDay, "personal" to saved.personal)))
        return view(saved)
    }

    fun update(id: UUID, command: LlmProviderCommand): LlmProviderView {
        val found = store.findById(id)
        val user = gate.require(if (found?.personal == true) VIEW else MANAGE)
        val actor = user.actor
        val existing = found?.takeIf { it.visibleTo(user.userId?.value) } ?: throw NotFoundException("LlmProviderConnection", id)
        if (command.key.trim() != existing.key) throw ValidationException("the key cannot change: model profiles refer to it", "key")
        if (command.provider != existing.provider) throw ValidationException("the provider type cannot change; add a new connection", "provider")
        rejectSubscriptionToken(command)
        val newKey = command.apiKey?.trim()?.ifEmpty { null }
        val apiKey = when {
            newKey != null -> seal(id, newKey)
            command.clearApiKey -> null
            else -> existing.apiKey
        }
        val saved = store.save(
            existing.copy(
                name = command.name.trim(), baseUrl = baseUrl(command), apiKey = apiKey, enabled = command.enabled, updatedAt = clock.now(), updatedBy = actor,
                requestsPerMinute = command.requestsPerMinute, requestsPerDay = command.requestsPerDay,
            ),
        )
        cache.invalidate()
        val changes = AuditableChanges.changed(
            "name" to (existing.name to saved.name),
            "baseUrl" to (existing.baseUrl to saved.baseUrl),
            "enabled" to (existing.enabled to saved.enabled),
            "requestsPerMinute" to (existing.requestsPerMinute to saved.requestsPerMinute),
            "requestsPerDay" to (existing.requestsPerDay to saved.requestsPerDay),
        ) + (if (newKey != null || (command.clearApiKey && existing.apiKey != null)) mapOf("apiKey" to if (newKey != null) "replaced" else "removed") else emptyMap())
        audit.record(AuditEntry("LLM_PROVIDER_UPDATE", ENTITY, id.toString(), details = changes + ("key" to saved.key)))
        return view(saved)
    }

    fun delete(id: UUID) {
        val found = store.findById(id)
        val user = gate.require(if (found?.personal == true) VIEW else MANAGE)
        val existing = found?.takeIf { it.visibleTo(user.userId?.value) } ?: throw NotFoundException("LlmProviderConnection", id)
        store.delete(id)
        cache.invalidate()
        audit.record(AuditEntry("LLM_PROVIDER_DELETE", ENTITY, id.toString(), details = mapOf("key" to existing.key, "provider" to existing.provider.name)))
    }

    private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Instant, ProviderModels>>()

    /**
     * The models every usable endpoint offers (`models.view`): the source of new model profiles. Cached per endpoint for
     * ten minutes; [refresh] asks the providers again.
     */
    fun catalog(refresh: Boolean): List<CatalogSection> {
        val user = gate.require(VIEW)
        val now = clock.now()
        val names = store.findAll().associate { it.key to it.name }
        // ADR 0050 step 2b: the caller's own connections are listed to them only
        val connections = cache.connections().filter { it.ownerUserId == null || it.ownerUserId == user.userId?.value }
        val endpoints = connections.mapNotNull { c ->
            val endpoint = registry.resolve(c.provider, c.key) ?: return@mapNotNull null
            Triple(endpoint, names[c.key] ?: c.key, ConnectionSource.DATABASE)
        } + Provider.entries.mapNotNull { p ->
            if (connections.any { it.provider == p }) return@mapNotNull null
            registry.resolve(p)?.takeIf { it.connection == null }?.let { Triple(it, "${p.name.lowercase()} (environment)", ConnectionSource.ENVIRONMENT) }
        }
        return endpoints.map { (endpoint, name, source) ->
            val cached = catalogCache[endpoint.fingerprint]?.takeIf { !refresh && it.first.plus(CATALOG_TTL).isAfter(now) }?.second
            val models = cached ?: lister.list(endpoint.provider, endpoint.settings).also { if (it.ok) catalogCache[endpoint.fingerprint] = now to it }
            CatalogSection(endpoint.connection, name, endpoint.provider, source, models.ok, models.error, models.details, personal = endpoint.ownerUserId != null)
        }
    }

    /** Lists the models the endpoint offers (also a connection test); [id] null = the environment endpoint of [provider]. */
    fun models(id: UUID?, provider: Provider?): ProviderModels {
        val found = id?.let { store.findById(it) }
        val user = gate.require(if (found?.personal == true) VIEW else MANAGE)
        val endpoint = if (id != null) {
            val c = found?.takeIf { it.visibleTo(user.userId?.value) } ?: throw NotFoundException("LlmProviderConnection", id)
            cache.invalidate()
            registry.resolve(c.provider, c.key)?.let { c.provider to it.settings }
                ?: return ProviderModels(false, emptyList(), if (!c.enabled) "the connection is disabled" else "the connection lacks an API key or base URL (or its key cannot be read)")
        } else {
            val p = provider ?: throw ValidationException("provider or id is required", "provider")
            registry.environment(p)?.let { p to it } ?: return ProviderModels(false, emptyList(), "no environment configuration for ${p.name.lowercase()}")
        }
        return lister.list(endpoint.first, endpoint.second)
    }

    /**
     * ADR 0048: a Claude subscription token (`claude setup-token`, `sk-ant-oat...`) works only inside Claude Code; the API
     * answers 401 "invalid x-api-key". Say so before it is stored.
     */
    private fun rejectSubscriptionToken(command: LlmProviderCommand) {
        if (command.provider == Provider.ANTHROPIC && command.apiKey?.trim()?.startsWith(SUBSCRIPTION_TOKEN_PREFIX) == true) {
            throw ValidationException(
                "this is a Claude subscription token for Claude Code, not an API key: create an API key in the Anthropic Console, " +
                    "or use your login through a personal runner and a Claude Code (your login) model profile",
                "apiKey",
            )
        }
    }

    private fun seal(id: UUID, apiKey: String): SealedValue {
        val v = vault?.takeIf { it.configured }
            ?: throw InvalidStateException("no secret key is configured on this server (BACKOFFICE_SECRETS_KEY): API keys cannot be stored")
        return v.seal(apiKey, LlmProviderConnection.associatedData(id))
    }

    private fun baseUrl(command: LlmProviderCommand): String? = command.baseUrl?.trim()?.trimEnd('/')?.ifEmpty { null }

    private fun view(c: LlmProviderConnection) = LlmProviderView(
        c.id, c.key, c.name, c.provider, c.baseUrl, c.apiKey != null, c.enabled, registry.resolve(c.provider, c.key) != null,
        ConnectionSource.DATABASE, c.updatedAt, c.updatedBy, c.requestsPerMinute, c.requestsPerDay, quota.usedToday()[c.key] ?: 0, c.personal,
    )

    companion object {
        const val ENTITY = "LlmProviderConnection"
        const val VIEW = "models.view"
        const val MANAGE = "admin.settings.manage"
        val CATALOG_TTL: Duration = Duration.ofMinutes(10)
        const val SUBSCRIPTION_TOKEN_PREFIX = "sk-ant-oat"
    }
}
