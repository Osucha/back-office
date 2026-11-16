package pl.mos.backoffice.runner.adapter.`in`.runner

import pl.mos.backoffice.runner.GatewayJson
import pl.mos.backoffice.runner.SandboxPolicyJson
import pl.mos.backoffice.runner.application.port.`in`.system.GatewayUrls
import java.time.Instant
import java.util.UUID

/* REST documents of the tag `runner-v2` in docs/api/openapi.yaml (runner-controllers and sandboxes). */

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

/** ADR 0051: `PUT /runner/v2/key`. */
data class RunnerPublicKeyJson(val publicKey: String)

/** ADR 0051: answer of `POST /runner/v2/jobs/{jobId}/credentials`; [expiresAt] is part of every token's associated data. */
data class JobCredentialsJson(val expiresAt: String, val credentials: List<SealedCredentialJson>)

data class SealedCredentialJson(
    val ref: String,
    val repository: String,
    val capability: String,
    val enc: String,
    val ciphertext: String,
    /** ADR 0064: the connector key of a `CONNECTOR` secret; absent otherwise. */
    val connector: String? = null,
)

fun GatewayUrls.toJson() = GatewayJson(anthropicBaseUrl, openaiBaseUrl, apiBaseUrl)
