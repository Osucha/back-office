package pl.mos.backoffice.shared

import java.time.Instant

/** Time source port. Core code never calls `Instant.now()` directly so tests can control time. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val SYSTEM: Clock = Clock { Instant.now() }
        fun fixed(instant: Instant): Clock = Clock { instant }
    }
}
