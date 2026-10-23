package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.CiSignal
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId

/**
 * Outbound port to the durable graph engine (LangGraph4j in `projects-adapters`). Every call returns
 * immediately; execution happens on the engine's own threads and reports progress through
 * [pl.mos.backoffice.projects.application.port.in.RecordRunProgress].
 */
interface WorkflowEngine {
    /** Starts a `RUNNING` run from its START node. */
    fun start(run: WorkflowRun)

    /** Continues a run paused at a `HUMAN_GATE` with the owner's decision (or a synthetic `timeout`). */
    fun resumeWithDecision(run: WorkflowRun, decision: Decision)

    /** Continues a run paused at a `WAIT_FOR_CI` node with the received signal (or a synthetic timeout). */
    fun resumeWithSignal(run: WorkflowRun, signal: CiSignal?)

    /** Continues a previously `FAILED` run from [fromNodeId] on the same thread; [feedback] goes to the next task step. */
    fun retry(run: WorkflowRun, fromNodeId: String, feedback: String? = null)

    /** Best-effort stop of an in-flight run; the run itself is marked cancelled by the caller. */
    fun cancel(runId: WorkflowRunId)

    /** Re-attaches to every `RUNNING` run after a restart by resuming from its last checkpoint. */
    fun recoverAll(runs: List<WorkflowRun>)
}
