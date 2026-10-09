package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.SignalRun
import pl.mos.backoffice.projects.application.port.`in`.SignalRun.SignalCommand
import pl.mos.backoffice.projects.application.port.`in`.SignalRun.SignalResult
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.event.CiSignalReceived
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.CiSignalStatus
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.CiSignalMatcher
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate

/**
 * Implements [SignalRun] (docs/architecture/03-pipelines-and-agents.md): a CI token signal (`ci.signal`)
 * is matched to the run waiting on it by [CiSignalMatcher] and resumes it; a manual signal from the UI
 * (`run.signal`) targets one run. Both are audited (`CI_SIGNAL`, `RUN_SIGNAL_MANUAL`).
 */
class SignalService(
    private val runs: WorkflowRunRepository,
    private val engine: WorkflowEngine,
    private val events: EventPublisher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : SignalRun {

    override fun signal(command: SignalCommand): SignalResult {
        gate.require(ProjectsPermissions.CI_SIGNAL, command.projectId.value)
        val result = deliver(command)
        audit.record(
            AuditEntry(
                "CI_SIGNAL", ENTITY, result.runId?.toString(), projectId = command.projectId.value,
                details = mapOf("signal" to command.signal, "status" to command.status.name, "resumed" to result.resumed, "message" to result.message),
            ),
        )
        return result
    }

    override fun signalRun(runId: WorkflowRunId, signal: String, status: CiSignalStatus, message: String?): WorkflowRun {
        val run = runs.findById(runId) ?: throw NotFoundException("Run", runId)
        gate.require(ProjectsPermissions.RUN_SIGNAL, run.projectId.value)
        val result = deliver(SignalCommand(run.projectId, signal, status, runId = runId, message = message, context = CiContext()))
        if (!result.resumed) throw InvalidStateException(result.message)
        audit.record(
            AuditEntry("RUN_SIGNAL_MANUAL", ENTITY, runId.toString(), projectId = run.projectId.value, details = mapOf("signal" to signal, "status" to status.name)),
        )
        return runs.findById(runId) ?: run
    }

    private fun deliver(command: SignalCommand): SignalResult {
        val now = clock.now()
        val byId = command.runId?.let { runs.findById(it) }
        val waiting = if (command.runId == null) runs.findWaiting(command.projectId) else emptyList()
        val match = CiSignalMatcher.match(command.projectId, command.signal, command.runId, command.branch, byId, waiting)
        val run = match.run
        if (run == null) {
            events.publish(CiSignalReceived(null, command.projectId, command.signal, command.status.name, false, now))
            return SignalResult(null, false, match.message)
        }
        val signal = CiSignal(command.signal, command.status, command.message, command.context, now)
        val resumed = runs.save(run.resumedWithSignal(signal, now))
        events.publish(CiSignalReceived(resumed.id, resumed.projectId, command.signal, command.status.name, true, now))
        engine.resumeWithSignal(resumed, signal)
        return SignalResult(resumed.id, true, "resumed run ${resumed.id} waiting at node ${run.waitingOn?.nodeId}")
    }

    companion object {
        const val ENTITY = "WorkflowRun"
    }
}
