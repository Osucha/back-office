package pl.mos.backoffice.evaluation.application.port.out

import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkRequest

/**
 * Shape of a `BENCHMARK_ATTEMPT` work item (ADR 0023): `{attemptId}`, dedupe key `BENCHMARK_ATTEMPT:<id>`,
 * priority below workflow runs. Also the continuation of a CODING_JOB attempt's runner job.
 */
object AttemptWork {
    const val ATTEMPT_ID = "attemptId"
    const val PRIORITY = -1

    fun request(attemptId: AttemptId, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): WorkRequest = WorkRequest(
        kind = WorkKinds.BENCHMARK_ATTEMPT,
        payload = mapOf(ATTEMPT_ID to attemptId.toString()),
        dedupeKey = "${WorkKinds.BENCHMARK_ATTEMPT}:$attemptId",
        priority = PRIORITY,
        maxAttempts = maxAttempts,
    )

    /** Retry budget of 6 re-queues (ADR 0020) plus the final run without retry, plus one spare. */
    const val DEFAULT_MAX_ATTEMPTS = 9
}
