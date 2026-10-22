package pl.mos.backoffice.runnercontroller.fake

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson
import pl.mos.backoffice.runnercontroller.security.AssignmentVerifier
import pl.mos.backoffice.runnercontroller.security.CanonicalJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny fake of the Back Office side of the runner protocol v2 (plus the sandbox callbacks, uploads and an
 * Anthropic-compatible LLM endpoint), signing assignments with a real Ed25519 key.
 */
class FakeBackOffice(bindHost: String = "127.0.0.1") : AutoCloseable {
    val json = ProtocolJson.mapper()
    val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val keyId = "test-key-1"
    val registrationToken = "bo_rrt_test"
    val runnerToken = "bo_rt_" + UUID.randomUUID()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(bindHost, 0), 0)

    data class Call(val method: String, val path: String, val authorization: String?, val body: String, val headers: Map<String, String>)

    val calls = CopyOnWriteArrayList<Call>()
    val queue = ConcurrentLinkedQueue<ObjectNode>()
    val completions = CopyOnWriteArrayList<Pair<String, JsonNode>>()
    val events = CopyOnWriteArrayList<Pair<String, JsonNode>>()
    val results = CopyOnWriteArrayList<Pair<String, JsonNode>>()
    val uploads = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    val llmCalls = CopyOnWriteArrayList<Call>()
    val cancel = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val jobTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    val heartbeatStatus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val registered = AtomicBoolean(false)

    /** ADR 0051: the public key the controller registered, and the project tokens per job: ref -> (repository, token). */
    @Volatile var runnerPublicKey: String? = null
    val projectTokens = java.util.concurrent.ConcurrentHashMap<String, Map<String, Pair<String, String>>>()
    /** ADR 0051: seal with this associated data instead of the real one (a tampered answer). */
    @Volatile var credentialAad: String? = null

    /** Host name under which sandboxes reach this server (e.g. `host.docker.internal`). */
    var publicHost: String = bindHost
    var ceiling: Map<String, Any?> = mapOf("isolation" to "STANDARD", "resources" to mapOf("cpu" to "2", "memory" to "4Gi"), "timeoutSeconds" to 3600)
    var allowedImages: List<String> = listOf("back-office/runner:*")
    var warmPoolSize = 0

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { handle(it) }
        server.start()
    }

    val port: Int get() = server.address.port
    val url: String get() = "http://127.0.0.1:$port"
    val publicUrl: String get() = "http://$publicHost:$port"

    fun gateway(): Map<String, String> = mapOf(
        "anthropicBaseUrl" to "$publicUrl/api/v1/llm/anthropic",
        "openaiBaseUrl" to "$publicUrl/api/v1/llm/openai/v1",
        "apiBaseUrl" to "$publicUrl/api/v1",
    )

    fun jwk(): Map<String, Any?> = mapOf("kty" to "OKP", "crv" to "Ed25519", "kid" to keyId, "x" to AssignmentVerifier.jwkX(keys.public))

    /** Queues a signed assignment; returns the job id. */
    fun enqueue(
        spec: Map<String, Any?>,
        policy: Map<String, Any?>? = null,
        leaseExpiresAt: Instant = Instant.now().plusSeconds(60),
        tamper: ((ObjectNode) -> Unit)? = null,
        sign: Boolean = true,
    ): String {
        val jobId = UUID.randomUUID().toString()
        queue += assignment(jobId, spec, policy, leaseExpiresAt, tamper, sign)
        return jobId
    }

    fun assignment(
        jobId: String,
        spec: Map<String, Any?>,
        policy: Map<String, Any?>?,
        leaseExpiresAt: Instant = Instant.now().plusSeconds(60),
        tamper: ((ObjectNode) -> Unit)? = null,
        sign: Boolean = true,
    ): ObjectNode {
        val specNode = json.valueToTree<JsonNode>(spec)
        val policyNode = policy?.let { json.valueToTree<JsonNode>(it) }
        val digest = "sha256:" + CanonicalJson.specDigest(specNode, policyNode)
        val expires = leaseExpiresAt.toString()
        val token = "bo_job_" + UUID.randomUUID().toString().replace("-", "")
        jobTokens[jobId] = token
        val node = json.createObjectNode()
        node.put("jobId", jobId)
        node.put("leaseExpiresAt", expires)
        node.put("jobToken", token)
        node.set("spec", specNode)
        if (policyNode != null) node.set("policy", policyNode)
        node.put("specDigest", digest)
        if (sign) {
            val sig = Signature.getInstance("Ed25519").apply {
                initSign(keys.private)
                update(AssignmentVerifier.signingInput(jobId, digest, expires))
            }.sign()
            node.put("signature", Base64.getEncoder().encodeToString(sig))
            node.put("keyId", keyId)
        }
        tamper?.invoke(node)
        return node
    }

    fun completionOf(jobId: String): JsonNode? = completions.lastOrNull { it.first == jobId }?.second

    fun awaitCompletion(jobId: String, timeoutMillis: Long): JsonNode? {
        val until = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < until) {
            completionOf(jobId)?.let { return it }
            Thread.sleep(200)
        }
        return completionOf(jobId)
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes()
        val path = ex.requestURI.path
        val auth = ex.requestHeaders.getFirst("Authorization")
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        val call = Call(ex.requestMethod, path, auth, String(body), headers)
        calls += call
        try {
            route(ex, call, body)
        } catch (e: Exception) {
            respond(ex, 500, mapOf("error" to e.toString()))
        }
    }

    private fun route(ex: HttpExchange, call: Call, body: ByteArray) {
        val path = call.path
        val runnerAuth = call.authorization == "Bearer $runnerToken"
        when {
            path == "/api/v1/runner/v2/register" -> {
                val req = json.readTree(body)
                if (req.get("registrationToken")?.asString() != registrationToken || !registered.compareAndSet(false, true)) {
                    return respond(ex, 401, mapOf("title" to "bad registration token"))
                }
                respond(ex, 201, mapOf("runnerId" to UUID.randomUUID().toString(), "runnerToken" to runnerToken, "poolId" to "test-pool"))
            }
            path.startsWith("/api/v1/runner/v2/") && path.endsWith("/uploads") -> {
                val jobId = path.split("/")[6]
                if (call.authorization != "Bearer ${jobTokens[jobId]}") return respond(ex, 401, mapOf("title" to "job token"))
                val req = json.readTree(body)
                val key = "jobs/$jobId/${req.get("name").asString()}"
                respond(ex, 200, mapOf("url" to "$publicUrl/blob/$key", "method" to "PUT", "headers" to mapOf("x-test" to "1"), "blobKey" to key, "expiresAt" to Instant.now().plusSeconds(600).toString()))
            }
            path.startsWith("/api/v1/runner/v2/") && !runnerAuth -> respond(ex, 401, mapOf("title" to "runner token"))
            path == "/api/v1/runner/v2/config" -> respond(
                ex, 200,
                mapOf(
                    "poolId" to "test-pool",
                    "ceiling" to ceiling,
                    "allowedImages" to allowedImages,
                    "warmPoolSize" to warmPoolSize,
                    "gateway" to gateway(),
                    "signingKeys" to listOf(jwk()),
                ),
            )
            path == "/api/v1/runner/v2/jobs/lease" -> {
                val req = json.readTree(body)
                val max = req.get("maxJobs")?.asInt() ?: 1
                val wait = (req.get("waitSeconds")?.asInt() ?: 1).coerceAtMost(2)
                val until = System.currentTimeMillis() + wait * 1000L
                val out = mutableListOf<ObjectNode>()
                while (out.size < max) {
                    val next = queue.poll()
                    if (next != null) {
                        out += next
                        continue
                    }
                    if (out.isNotEmpty() || System.currentTimeMillis() > until) break
                    TimeUnit.MILLISECONDS.sleep(50)
                }
                respond(ex, 200, out)
            }
            path == "/api/v1/runner/v2/key" && call.method == "PUT" -> {
                runnerPublicKey = json.readTree(body).get("publicKey").asString()
                respond(ex, 204, null)
            }
            path.endsWith("/credentials") -> {
                val jobId = path.split("/")[6]
                val key = runnerPublicKey ?: return respond(ex, 409, mapOf("title" to "no public key"))
                val expiresAt = Instant.now().plusSeconds(120).toString()
                val hpke = org.bouncycastle.crypto.hpke.HPKE(
                    org.bouncycastle.crypto.hpke.HPKE.mode_base, org.bouncycastle.crypto.hpke.HPKE.kem_X25519_SHA256,
                    org.bouncycastle.crypto.hpke.HPKE.kdf_HKDF_SHA256, org.bouncycastle.crypto.hpke.HPKE.aead_AES_GCM256,
                )
                val recipient = hpke.deserializePublicKey(java.util.Base64.getDecoder().decode(key))
                val sealed = projectTokens[jobId].orEmpty().map { (ref, value) ->
                    val aad = credentialAad ?: "$jobId|$ref|$expiresAt"
                    val r = hpke.seal(recipient, "back-office credentials v1".toByteArray(), aad.toByteArray(), value.second.toByteArray(), null, null, null)
                    mapOf(
                        "ref" to ref, "repository" to value.first, "capability" to "GIT_READ",
                        "enc" to java.util.Base64.getEncoder().encodeToString(r[1]), "ciphertext" to java.util.Base64.getEncoder().encodeToString(r[0]),
                    )
                }
                respond(ex, 200, mapOf("expiresAt" to expiresAt, "credentials" to sealed))
            }
            path.endsWith("/heartbeat") -> {
                val jobId = path.split("/")[6]
                val status = heartbeatStatus[jobId] ?: 200
                if (status != 200) return respond(ex, status, mapOf("title" to "lease"))
                respond(ex, 200, mapOf("leaseExpiresAt" to Instant.now().plusSeconds(60).toString(), "cancelRequested" to (jobId in cancel)))
            }
            path.endsWith("/complete") -> {
                completions += path.split("/")[6] to json.readTree(body)
                respond(ex, 202, null)
            }
            path.startsWith("/api/v1/internal/runner/jobs/") -> {
                val parts = path.split("/")
                val jobId = parts[6]
                if (call.authorization != "Bearer ${jobTokens[jobId]}") return respond(ex, 401, mapOf("title" to "job token"))
                when (parts[7]) {
                    "events" -> json.readTree(body).forEach { events += jobId to it }
                    "result" -> results += jobId to json.readTree(body)
                }
                respond(ex, 202, null)
            }
            path.startsWith("/blob/") && call.method == "PUT" -> {
                uploads += path.removePrefix("/blob/") to body
                respond(ex, 200, null)
            }
            path == "/api/v1/llm/anthropic/v1/messages" -> {
                llmCalls += call
                val key = call.headers["x-api-key"] ?: call.authorization?.removePrefix("Bearer ")
                if (key == null || key !in jobTokens.values) return respond(ex, 401, mapOf("type" to "error"))
                respond(
                    ex, 200,
                    mapOf(
                        "id" to "msg_fake", "type" to "message", "role" to "assistant", "model" to "fake",
                        "content" to listOf(mapOf("type" to "text", "text" to "hello from the fake gateway")),
                        "stop_reason" to "end_turn", "usage" to mapOf("input_tokens" to 3, "output_tokens" to 5),
                    ),
                )
            }
            else -> respond(ex, 404, mapOf("title" to "not found: $path"))
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: Any?) {
        val bytes = if (body == null) ByteArray(0) else json.writeValueAsBytes(body)
        if (bytes.isNotEmpty()) ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    override fun close() {
        server.stop(0)
    }
}
