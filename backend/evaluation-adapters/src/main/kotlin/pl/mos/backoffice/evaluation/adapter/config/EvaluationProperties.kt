package pl.mos.backoffice.evaluation.adapter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `backoffice.evaluation.*` settings. */
@ConfigurationProperties(prefix = "backoffice.evaluation")
data class EvaluationProperties(
    /** Deprecated (ADR 0023): provider concurrency now comes from the distributed provider rate limiter; ignored. */
    val concurrencyPerProvider: Int = 2,
    /** Create the dated model seed and the starter suite when the catalogue is empty. */
    val seedStarterContent: Boolean = true,
    /** Re-queue in-flight attempts of running benchmarks after a restart. */
    val recoverOnStartup: Boolean = true,
    /** Re-queueing of attempts that hit a retryable provider error, e.g. the rate limiter (ADR 0020). */
    val retry: Retry = Retry(),
) {
    data class Retry(
        /** Re-queues per attempt before a retryable error fails it. */
        val maxAttempts: Int = 6,
        /** Delay before the first re-queue; doubled on each further one. */
        val initialBackoff: Duration = Duration.ofSeconds(15),
        /** Upper bound of the delay between re-queues. */
        val maxBackoff: Duration = Duration.ofMinutes(5),
    )
}
