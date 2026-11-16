package pl.mos.backoffice.identity.adapter.out.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.osucha.portpermissions.PermissionCatalog
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import pl.mos.backoffice.identity.application.port.`in`.system.LoadCurrentUser
import pl.mos.backoffice.identity.application.port.out.AccessVersion
import pl.mos.backoffice.identity.application.port.out.SessionSummary
import pl.mos.backoffice.identity.application.port.out.SessionTerminator
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.CurrentUserAccessGuard
import pl.mos.backoffice.shared.security.UserId
import java.security.MessageDigest
import java.time.Duration
import java.util.Optional

/** How the request was authenticated. */
enum class AuthenticationSource { SESSION, PERSONAL_TOKEN, CI_TOKEN }

/**
 * The per-request authentication: a [CurrentUser] with its effective permissions. It is built by the
 * principal filter for every request and never stored in the session (the session holds only the user id).
 * [accessVersion] is the version of the user's access the permissions were resolved under (ADR 0068; null for CI
 * tokens): `GET /me` returns it and every API response carries it as `X-Access-Version`.
 */
class CurrentUserAuthentication(
    val currentUser: CurrentUser,
    val source: AuthenticationSource,
    val accessVersion: Long? = null,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = currentUser
    override fun getName(): String = currentUser.userId?.toString() ?: currentUser.login
}

/**
 * The shared `AccessGuard` and `CurrentPrincipal` (ADR 0017, ADR 0066) over the Spring Security context.
 * `asSystem` uses a thread-local override, so it works on any thread (engine, schedulers, seeding) with or
 * without an HTTP request, but only on the thread that called it: work handed to another thread must call
 * `asSystem` there. The SYSTEM principal holds no permissions; it only passes `requireSystem()` (ADR 0067).
 */
class SecurityContextAccessGuard(catalog: PermissionCatalog) : CurrentUserAccessGuard(catalog) {

    private val override = ThreadLocal<CurrentUser?>()

    /** The caller, or `null` when nobody is authenticated (used by the audit log and the 403 handler). */
    fun currentUserOrNull(): CurrentUser? =
        override.get() ?: (SecurityContextHolder.getContext().authentication as? CurrentUserAuthentication)?.currentUser

    override fun currentUser(): CurrentUser =
        currentUserOrNull() ?: throw AuthenticationCredentialsNotFoundException("authentication required")

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

/** A user's effective permissions and the access version they were resolved under. */
data class ResolvedUser(val user: CurrentUser, val accessVersion: Long)

/**
 * Effective permissions of users (session and personal-token owners), cached in memory for [ttl] and keyed by
 * (user, the user's access version, ADR 0068): a change of that user's access or of everyone's moves the version, so
 * the next request resolves again (ADR 0017, principle 6), while changes of other users leave the entry alone. The
 * version is read before resolving: a change made meanwhile moves it again and the next request resolves once more.
 */
class CurrentUserCache(
    private val loader: LoadCurrentUser,
    private val version: AccessVersion,
    ttl: Duration = Duration.ofSeconds(30),
) {
    private val cache: Cache<Pair<UserId, Long>, Optional<CurrentUser>> =
        Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build()

    fun resolve(userId: UserId): ResolvedUser? {
        val current = version.current(userId)
        return cache.get(userId to current) { Optional.ofNullable(loader.forUser(userId)) }.orElse(null)?.let { ResolvedUser(it, current) }
    }

    fun get(userId: UserId): CurrentUser? = resolve(userId)?.user
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
