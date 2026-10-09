package pl.mos.backoffice.runnercontroller.protocol

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/*
 * Runner protocol v2 message shapes (docs/api/openapi.yaml, tag `runner-v2`). Kept local on purpose: the
 * controller is released independently of Back Office and must tolerate unknown fields (the mapper does not
 * fail on them). `spec` and `policy` of an assignment are kept as JSON trees so the digest is computed over
 * exactly what Back Office signed and the spec is forwarded to the sandbox unchanged.
 */

enum class BackendKind { AGENT_SANDBOX, K8S_JOB, DOCKER }

data class RunnerCapabilities(
    val backends: List<BackendKind>,
    val runtimeClasses: List<String>,
    val maxConcurrent: Int,
)

data class RunnerRegistration(
    val registrationToken: String,
    val name: String,
    val version: String,
    val cluster: String?,
    val capabilities: RunnerCapabilities,
)

data class RunnerRegistered(
    val runnerId: String? = null,
    val runnerToken: String = "",
    val poolId: String? = null,
)

data class GatewayUrls(
    val anthropicBaseUrl: String? = null,
    val openaiBaseUrl: String? = null,
    val apiBaseUrl: String? = null,
)

data class RunnerConfig(
    val poolId: String? = null,
    val ceiling: JsonNode? = null,
    val allowedImages: List<String> = emptyList(),
    val warmPoolSize: Int? = null,
    val gateway: GatewayUrls? = null,
    val signingKeys: List<Map<String, Any?>> = emptyList(),
)

data class LeaseRequest(val maxJobs: Int, val waitSeconds: Int, val freeSlots: Int)

data class AgentJobAssignment(
    val jobId: String,
    val leaseExpiresAt: String,
    val jobToken: String,
    val spec: ObjectNode,
    val policy: ObjectNode?,
    val specDigest: String?,
    val signature: String?,
    val keyId: String?,
    /** Not in the contract yet; used for labels when Back Office sends it. */
    val projectId: String? = null,
)

enum class HeartbeatPhase { STARTING, RUNNING }

data class JobHeartbeat(
    val phase: HeartbeatPhase,
    val sandboxName: String?,
    val node: String?,
    val cpuMillis: Long? = null,
    val memoryBytes: Long? = null,
)

data class JobHeartbeatResponse(val leaseExpiresAt: String? = null, val cancelRequested: Boolean = false)

enum class AgentJobStatus { QUEUED, LEASED, STARTING, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, LOST }

data class JobCompletion(
    val status: AgentJobStatus,
    val exitCode: Int? = null,
    val reason: String? = null,
    val imageDigest: String? = null,
    val egressLogDigest: String? = null,
    val egressDenied: List<String> = emptyList(),
    val durationMs: Long = 0,
)
