package pl.mos.backoffice.runner.adapter.out.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.mos.backoffice.runner.JobCompletionJson
import pl.mos.backoffice.runner.RunnerJobSpecJson
import pl.mos.backoffice.runner.RunnerResultJson
import pl.mos.backoffice.runner.SandboxPolicyJson
import pl.mos.backoffice.runner.UsageContextJson
import pl.mos.backoffice.runner.WorkRequestJson
import pl.mos.backoffice.runner.application.port.`in`.AgentJobFilter
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.PoolCounts
import pl.mos.backoffice.runner.application.port.out.ProjectPolicyStore
import pl.mos.backoffice.runner.application.port.out.RegistrationTokenStore
import pl.mos.backoffice.runner.application.port.out.RunnerNodeStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.StoredResult
import pl.mos.backoffice.runner.domain.AgentJob
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.runner.domain.RegistrationToken
import pl.mos.backoffice.runner.domain.RunnerNode
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.runner.toContext
import pl.mos.backoffice.runner.toJson
import pl.mos.backoffice.runner.toSpec
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.runner.RunnerLogLine
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.usage.PageOf
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val ACTIVE = AgentJobStatus.ACTIVE.map { it.name }
private val STRING_MAP = object : TypeReference<Map<String, String>>() {}
private val STRING_LIST = object : TypeReference<List<String>>() {}

private fun Instant?.ts(): OffsetDateTime? = this?.atOffset(ZoneOffset.UTC)

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

/** JDBC adapters of the fleet tables (V51__runner_fleet.sql). JSON columns hold the openapi documents. */
class JdbcFleetStores(private val db: NamedParameterJdbcTemplate, private val json: JsonMapper) :
    RunnerPoolStore, RunnerNodeStore, RegistrationTokenStore, AgentJobStore, ProjectPolicyStore {

    private fun write(value: Any?): String? = value?.let(json::writeValueAsString)

    private fun policyJson(policy: SandboxPolicy?): String? = policy?.let { write(SandboxPolicyJson.of(it)) }

    private fun policyOf(text: String?): SandboxPolicy? = text?.let { json.readValue(it, SandboxPolicyJson::class.java).toPolicy() }

    // ================================================================ pools

    private val poolColumns = "id, name, description, labels, ceiling, allowed_images, warm_pool_size, max_concurrent_jobs, project_ids, created_at, updated_at, owner_user_id, llm_credentials"

    private fun pool(rs: ResultSet) = RunnerPool(
        id = rs.getString("id"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        labels = json.readValue(rs.getString("labels"), STRING_MAP),
        ceiling = policyOf(rs.getString("ceiling")) ?: SandboxPolicy.DEFAULT,
        allowedImages = json.readValue(rs.getString("allowed_images"), STRING_LIST),
        warmPoolSize = rs.getInt("warm_pool_size"),
        maxConcurrentJobs = rs.getInt("max_concurrent_jobs"),
        projectIds = json.readValue(rs.getString("project_ids"), STRING_LIST).map(UUID::fromString).toSet(),
        createdAt = rs.instant("created_at")!!,
        updatedAt = rs.instant("updated_at")!!,
        ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
        llmCredentials = LlmCredentials.valueOf(rs.getString("llm_credentials")),
    )

    private fun poolParams(pool: RunnerPool) = MapSqlParameterSource()
        .addValue("id", pool.id).addValue("name", pool.name).addValue("description", pool.description)
        .addValue("labels", write(pool.labels)).addValue("ceiling", policyJson(pool.ceiling)).addValue("allowedImages", write(pool.allowedImages))
        .addValue("warm", pool.warmPoolSize).addValue("max", pool.maxConcurrentJobs).addValue("projects", write(pool.projectIds.map(UUID::toString)))
        .addValue("createdAt", pool.createdAt.ts()).addValue("updatedAt", pool.updatedAt.ts())
        .addValue("owner", pool.ownerUserId).addValue("credentials", pool.llmCredentials.name)

    override fun findAll(): List<RunnerPool> = db.query("SELECT $poolColumns FROM runner_pool ORDER BY id") { rs, _ -> pool(rs) }

    override fun find(id: String): RunnerPool? =
        db.query("SELECT $poolColumns FROM runner_pool WHERE id = :id", mapOf("id" to id)) { rs, _ -> pool(rs) }.firstOrNull()

    override fun insert(pool: RunnerPool) {
        db.update(
            """
            INSERT INTO runner_pool ($poolColumns)
            VALUES (:id, :name, :description, CAST(:labels AS jsonb), CAST(:ceiling AS jsonb), CAST(:allowedImages AS jsonb), :warm, :max,
                    CAST(:projects AS jsonb), :createdAt, :updatedAt, :owner, :credentials)
            """.trimIndent(),
            poolParams(pool),
        )
    }

    override fun update(pool: RunnerPool) {
        db.update(
            """
            UPDATE runner_pool SET name = :name, description = :description, labels = CAST(:labels AS jsonb), ceiling = CAST(:ceiling AS jsonb),
                   allowed_images = CAST(:allowedImages AS jsonb), warm_pool_size = :warm, max_concurrent_jobs = :max,
                   project_ids = CAST(:projects AS jsonb), updated_at = :updatedAt, llm_credentials = :credentials
            WHERE id = :id
            """.trimIndent(),
            poolParams(pool),
        )
    }

    override fun delete(id: String) {
        db.update("DELETE FROM runner_pool WHERE id = :id", mapOf("id" to id))
    }

    // ================================================================ runners

    private val nodeColumns = "id, pool_id, name, version, cluster, backends, runtime_classes, max_concurrent, registered_at, last_seen_at, revoked_at"

    private fun node(rs: ResultSet) = RunnerNode(
        id = rs.getObject("id", UUID::class.java),
        poolId = rs.getString("pool_id"),
        name = rs.getString("name"),
        version = rs.getString("version"),
        cluster = rs.getString("cluster"),
        backends = json.readValue(rs.getString("backends"), STRING_LIST),
        runtimeClasses = json.readValue(rs.getString("runtime_classes"), STRING_LIST),
        maxConcurrent = rs.getInt("max_concurrent"),
        registeredAt = rs.instant("registered_at")!!,
        lastSeenAt = rs.instant("last_seen_at")!!,
        revokedAt = rs.instant("revoked_at"),
    )

    override fun insert(node: RunnerNode, tokenHash: String) {
        db.update(
            """
            INSERT INTO runner (id, pool_id, name, version, cluster, backends, runtime_classes, max_concurrent, token_hash, registered_at, last_seen_at)
            VALUES (:id, :pool, :name, :version, :cluster, CAST(:backends AS jsonb), CAST(:runtimes AS jsonb), :max, :hash, :at, :at)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", node.id).addValue("pool", node.poolId).addValue("name", node.name).addValue("version", node.version)
                .addValue("cluster", node.cluster).addValue("backends", write(node.backends)).addValue("runtimes", write(node.runtimeClasses))
                .addValue("max", node.maxConcurrent).addValue("hash", tokenHash).addValue("at", node.registeredAt.ts()),
        )
    }

    override fun find(id: UUID): RunnerNode? =
        db.query("SELECT $nodeColumns FROM runner WHERE id = :id", mapOf("id" to id)) { rs, _ -> node(rs) }.firstOrNull()

    override fun findRunnerByTokenHash(hash: String): RunnerNode? =
        db.query("SELECT $nodeColumns FROM runner WHERE token_hash = :hash", mapOf("hash" to hash)) { rs, _ -> node(rs) }.firstOrNull()

    override fun list(poolId: String?): List<RunnerNode> =
        if (poolId == null) db.query("SELECT $nodeColumns FROM runner ORDER BY pool_id, name") { rs, _ -> node(rs) }
        else db.query("SELECT $nodeColumns FROM runner WHERE pool_id = :pool ORDER BY name", mapOf("pool" to poolId)) { rs, _ -> node(rs) }

    override fun touch(id: UUID, at: Instant) {
        db.update("UPDATE runner SET last_seen_at = :at WHERE id = :id AND last_seen_at < :at", MapSqlParameterSource().addValue("id", id).addValue("at", at.ts()))
    }

    override fun revoke(id: UUID, at: Instant) {
        db.update("UPDATE runner SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL", MapSqlParameterSource().addValue("id", id).addValue("at", at.ts()))
    }

    // ================================================================ registration tokens

    override fun insert(token: RegistrationToken, tokenHash: String, createdBy: String?) {
        db.update(
            """
            INSERT INTO runner_registration_token (id, pool_id, token_hash, created_at, expires_at, created_by)
            VALUES (:id, :pool, :hash, :createdAt, :expiresAt, :createdBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", token.id).addValue("pool", token.poolId).addValue("hash", tokenHash)
                .addValue("createdAt", token.createdAt.ts()).addValue("expiresAt", token.expiresAt.ts()).addValue("createdBy", createdBy),
        )
    }

    override fun consume(tokenHash: String, now: Instant, runnerId: UUID): RegistrationToken? =
        db.query(
            """
            UPDATE runner_registration_token SET used_at = :now, runner_id = :runner
            WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now
            RETURNING id, pool_id, created_at, expires_at, used_at
            """.trimIndent(),
            MapSqlParameterSource().addValue("hash", tokenHash).addValue("now", now.ts()).addValue("runner", runnerId),
        ) { rs, _ -> RegistrationToken(rs.getObject("id", UUID::class.java), rs.getString("pool_id"), rs.instant("created_at")!!, rs.instant("expires_at")!!, rs.instant("used_at")) }
            .firstOrNull()

    // ================================================================ agent jobs

    private val jobColumns = """
        id, label, pool_id, runner_id, status, priority, spec, usage_context, requested_policy, policy, policy_sources, pool_selector,
        continuation, cancel_requested, lease_expires_at, lease_count, lost_count, phase, sandbox_name, node, queued_at, leased_at,
        started_at, finished_at, queue_deadline, reason, completion, (result IS NOT NULL OR result_blob_key IS NOT NULL) AS has_result, cost_usd
    """.trimIndent()

    private fun job(rs: ResultSet): AgentJob {
        val label = rs.getString("label")
        val requested = policyOf(rs.getString("requested_policy"))
        val specJson = json.readValue(rs.getString("spec"), RunnerJobSpecJson::class.java)
        val context = json.readValue(rs.getString("usage_context"), UsageContextJson::class.java).toContext()
        return AgentJob(
            id = RunnerJobId(rs.getObject("id", UUID::class.java)),
            label = label,
            poolId = rs.getString("pool_id"),
            runnerId = rs.getObject("runner_id", UUID::class.java),
            status = AgentJobStatus.valueOf(rs.getString("status")),
            priority = rs.getInt("priority"),
            spec = specJson.toSpec(context, label, requested),
            requestedPolicy = requested,
            policy = policyOf(rs.getString("policy")),
            policySources = json.readValue(rs.getString("policy_sources"), STRING_MAP),
            poolSelector = json.readValue(rs.getString("pool_selector"), STRING_MAP),
            continuation = rs.getString("continuation")?.let { json.readValue(it, WorkRequestJson::class.java).toRequest() },
            cancelRequested = rs.getBoolean("cancel_requested"),
            leaseExpiresAt = rs.instant("lease_expires_at"),
            leaseCount = rs.getInt("lease_count"),
            lostCount = rs.getInt("lost_count"),
            phase = rs.getString("phase"),
            sandboxName = rs.getString("sandbox_name"),
            node = rs.getString("node"),
            queuedAt = rs.instant("queued_at")!!,
            leasedAt = rs.instant("leased_at"),
            startedAt = rs.instant("started_at"),
            finishedAt = rs.instant("finished_at"),
            queueDeadline = rs.instant("queue_deadline"),
            reason = rs.getString("reason"),
            completion = rs.getString("completion")?.let { json.readValue(it, JobCompletionJson::class.java).toReport() },
            hasResult = rs.getBoolean("has_result"),
            costUsd = rs.getBigDecimal("cost_usd"),
        )
    }

    private fun queryJobs(sql: String, params: Map<String, Any?> = emptyMap()): List<AgentJob> = db.query(sql, params) { rs, _ -> job(rs) }

    override fun find(id: RunnerJobId): AgentJob? = queryJobs("SELECT $jobColumns FROM agent_job WHERE id = :id", mapOf("id" to id.value)).firstOrNull()

    override fun findByLabel(label: String): AgentJob? = queryJobs("SELECT $jobColumns FROM agent_job WHERE label = :label", mapOf("label" to label)).firstOrNull()

    override fun insertIfAbsent(job: AgentJob): AgentJob {
        val usage = job.spec.usageContext
        db.update(
            """
            INSERT INTO agent_job (id, label, pool_id, status, priority, mode, agent, model_label, project_id, run_id, node_id, benchmark_id, attempt_id,
                                   spec, usage_context, requested_policy, policy, policy_sources, pool_selector, continuation, queued_at,
                                   queue_deadline, timeout_seconds, reason)
            VALUES (:id, :label, :pool, :status, :priority, :mode, :agent, :model, :project, :run, :node, :benchmark, :attempt,
                    CAST(:spec AS jsonb), CAST(:usage AS jsonb), CAST(:requested AS jsonb), CAST(:policy AS jsonb), CAST(:sources AS jsonb),
                    CAST(:selector AS jsonb), CAST(:continuation AS jsonb), :queuedAt, :deadline, :timeout, :reason)
            ON CONFLICT (label) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", job.id.value).addValue("label", job.label).addValue("pool", job.poolId).addValue("status", job.status.name)
                .addValue("priority", job.priority).addValue("mode", job.spec.mode.name).addValue("agent", job.spec.agent.name)
                .addValue("model", job.spec.model.label).addValue("project", usage.projectId).addValue("run", usage.runId).addValue("node", usage.stepId)
                .addValue("benchmark", usage.benchmarkId).addValue("attempt", usage.attemptId)
                .addValue("spec", write(RunnerJobSpecJson.of(job.spec))).addValue("usage", write(usage.toJson()))
                .addValue("requested", policyJson(job.requestedPolicy)).addValue("policy", policyJson(job.policy))
                .addValue("sources", write(job.policySources)).addValue("selector", write(job.poolSelector))
                .addValue("continuation", job.continuation?.let { write(WorkRequestJson.of(it)) })
                .addValue("queuedAt", job.queuedAt.ts()).addValue("deadline", job.queueDeadline.ts())
                .addValue("timeout", job.spec.timeout.seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).addValue("reason", job.reason),
        )
        return findByLabel(job.label) ?: error("agent job ${job.label} vanished after insert")
    }

    override fun lock(id: RunnerJobId): AgentJob? =
        queryJobs("SELECT $jobColumns FROM agent_job WHERE id = :id FOR UPDATE", mapOf("id" to id.value)).firstOrNull()

    override fun lockQueued(poolId: String, limit: Int): List<AgentJob> = queryJobs(
        """
        SELECT $jobColumns FROM agent_job
        WHERE pool_id = :pool AND status = 'QUEUED' AND NOT cancel_requested
        ORDER BY priority DESC, queued_at ASC LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
        mapOf("pool" to poolId, "limit" to limit),
    )

    override fun lockExpiredLeases(now: Instant, limit: Int): List<AgentJob> = queryJobs(
        """
        SELECT $jobColumns FROM agent_job
        WHERE status IN (:active) AND lease_expires_at < :now
        ORDER BY lease_expires_at LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
        mapOf("active" to ACTIVE, "now" to now.ts(), "limit" to limit),
    )

    override fun lockQueuedPastDeadline(now: Instant, limit: Int): List<AgentJob> = queryJobs(
        """
        SELECT $jobColumns FROM agent_job
        WHERE status = 'QUEUED' AND queue_deadline < :now
        ORDER BY queue_deadline LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
        mapOf("now" to now.ts(), "limit" to limit),
    )

    override fun lockUnassigned(limit: Int): List<AgentJob> = queryJobs(
        "SELECT $jobColumns FROM agent_job WHERE status = 'QUEUED' AND pool_id IS NULL ORDER BY queued_at LIMIT :limit FOR UPDATE SKIP LOCKED",
        mapOf("limit" to limit),
    )

    override fun update(job: AgentJob) {
        db.update(
            """
            UPDATE agent_job SET pool_id = :pool, runner_id = :runner, status = :status, priority = :priority, policy = CAST(:policy AS jsonb),
                   policy_sources = CAST(:sources AS jsonb), pool_selector = CAST(:selector AS jsonb), cancel_requested = :cancel,
                   lease_expires_at = :leaseExpiresAt, lease_count = :leaseCount, lost_count = :lostCount, phase = :phase,
                   sandbox_name = :sandbox, node = :node, leased_at = :leasedAt, started_at = :startedAt, finished_at = :finishedAt,
                   reason = :reason, completion = CAST(:completion AS jsonb), cost_usd = :cost
            WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", job.id.value).addValue("pool", job.poolId).addValue("runner", job.runnerId).addValue("status", job.status.name)
                .addValue("priority", job.priority).addValue("policy", policyJson(job.policy)).addValue("sources", write(job.policySources))
                .addValue("selector", write(job.poolSelector)).addValue("cancel", job.cancelRequested)
                .addValue("leaseExpiresAt", job.leaseExpiresAt.ts()).addValue("leaseCount", job.leaseCount).addValue("lostCount", job.lostCount)
                .addValue("phase", job.phase).addValue("sandbox", job.sandboxName).addValue("node", job.node)
                .addValue("leasedAt", job.leasedAt.ts()).addValue("startedAt", job.startedAt.ts()).addValue("finishedAt", job.finishedAt.ts())
                .addValue("reason", job.reason?.take(4000)).addValue("completion", job.completion?.let { write(JobCompletionJson.of(it)) })
                .addValue("cost", job.costUsd),
        )
    }

    override fun setTokenHash(id: RunnerJobId, hash: String?) {
        // a new lease (or the end of the job) also invalidates the gateway-only agent token of the previous one
        db.update(
            "UPDATE agent_job SET token_hash = :hash, agent_token_hash = NULL WHERE id = :id",
            MapSqlParameterSource().addValue("id", id.value).addValue("hash", hash),
        )
    }

    override fun findJobByTokenHash(hash: String): AgentJob? =
        queryJobs("SELECT $jobColumns FROM agent_job WHERE token_hash = :hash", mapOf("hash" to hash)).firstOrNull()

    override fun setAgentTokenHash(id: RunnerJobId, hash: String): Boolean =
        db.update(
            "UPDATE agent_job SET agent_token_hash = :hash WHERE id = :id AND agent_token_hash IS NULL AND token_hash IS NOT NULL",
            MapSqlParameterSource().addValue("id", id.value).addValue("hash", hash),
        ) == 1

    override fun findJobByAgentTokenHash(hash: String): AgentJob? =
        queryJobs("SELECT $jobColumns FROM agent_job WHERE agent_token_hash = :hash", mapOf("hash" to hash)).firstOrNull()

    override fun saveResult(id: RunnerJobId, result: RunnerResult?, blobKey: String?) {
        db.update(
            "UPDATE agent_job SET result = CAST(:result AS jsonb), result_blob_key = :key WHERE id = :id",
            MapSqlParameterSource().addValue("id", id.value).addValue("result", result?.let { write(RunnerResultJson.of(it)) }).addValue("key", blobKey),
        )
    }

    override fun loadResult(id: RunnerJobId): StoredResult? =
        db.query("SELECT result, result_blob_key, finished_at FROM agent_job WHERE id = :id", mapOf("id" to id.value)) { rs, _ ->
            val text = rs.getString("result")
            val key = rs.getString("result_blob_key")
            if (text == null && key == null) null
            else StoredResult(text?.let { json.readValue(it, RunnerResultJson::class.java).toRunnerResult(rs.instant("finished_at") ?: Instant.EPOCH) }, key)
        }.firstOrNull()

    override fun countActive(poolId: String): Int =
        db.queryForObject("SELECT count(*) FROM agent_job WHERE pool_id = :pool AND status IN (:active)", mapOf("pool" to poolId, "active" to ACTIVE), Int::class.java) ?: 0

    override fun countActiveByRunner(runnerId: UUID): Int =
        db.queryForObject("SELECT count(*) FROM agent_job WHERE runner_id = :runner AND status IN (:active)", mapOf("runner" to runnerId, "active" to ACTIVE), Int::class.java) ?: 0

    override fun countsByPool(): Map<String, PoolCounts> =
        db.query(
            """
            SELECT pool_id, count(*) FILTER (WHERE status = 'QUEUED') AS queued, count(*) FILTER (WHERE status IN (:active)) AS active
            FROM agent_job WHERE pool_id IS NOT NULL AND (status = 'QUEUED' OR status IN (:active)) GROUP BY pool_id
            """.trimIndent(),
            mapOf("active" to ACTIVE),
        ) { rs, _ -> rs.getString("pool_id") to PoolCounts(rs.getInt("queued"), rs.getInt("active")) }.toMap()

    override fun countActiveByRunners(): Map<UUID, Int> =
        db.query("SELECT runner_id, count(*) AS n FROM agent_job WHERE runner_id IS NOT NULL AND status IN (:active) GROUP BY runner_id", mapOf("active" to ACTIVE)) { rs, _ ->
            rs.getObject("runner_id", UUID::class.java) to rs.getInt("n")
        }.toMap()

    override fun activeOfRunner(runnerId: UUID): List<AgentJob> =
        queryJobs("SELECT $jobColumns FROM agent_job WHERE runner_id = :runner AND status IN (:active)", mapOf("runner" to runnerId, "active" to ACTIVE))

    override fun hasLiveJobs(poolId: String): Boolean =
        (db.queryForObject("SELECT count(*) FROM agent_job WHERE pool_id = :pool AND (status = 'QUEUED' OR status IN (:active))", mapOf("pool" to poolId, "active" to ACTIVE), Int::class.java) ?: 0) > 0

    override fun page(filter: AgentJobFilter, page: Int, size: Int): PageOf<AgentJob> {
        val where = mutableListOf<String>()
        val params = MapSqlParameterSource().addValue("limit", size).addValue("offset", page.toLong() * size)
        filter.poolId?.let { where += "pool_id = :pool"; params.addValue("pool", it) }
        filter.status?.let { where += "status = :status"; params.addValue("status", it.name) }
        filter.projectId?.let { where += "project_id = :project"; params.addValue("project", it) }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = db.queryForObject("SELECT count(*) FROM agent_job $clause", params, Long::class.java) ?: 0L
        val content = db.query("SELECT $jobColumns FROM agent_job $clause ORDER BY queued_at DESC, id LIMIT :limit OFFSET :offset", params) { rs, _ -> job(rs) }
        return PageOf(content, page, size, total)
    }

    override fun appendEvent(id: RunnerJobId, at: Instant, message: String, progress: Boolean) {
        db.update(
            "INSERT INTO agent_job_event (job_id, at, message, progress) VALUES (:id, :at, :message, :progress)",
            MapSqlParameterSource().addValue("id", id.value).addValue("at", at.ts()).addValue("message", message).addValue("progress", progress),
        )
    }

    override fun events(id: RunnerJobId, after: Instant?): List<RunnerLogLine> {
        val params = MapSqlParameterSource().addValue("id", id.value).addValue("after", after.ts())
        val condition = if (after == null) "" else "AND at > :after"
        return db.query("SELECT at, message, progress FROM agent_job_event WHERE job_id = :id $condition ORDER BY at, id", params) { rs, _ ->
            RunnerLogLine(rs.instant("at")!!, rs.getString("message"), rs.getBoolean("progress"))
        }
    }

    override fun tryAdvisoryLock(key: Long): Boolean =
        db.queryForObject("SELECT pg_try_advisory_xact_lock(:key)", mapOf("key" to key), Boolean::class.java) == true

    // ================================================================ project sandbox policy

    override fun findPolicy(projectId: UUID): SandboxPolicy? =
        db.query("SELECT policy FROM project_sandbox_policy WHERE project_id = :id", mapOf("id" to projectId)) { rs, _ -> policyOf(rs.getString("policy")) }.firstOrNull()

    override fun savePolicy(projectId: UUID, policy: SandboxPolicy, at: Instant, updatedBy: String?) {
        db.update(
            """
            INSERT INTO project_sandbox_policy (project_id, policy, updated_at, updated_by) VALUES (:id, CAST(:policy AS jsonb), :at, :by)
            ON CONFLICT (project_id) DO UPDATE SET policy = EXCLUDED.policy, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", projectId).addValue("policy", policyJson(policy)).addValue("at", at.ts()).addValue("by", updatedBy),
        )
    }
}
