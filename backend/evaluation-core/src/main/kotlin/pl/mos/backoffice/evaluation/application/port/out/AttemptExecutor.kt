package pl.mos.backoffice.evaluation.application.port.out

import pl.mos.backoffice.evaluation.domain.model.AttemptId

/**
 * Outbound port: run an attempt asynchronously. The adapter owns threading and per-provider
 * concurrency limits and calls back into `ExecuteAttempt` on its own thread.
 */
fun interface AttemptExecutor {
    fun submit(attemptId: AttemptId)
}
