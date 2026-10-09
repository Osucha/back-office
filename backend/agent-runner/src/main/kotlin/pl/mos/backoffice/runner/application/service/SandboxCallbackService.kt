package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.SandboxCallbacks
import pl.mos.backoffice.runner.application.port.`in`.SandboxEvent
import pl.mos.backoffice.runner.application.port.`in`.UploadRequestInput
import pl.mos.backoffice.runner.application.port.`in`.UploadTargetView
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.ResultArchive
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.RunnerLogEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.platform.JobCredential
import pl.mos.backoffice.shared.platform.JobTokenScope
import pl.mos.backoffice.runner.domain.FleetTokens
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.security.SystemOperation
import java.math.RoundingMode
import java.util.UUID

/**
 * Calls made from inside the sandbox with its `bo_job_` token: log events, the agent's result and upload
 * URLs. The token is bound to one job: a token of another job is refused as unauthenticated. Only the full
 * job token (held by the runner entrypoint) may call them; the gateway-only agent token the coding agent gets
 * is refused, so a prompt-injected agent cannot post or forge the job's result (ADR 0029).
 *
 * Usage is **not** booked from the result: every model call went through the LLM gateway proxy, which
 * metered it into the ledger already. The reported (or catalogue-priced) cost is kept on the job for the
 * admin view only.
 */
class SandboxCallbackService(
    private val jobs: AgentJobStore,
    private val archive: ResultArchive,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
    private val events: EventPublisher,
    private val catalog: ModelCatalog?,
) : SandboxCallbacks {

    @SystemOperation("sandbox log events, authenticated by the job token")
    override fun appendEvents(credential: JobCredential, jobId: RunnerJobId, events: List<SandboxEvent>) {
        requireOwnJob(credential, jobId, JobTokenScope.FULL)
        val job = jobs.find(jobId) ?: throw NotFoundException("AgentJob", jobId)
        val now = lifecycle.now()
        val usage = job.spec.usageContext
        events.take(MAX_EVENTS_PER_CALL).forEach { event ->
            val at = event.at ?: now
            val progress = event.type.equals("PROGRESS", ignoreCase = true)
            val message = event.message.take(MAX_MESSAGE_CHARS)
            jobs.appendEvent(jobId, at, message, progress)
            this.events.publish(RunnerLogEvent(jobId, message, progress, at, usage.projectId, usage.runId))
        }
    }

    @SystemOperation("agent result, authenticated by the job token")
    override fun postResult(credential: JobCredential, jobId: RunnerJobId, result: RunnerResult) {
        requireOwnJob(credential, jobId, JobTokenScope.FULL)
        tx.inTransaction {
            val job = jobs.lock(jobId) ?: throw NotFoundException("AgentJob", jobId)
            if (!job.status.active) throw InvalidStateException("agent job $jobId is ${job.status}")
            if (job.hasResult) throw InvalidStateException("agent job $jobId already has a result")
            val blobKey = if (archive.sizeOf(result) > lifecycle.settings.inlineResultMaxBytes) archive.store(jobId, result) else null
            val inline = if (blobKey == null) result else result.copy(diff = null, artifacts = result.artifacts.map { it.copy(content = "") })
            jobs.saveResult(jobId, inline, blobKey)
            val cost = result.reportedCostUsd ?: catalog?.findPricing(job.spec.model)?.costOf(result.usage)?.amount
            jobs.update(job.copy(hasResult = true, costUsd = cost?.setScale(6, RoundingMode.HALF_UP)))
        }
    }

    @SystemOperation("presigned upload URL, authenticated by the job token")
    override fun createUpload(credential: JobCredential, jobId: RunnerJobId, request: UploadRequestInput): UploadTargetView {
        requireOwnJob(credential, jobId, JobTokenScope.FULL)
        if (request.sizeBytes <= 0 || request.sizeBytes > lifecycle.settings.maxUploadBytes) {
            throw ValidationException("sizeBytes must be between 1 and ${lifecycle.settings.maxUploadBytes}", "sizeBytes")
        }
        val name = request.name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(MAX_NAME_CHARS).ifEmpty { "upload" }
        val key = "agent-jobs/$jobId/uploads/${UUID.randomUUID().toString().take(8)}-$name"
        val url = archive.presignUpload(key, request.contentType, request.sizeBytes, lifecycle.settings.uploadUrlTtl)
            ?: throw IntegrationException("blob-store", "object storage is not configured; uploads are unavailable")
        return UploadTargetView(url.url, url.method, url.headers, key, url.expiresAt)
    }

    @SystemOperation("gateway-only agent token, authenticated by the full job token")
    override fun issueAgentToken(credential: JobCredential, jobId: RunnerJobId): String {
        requireOwnJob(credential, jobId, JobTokenScope.FULL)
        return tx.inTransaction {
            val job = jobs.lock(jobId) ?: throw NotFoundException("AgentJob", jobId)
            if (!job.status.active) throw InvalidStateException("agent job $jobId is ${job.status}")
            val token = FleetTokens.generate(FleetTokens.JOB_PREFIX)
            if (!jobs.setAgentTokenHash(jobId, token.hash)) throw InvalidStateException("an agent token was already issued for this lease of job $jobId")
            token.plaintext
        }
    }

    private fun requireOwnJob(credential: JobCredential, jobId: RunnerJobId, scope: JobTokenScope) {
        if (credential.jobId != jobId.value) throw RunnerAuthenticationException("the job token belongs to another job")
        if (credential.scope != scope) throw RunnerAuthenticationException("the agent token only reaches the LLM gateway; this call needs the job token of the runner")
    }

    companion object {
        const val MAX_EVENTS_PER_CALL = 500
        const val MAX_MESSAGE_CHARS = 8000
        const val MAX_NAME_CHARS = 120
    }
}
