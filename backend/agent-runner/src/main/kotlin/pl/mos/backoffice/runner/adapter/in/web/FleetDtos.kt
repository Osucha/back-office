package pl.mos.backoffice.runner.adapter.`in`.web

import pl.mos.backoffice.runner.GatewayJson
import pl.mos.backoffice.runner.ModelRefJson
import pl.mos.backoffice.runner.SandboxPolicyJson
import pl.mos.backoffice.runner.application.port.`in`.AgentJobView
import pl.mos.backoffice.runner.application.port.`in`.GatewayUrls
import pl.mos.backoffice.runner.application.port.`in`.PersonalRunnerView
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolInput
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolView
import pl.mos.backoffice.runner.application.port.`in`.RunnerView
import pl.mos.backoffice.runner.application.port.`in`.SandboxPolicyView
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/* REST documents of the tags `runner-v2` and `fleet` in docs/api/openapi.yaml. */

data class RunnerCapabilitiesJson(val backends: List<String> = emptyList(), val runtimeClasses: List<String> = emptyList(), val maxConcurrent: Int? = null)

data class RunnerRegistrationJson(
    val registrationToken: String = "",
    val name: String = "",
    val version: String = "",
    val cluster: String? = null,
    val capabilities: RunnerCapabilitiesJson = RunnerCapabilitiesJson(),
)

data class RunnerRegisteredJson(val runnerId: UUID, val runnerToken: String, val poolId: String)

data class RunnerConfigJson(
    val poolId: String,
    val ceiling: SandboxPolicyJson,
    val allowedImages: List<String>,
    val warmPoolSize: Int,
    val gateway: GatewayJson,
    val signingKeys: List<Map<String, Any?>>,
)

data class LeaseRequestJson(
    val maxJobs: Int? = null,
    val waitSeconds: Int? = null,
    val freeSlots: Int? = null,
    /** ADR 0050 step 3: the runner's coding agents and whether a Claude login is set (null = not reported). */
    val harnesses: List<String>? = null,
    val claudeLogin: Boolean? = null,
)

/**
 * openapi `AgentJobAssignment`. `spec` and `policy` are canonical JSON trees (keys sorted, no nulls) so
 * `specDigest = sha256(canonical({"policy":…,"spec":…}))` can be recomputed by the controller from what it
 * received; `signature` is Ed25519 over `jobId|specDigest|leaseExpiresAt` (the string as sent).
 */
data class AgentJobAssignmentJson(
    val jobId: UUID,
    val leaseExpiresAt: String,
    val jobToken: String,
    val spec: Any?,
    val policy: Any?,
    val specDigest: String,
    val signature: String?,
    val keyId: String?,
)

data class JobHeartbeatJson(
    val phase: String? = null,
    val sandboxName: String? = null,
    val node: String? = null,
    val cpuMillis: Long? = null,
    val memoryBytes: Long? = null,
)

data class JobHeartbeatResponseJson(val leaseExpiresAt: Instant, val cancelRequested: Boolean)

data class UploadRequestJson(val name: String = "", val contentType: String = "application/octet-stream", val sizeBytes: Long = 0, val sha256: String? = null)

data class UploadTargetJson(val url: String, val method: String, val headers: Map<String, String>, val blobKey: String, val expiresAt: Instant)

data class QueueDepthJson(val queued: Int, val running: Int)

/** openapi `WorkspaceSync` (ADR 0044): the workspaces a runner-controller holds. */
data class WorkspaceSyncJson(val held: List<String> = emptyList())

/** openapi `WorkspaceSyncResult`: the workspaces to delete. */
data class WorkspaceSyncResultJson(val release: List<String>)

data class RunnerPoolInputJson(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val labels: Map<String, String> = emptyMap(),
    val ceiling: SandboxPolicyJson? = null,
    val allowedImages: List<String> = emptyList(),
    val warmPoolSize: Int = 2,
    val maxConcurrentJobs: Int = 10,
    val projectIds: List<UUID> = emptyList(),
) {
    fun toInput() = RunnerPoolInput(id, name, description, labels, ceiling?.toPolicy() ?: pl.mos.backoffice.shared.runner.SandboxPolicy.DEFAULT, allowedImages, warmPoolSize, maxConcurrentJobs, projectIds.toSet())
}

data class RunnerPoolJson(
    val id: String,
    val name: String,
    val description: String?,
    val labels: Map<String, String>,
    val ceiling: SandboxPolicyJson,
    val allowedImages: List<String>,
    val warmPoolSize: Int,
    val maxConcurrentJobs: Int,
    val projectIds: List<UUID>,
    val runners: Int,
    val onlineRunners: Int,
    val queued: Int,
    val running: Int,
    val createdAt: Instant,
    /** ADR 0040: owner of a personal pool; null = shared pool. */
    val ownerUserId: UUID? = null,
    val llmCredentials: String = "GATEWAY",
) {
    companion object {
        fun of(v: RunnerPoolView) = RunnerPoolJson(
            v.input.id, v.input.name, v.input.description, v.input.labels, SandboxPolicyJson.of(v.input.ceiling), v.input.allowedImages,
            v.input.warmPoolSize, v.input.maxConcurrentJobs, v.input.projectIds.sorted(), v.runners, v.onlineRunners, v.queued, v.running, v.createdAt,
            v.ownerUserId, v.llmCredentials.name,
        )
    }
}

data class RegistrationTokenJson(val token: String, val expiresAt: Instant, val helmValues: String)

/** openapi `PersonalRunner` (ADR 0040). */
data class PersonalRunnerJson(
    val poolId: String,
    val llmCredentials: String,
    val runners: List<RunnerJson>,
    val queued: Int,
    val running: Int,
    val createdAt: Instant,
) {
    companion object {
        fun of(v: PersonalRunnerView) = PersonalRunnerJson(v.poolId, v.llmCredentials.name, v.runners.map(RunnerJson::of), v.queued, v.running, v.createdAt)
    }
}

data class PersonalRunnerInputJson(val llmCredentials: String = "LOCAL_LOGIN")

data class PersonalRegistrationTokenJson(val token: String, val expiresAt: Instant, val dockerCommand: String)

data class RunnerJson(
    val id: UUID,
    val poolId: String,
    val name: String,
    val version: String,
    val backends: List<String>,
    val runtimeClasses: List<String>,
    val maxConcurrent: Int,
    val running: Int,
    val lastSeenAt: Instant,
    val online: Boolean,
    val cluster: String?,
    val registeredAt: Instant,
    val revokedAt: Instant?,
) {
    companion object {
        fun of(v: RunnerView) = RunnerJson(
            v.id, v.poolId, v.name, v.version, v.backends, v.runtimeClasses, v.maxConcurrent, v.running, v.lastSeenAt, v.online, v.cluster, v.registeredAt, v.revokedAt,
        )
    }
}

data class MoneyJson(val amount: BigDecimal, val currency: String)

data class AgentJobJson(
    val id: UUID,
    val label: String,
    val poolId: String?,
    val runnerId: UUID?,
    val status: String,
    val mode: String,
    val projectId: UUID?,
    val runId: UUID?,
    val nodeId: String?,
    val benchmarkId: UUID?,
    val model: ModelRefJson,
    val isolation: String?,
    val policy: SandboxPolicyJson?,
    val policySources: Map<String, String>,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val reason: String?,
    val cost: MoneyJson,
    val egressDenied: List<String>,
    val cancelRequested: Boolean,
    val leaseExpiresAt: Instant?,
    val attempts: Int,
    val priority: Int,
    val gitAccess: String,
) {
    companion object {
        fun of(v: AgentJobView) = AgentJobJson(
            v.id.value, v.label, v.poolId, v.runnerId, v.status.name, v.mode, v.projectId, v.runId, v.nodeId, v.benchmarkId, ModelRefJson.of(v.model),
            v.isolation?.name, v.policy?.let(SandboxPolicyJson::of), v.policySources, v.queuedAt, v.startedAt, v.finishedAt, v.reason,
            MoneyJson(v.cost.amount, v.cost.currency), v.egressDenied, v.cancelRequested, v.leaseExpiresAt, v.attempts, v.priority,
            v.gitAccess.name,
        )
    }
}

data class AgentJobPageJson(val content: List<AgentJobJson>, val page: Int, val size: Int, val totalElements: Long)

data class SandboxPolicyViewJson(
    val project: SandboxPolicyJson,
    val effective: SandboxPolicyJson,
    val ceiling: SandboxPolicyJson,
    val sources: Map<String, String>,
    val poolId: String?,
    val warnings: List<String>,
) {
    companion object {
        fun of(v: SandboxPolicyView) = SandboxPolicyViewJson(
            SandboxPolicyJson.of(v.project), SandboxPolicyJson.of(v.effective), SandboxPolicyJson.of(v.ceiling), v.sources, v.poolId, v.warnings,
        )
    }
}

fun GatewayUrls.toJson() = GatewayJson(anthropicBaseUrl, openaiBaseUrl, apiBaseUrl)

/** ADR 0051: `PUT /runner/v2/key`. */
data class RunnerPublicKeyJson(val publicKey: String)

/** ADR 0051: answer of `POST /runner/v2/jobs/{jobId}/credentials`; [expiresAt] is part of every token's associated data. */
data class JobCredentialsJson(val expiresAt: String, val credentials: List<SealedCredentialJson>)

data class SealedCredentialJson(val ref: String, val repository: String, val capability: String, val enc: String, val ciphertext: String)
