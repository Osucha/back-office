package pl.mos.backoffice.app.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.mos.backoffice.shared.Clock

/**
 * Cross-cutting beans shared by every context: the clock. The domain event publisher comes from
 * `platform-runtime` (ADR 0023): events go to the `event_log` outbox, are announced with `NOTIFY` and reach the
 * SSE subscribers of every replica; they are also re-published as Spring application events in-process.
 */
@Configuration
class CoreBeans {

    @Bean
    fun clock(): Clock = Clock.SYSTEM
}
