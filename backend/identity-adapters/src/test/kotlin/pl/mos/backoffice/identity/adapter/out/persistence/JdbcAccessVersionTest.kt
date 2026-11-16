package pl.mos.backoffice.identity.adapter.out.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.adapter.out.security.CurrentUserCache
import pl.mos.backoffice.identity.application.port.`in`.system.LoadCurrentUser
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.shared.security.CiTokenIdentity
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.UserId
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR 0068 against PostgreSQL: access versions per user plus a global component, applied on this instance after
 * commit and on other instances through the cluster signal (or, when a signal is lost, through the poll).
 */
@SpringBootTest
@Import(PostgresTestSupport::class)
class JdbcAccessVersionTest {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var tm: PlatformTransactionManager

    private val tx by lazy { TransactionTemplate(tm) }

    /** A version source that polls only at its first read (then: signals and its own bumps). */
    private fun instance(signals: ClusterSignals? = null, refreshMillis: Long = 60_000) = JdbcAccessVersion(jdbc, refreshMillis, signals)

    @Test
    fun `a user bump moves only that user's version, a global bump moves everyone's`() {
        val versions = instance()
        val alice = UserId.new()
        val bob = UserId.new()
        val alice0 = versions.current(alice)
        val bob0 = versions.current(bob)

        tx.executeWithoutResult { versions.userChanged(alice) }
        assertThat(versions.current(alice)).isEqualTo(alice0 + 1)
        assertThat(versions.current(bob)).isEqualTo(bob0)
        // the library port reads the same value by principal id
        assertThat(versions.current(alice.toString())).isEqualTo(alice0 + 1)

        tx.executeWithoutResult { versions.everyoneChanged() }
        assertThat(versions.current(alice)).isEqualTo(alice0 + 2)
        assertThat(versions.current(bob)).isEqualTo(bob0 + 1)
    }

    @Test
    fun `a rolled back change moves nothing, and a change is applied only after commit`() {
        val versions = instance()
        val alice = UserId.new()
        val before = versions.current(alice)
        var during = -1L
        runCatching {
            tx.executeWithoutResult {
                versions.userChanged(alice)
                during = versions.current(alice)
                error("rollback")
            }
        }
        // inside the transaction nobody may cache data under the new version yet
        assertThat(during).isEqualTo(before)
        assertThat(versions.current(alice)).isEqualTo(before)
        assertThat(instance().current(alice)).isEqualTo(before)
    }

    @Test
    fun `another instance sees a change through the signal at its next request, other users untouched`() {
        val bus = ClusterSignals.local()
        val a = instance(bus)
        val b = instance(bus)
        val alice = UserId.new()
        val bob = UserId.new()
        val alice0 = b.current(alice)
        val bob0 = b.current(bob)

        tx.executeWithoutResult { a.userChanged(alice) }
        assertThat(b.current(alice)).isEqualTo(alice0 + 1)
        assertThat(b.current(bob)).isEqualTo(bob0)

        tx.executeWithoutResult { a.everyoneChanged() }
        assertThat(b.current(bob)).isEqualTo(bob0 + 1)
        assertThat(b.current(alice)).isEqualTo(a.current(alice))
    }

    @Test
    fun `without a signal another instance catches up through its poll`() {
        val a = instance()
        val b = instance(refreshMillis = 0)
        val alice = UserId.new()
        val alice0 = b.current(alice)
        b.current(alice) // first poll: remembers where to look from

        tx.executeWithoutResult { a.userChanged(alice) }
        tx.executeWithoutResult { a.everyoneChanged() }
        Thread.sleep(5)
        assertThat(b.current(alice)).isEqualTo(alice0 + 2)
    }

    @Test
    fun `the cache resolves again only for the user whose access changed`() {
        val versions = instance()
        val loads = ConcurrentHashMap<UserId, Int>()
        val loader = object : LoadCurrentUser {
            override fun forUser(userId: UserId): CurrentUser {
                loads.merge(userId, 1, Int::plus)
                return CurrentUser(PrincipalKind.USER, userId, "$userId@example.com", "user", emptySet(), emptyMap())
            }
            override fun verifyPersonalToken(plaintext: String): PersonalAccessToken? = null
            override fun forPersonalToken(owner: CurrentUser, token: PersonalAccessToken) = owner
            override fun forCiToken(identity: CiTokenIdentity) = error("not used")
        }
        val cache = CurrentUserCache(loader, versions)
        val alice = UserId.new()
        val bob = UserId.new()
        val aliceBefore = cache.resolve(alice)!!.accessVersion
        cache.resolve(bob)
        cache.resolve(alice)
        assertThat(loads).containsEntry(alice, 1).containsEntry(bob, 1)

        tx.executeWithoutResult { versions.userChanged(alice) }
        assertThat(cache.resolve(alice)!!.accessVersion).isEqualTo(aliceBefore + 1)
        cache.resolve(bob)
        assertThat(loads).containsEntry(alice, 2).containsEntry(bob, 1)

        tx.executeWithoutResult { versions.everyoneChanged() }
        cache.resolve(alice)
        cache.resolve(bob)
        assertThat(loads).containsEntry(alice, 3).containsEntry(bob, 2)
    }
}
