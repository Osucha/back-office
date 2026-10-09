package pl.mos.backoffice.evaluation.adapter.config

import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.evaluation.application.port.`in`.EvaluationDashboardQuery
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites
import pl.mos.backoffice.evaluation.application.port.`in`.RecoverBenchmarks
import pl.mos.backoffice.evaluation.application.service.BenchmarkService
import pl.mos.backoffice.shared.ModelProfileId

/*
 * Transaction boundaries of the evaluation use cases. The core services are plain Kotlin; these
 * delegating wrappers run each call in one transaction so a change and its audit entry (`AuditPort`
 * writes in the caller's transaction, ADR 0018) commit or roll back together. Attempts submitted to the
 * executor inside such a transaction are dispatched after commit (see AttemptExecutorAdapter).
 */

@Transactional
class TransactionalManageModelProfiles(private val delegate: ManageModelProfiles) : ManageModelProfiles by delegate {
    /** The smoke test waits for a provider; it must not hold a database transaction meanwhile. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun test(id: ModelProfileId): ManageModelProfiles.ModelTestResult = delegate.test(id)
}

@Transactional
class TransactionalManageTaskSuites(delegate: ManageTaskSuites) : ManageTaskSuites by delegate

/** One bean serving `ManageBenchmarks`, `RecoverBenchmarks` and `EvaluationDashboardQuery`. */
@Transactional
class TransactionalBenchmarks(delegate: BenchmarkService) :
    ManageBenchmarks by delegate,
    RecoverBenchmarks by delegate,
    EvaluationDashboardQuery by delegate
