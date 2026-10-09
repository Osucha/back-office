package pl.mos.backoffice.identity.adapter.out.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import pl.mos.backoffice.identity.application.port.`in`.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.PermissionCatalog
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.out.SessionTerminator
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import java.security.MessageDigest
import java.time.Duration
import java.util.Optional
import java.util.UUID

/** How the request was authenticated. */
enum class AuthenticationSource { SESSION, PERSONAL_TOKEN, CI_TOKEN }

/**
 * The per-request authentication: a [CurrentUser] with its effective permissions. It is built by the
 * principal filter for every request and never stored in the session (the session holds only the user id).
 */
class CurrentUserAuthentication(val currentUser: CurrentUser, val source: AuthenticationSource) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = currentUser
    override fun getName(): String = currentUser.userId?.toString() ?: currentUser.login
}

/**
 * The shared [AuthorizationGate] (ADR 0017) over the Spring Security context. `asSystem` uses a thread-local
 * override, so it works on any thread (engine, schedulers, seeding) with or without an HTTP request.
 * The SYSTEM principal passes every check.
 */
class SecurityContextAuthorizationGate : AuthorizationGate {

    private val override = ThreadLocal<CurrentUser?>()

    /** The caller, or `null` when nobody is authenticated (used by the audit log and the 403 handler). */
    fun currentUserOrNull(): CurrentUser? =
        override.get() ?: (SecurityContextHolder.getContext().authentication as? CurrentUserAuthentication)?.currentUser

    override fun currentUser(): CurrentUser =
        currentUserOrNull() ?: throw AuthenticationCredentialsNotFoundException("authentication required")

    override fun require(permission: String): CurrentUser {
        val user = currentUser()
        if (user.kind != PrincipalKind.SYSTEM && !user.can(permission)) throw InsufficientPermissionException(permission)
        return user
    }

    override fun require(permission: String, projectId: UUID): CurrentUser {
        val user = currentUser()
        if (user.kind != PrincipalKind.SYSTEM && !user.can(permission, projectId)) throw InsufficientPermissionException(permission, projectId)
        return user
    }

    override fun <T> asSystem(reason: String, block: () -> T): T = runAs(CurrentUser.SYSTEM, block)

    /** Runs [block] as [user] on this thread (tests, internal adapters). */
    fun <T> runAs(user: CurrentUser, block: () -> T): T {
        val previous = override.get()
        override.set(user)
        try {
            return block()
        } finally {
            if (previous == null) override.remove() else override.set(previous)
        }
    }

    fun actorOrNull(): Actor? = currentUserOrNull()?.actor
}

/**
 * Effective permissions of session users, cached for [ttl] and keyed by (user, access version): every access
 * change increments the version, so the next request sees the change (ADR 0017, principle 6).
 */
class CurrentUserCache(
    private val loader: LoadCurrentUser,
    private val version: AccessVersion,
    ttl: Duration = Duration.ofSeconds(30),
) {
    private val cache: Cache<Pair<UserId, Long>, Optional<CurrentUser>> =
        Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build()

    fun get(userId: UserId): CurrentUser? =
        cache.get(userId to version.current()) { Optional.ofNullable(loader.forUser(userId)) }.orElse(null)

    fun invalidateAll() = cache.invalidateAll()
}

/** Collects every module's [PermissionCatalogContribution]; duplicate keys with different definitions fail startup. */
class PermissionCatalogCollector(contributions: List<PermissionCatalogContribution>) : PermissionCatalog {

    private val definitions: List<PermissionDefinition> = contributions.flatMap { it.permissions() }.let { all ->
        val conflicts = all.groupBy { it.key }.filterValues { defs -> defs.map { it.scope to it.module }.distinct().size > 1 }
        check(conflicts.isEmpty()) { "permission keys contributed with different scopes or modules: ${conflicts.keys}" }
        all.distinctBy { it.key }.sortedBy { it.key }
    }

    override fun definitions(): List<PermissionDefinition> = definitions
}

/**
 * [SessionTerminator] over Spring Session. The principal-name index holds the user id string (set at login
 * as `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`).
 */
class SpringSessionTerminator(private val sessions: FindByIndexNameSessionRepository<out Session>) : SessionTerminator {

    override fun sessionsOf(userId: UserId): List<SessionSummary> =
        sessions.findByPrincipalName(userId.toString()).values.map {
            SessionSummary(
                id = hashId(it.id),
                createdAt = it.creationTime,
                lastAccessAt = it.lastAccessedTime,
                sourceIp = it.getAttribute<String>(SOURCE_IP_ATTRIBUTE),
                userAgent = it.getAttribute<String>(USER_AGENT_ATTRIBUTE),
            )
        }.sortedByDescending { it.lastAccessAt }

    override fun terminateAll(userId: UserId): Int {
        val ids = sessions.findByPrincipalName(userId.toString()).keys
        ids.forEach { sessions.deleteById(it) }
        return ids.size
    }

    override fun terminateOthers(userId: UserId, keepSessionId: String?): Int {
        val ids = sessions.findByPrincipalName(userId.toString()).keys.filter { it != keepSessionId }
        ids.forEach { sessions.deleteById(it) }
        return ids.size
    }

    companion object {
        const val SOURCE_IP_ATTRIBUTE = "BO_SOURCE_IP"
        const val USER_AGENT_ATTRIBUTE = "BO_USER_AGENT"

        /** Session ids are credentials: only a hash leaves the server. */
        fun hashId(id: String): String =
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
