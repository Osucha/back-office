package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.runner.application.port.`in`.MaintainAgentJobs
import pl.mos.backoffice.runner.application.port.`in`.ManageAgentJobs
import pl.mos.backoffice.runner.application.port.`in`.ManageRunnerPools
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolInput
import pl.mos.backoffice.runner.application.service.AgentJobService
import pl.mos.backoffice.runner.domain.AgentJobStatus
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.shared.platform.JobTokenScope
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.runner.SandboxResources
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Runner protocol v2 end to end against PostgreSQL: registration, lease, heartbeat, sandbox callbacks,
 * completion with the continuation, lease expiry / LOST / re-queue, cancellation and token scoping.
 */
@SpringBootTest(classes = [RunnerTestApplication::class])
@AutoConfigureMockMvc
@Import(RunnerTestBeans::class)
class RunnerProtocolIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var pools: ManageRunnerPools
    @Autowired lateinit var adminJobs: ManageAgentJobs
    @Autowired lateinit var runner: AgentJobService
    @Autowired lateinit var maintenance: MaintainAgentJobs
    @Autowired lateinit var workQueue: RecordingWorkQueue
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var audit: RecordingAuditPort
    @Autowired lateinit var testBeans: RunnerTestBeans

    private lateinit var poolId: String
    private lateinit var selector: Map<String, String>

    @BeforeEach
    fun pool() {
        gate.user = FakeAuthorizationGate.user(platform = setOf(RunnerPermissions.FLEET_MANAGE, RunnerPermissions.FLEET_VIEW))
        poolId = "it-" + UUID.randomUUID().toString().take(8)
        selector = mapOf("suite" to poolId)
        pools.create(RunnerPoolInput(id = poolId, name = "Integration $poolId", labels = selector, ceiling = SandboxPolicy(resources = SandboxResources(cpu = "1")), maxConcurrentJobs = 5))
    }

    private fun spec(push: Boolean = false) = RunnerJobSpec(
        mode = RunnerMode.IMPLEMENT,
        agent = AgentHarness.CLAUDE_CODE,
        model = ModelRef(Provider.ANTHROPIC, "claude-opus-5"),
        prompt = "implement SHOP-1",
        repository = RunnerRepository("https://gitlab.example.com/g/shop.git", "main", "feature/shop-1", push = push),
        usageContext = UsageContext(UsageKind.PIPELINE_STEP, projectId = UUID.randomUUID(), runId = UUID.randomUUID(), stepId = "implement"),
        jobLabel = "job-${UUID.randomUUID()}",
        policy = SandboxPolicy(poolSelector = selector, resources = SandboxResources(memory = "2Gi")),
    )

    private fun continuation(runId: UUID) = WorkRequest(WorkKinds.RUN_ADVANCE, mapOf("runId" to runId.toString()), dedupeKey = "RUN_ADVANCE:$runId")

    private fun register(): String {
        val issued = pools.issueRegistrationToken(poolId)
        assertThat(issued.token).startsWith("bo_rrt_")
        // keys of deploy/helm/back-office-runner/values.yaml
        assertThat(issued.helmValues.lines()).contains(
            "backOffice:", "  url: \"http://api.test:8080\"", "  poolId: \"$poolId\"",
            "registration:", "  token: \"${issued.token}\"", "backend: auto",
        )
        assertThat(issued.helmValues).doesNotContain("registrationToken")
        val body = """{"registrationToken":"${issued.token}","name":"ctrl-$poolId","version":"2.0.0","cluster":"kind",
            "capabilities":{"backends":["K8S_JOB"],"runtimeClasses":["gvisor"],"maxConcurrent":3}}"""
        val response = mvc.perform(post("/api/v1/runner/v2/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andExpect(jsonPath("$.poolId").value(poolId)).andReturn().response.contentAsString
        // single use
        mvc.perform(post("/api/v1/runner/v2/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        return json.readTree(response).get("runnerToken").asString()
    }

    private fun lease(runnerToken: String): List<Map<String, Any?>> {
        val text = mvc.perform(
            post("/api/v1/runner/v2/jobs/lease").header("Authorization", "Bearer $runnerToken")
                .contentType(MediaType.APPLICATION_JSON).content("""{"maxJobs":5,"waitSeconds":0}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        return json.readValue(text, List::class.java) as List<Map<String, Any?>>
    }

    private fun heartbeat(runnerToken: String, jobId: Any?, phase: String = "RUNNING") = mvc.perform(
        post("/api/v1/runner/v2/jobs/$jobId/heartbeat").header("Authorization", "Bearer $runnerToken")
            .contentType(MediaType.APPLICATION_JSON).content("""{"phase":"$phase","sandboxName":"sb-1","node":"node-a"}"""),
    )

    private fun complete(runnerToken: String, jobId: Any?, status: String) = mvc.perform(
        post("/api/v1/runner/v2/jobs/$jobId/complete").header("Authorization", "Bearer $runnerToken")
            .contentType(MediaType.APPLICATION_JSON).content("""{"status":"$status","exitCode":0,"egressDenied":["evil.example.com:443"],"durationMs":1200}"""),
    )

    @Test
    fun `register, lease, heartbeat, result, complete and the continuation is enqueued`() {
        val runnerToken = register()
        assertThat(runnerToken).startsWith("bo_rt_")
        mvc.perform(get("/api/v1/runner/v2/config").header("Authorization", "Bearer $runnerToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.poolId").value(poolId))
            .andExpect(jsonPath("$.gateway.anthropicBaseUrl").value("http://api.test:8080/api/v1/llm/anthropic"))
            .andExpect(jsonPath("$.ceiling.resources.cpu").value("1"))
            .andExpect(jsonPath("$.signingKeys[0].kid").value("test-key"))

        val spec = spec()
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        assertThat(runner.submit(spec, continuation(spec.usageContext.runId!!))).isEqualTo(jobId) // idempotent on jobLabel
        assertThat(runner.result(jobId)).isNull()

        val assignment = lease(runnerToken).single()
        assertThat(assignment["jobId"]).isEqualTo(jobId.toString())
        val jobToken = assignment["jobToken"] as String
        assertThat(jobToken).startsWith("bo_job_")
        val expected = "$jobId|${assignment["specDigest"]}|${assignment["leaseExpiresAt"]}"
        assertThat(String(Base64.getDecoder().decode(assignment["signature"] as String))).isEqualTo(expected)
        @Suppress("UNCHECKED_CAST")
        val policy = assignment["policy"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        assertThat((policy["resources"] as Map<String, Any?>)).containsEntry("cpu", "1").containsEntry("memory", "2Gi")
        @Suppress("UNCHECKED_CAST")
        assertThat((policy["network"] as Map<String, Any?>)["allow"] as List<String>).contains("api.test:8080", "gitlab.example.com")
        @Suppress("UNCHECKED_CAST")
        assertThat(((assignment["spec"] as Map<String, Any?>)["gateway"] as Map<String, Any?>)["openaiBaseUrl"]).isEqualTo("http://api.test:8080/api/v1/llm/openai/v1")
        // ADR 0031: only the signed access level travels (push = false -> READ), never a git credential
        @Suppress("UNCHECKED_CAST")
        assertThat((assignment["spec"] as Map<String, Any?>)["gitAccess"]).isEqualTo("READ")
        assertThat(lease(runnerToken)).isEmpty()

        val credential = runner.verify(jobToken)!!
        assertThat(credential.jobId).isEqualTo(jobId.value)
        assertThat(credential.nodeId).isEqualTo("implement")
        assertThat(credential.modelAllowlist).contains("anthropic/claude-opus-5", "anthropic/claude-haiku-*")

        heartbeat(runnerToken, jobId).andExpect(status().isOk).andExpect(jsonPath("$.cancelRequested").value(false))

        // token scoping: a job token is not a runner token and vice versa
        mvc.perform(post("/api/v1/runner/v2/jobs/lease").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/events").header("Authorization", "Bearer $runnerToken").contentType(MediaType.APPLICATION_JSON).content("[]"))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/runner/v2/jobs/$jobId/uploads").header("Authorization", "Bearer $runnerToken").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)

        mvc.perform(
            post("/api/v1/internal/runner/jobs/$jobId/events").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON)
                .content("""[{"type":"LOG","message":"cloning","at":"2026-09-28T10:00:01Z"},{"type":"PROGRESS","message":"50%","at":"2026-09-28T10:00:02Z"}]"""),
        ).andExpect(status().isAccepted)
        assertThat(runner.logs(jobId, null)).hasSize(2)
        assertThat(testBeans.events.filter { it.type == "RunnerLog" && it.payload["jobId"] == jobId.toString() && it.payload["message"] == "cloning" })
            .singleElement().matches { it.payload["projectId"] == spec.usageContext.projectId.toString() }

        mvc.perform(
            post("/api/v1/runner/v2/jobs/$jobId/uploads").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"../../opencode log.txt","contentType":"text/plain","sizeBytes":1024}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.method").value("PUT"))
            .andExpect(jsonPath("$.blobKey").value(org.hamcrest.Matchers.startsWith("agent-jobs/$jobId/uploads/")))
            .andExpect(jsonPath("$.blobKey").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(".."))))

        val result = """{"status":"SUCCEEDED","summary":"done","branch":"feature/shop-1","commitSha":"abc","diff":"diff --git a/x b/x",
            "artifacts":[],"usage":{"inputTokens":100,"outputTokens":20,"costUsd":0.25}}"""
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/result").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON).content(result))
            .andExpect(status().isAccepted)
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/result").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON).content(result))
            .andExpect(status().isConflict)
        // a job token of this job cannot post for another job
        mvc.perform(post("/api/v1/internal/runner/jobs/${UUID.randomUUID()}/events").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON).content("[]"))
            .andExpect(status().isUnauthorized)

        complete(runnerToken, jobId, "SUCCEEDED").andExpect(status().isAccepted)

        val stored = runner.result(jobId)!!
        assertThat(stored.status).isEqualTo(RunnerJobStatus.SUCCEEDED)
        assertThat(stored.diff).isEqualTo("diff --git a/x b/x")
        assertThat(runner.verify(jobToken)).isNull() // token expires with the terminal state
        val enqueued = workQueue.requests.single { it.dedupeKey == "RUN_ADVANCE:${spec.usageContext.runId}" }
        assertThat(enqueued.kind).isEqualTo(WorkKinds.RUN_ADVANCE)
        assertThat(enqueued.payload).containsEntry("jobId", jobId.toString()).containsEntry("status", "SUCCEEDED")
            .containsEntry("runId", spec.usageContext.runId.toString())
        val view = adminJobs.get(jobId)
        assertThat(view.status).isEqualTo(AgentJobStatus.SUCCEEDED)
        assertThat(view.cost.amount).isEqualByComparingTo("0.25")
        assertThat(view.egressDenied).containsExactly("evil.example.com:443")
        assertThat(view.policySources).containsEntry("resources.cpu", "POOL").containsEntry("resources.memory", "TEMPLATE")
        assertThat(audit.operations()).contains("RUNNER_POOL_CREATE", "RUNNER_REGISTRATION_TOKEN_CREATE", "RUNNER_REGISTER")

        // heartbeats after the end are refused so the controller stops the sandbox
        heartbeat(runnerToken, jobId).andExpect(status().isConflict)
    }

    @Test
    fun `the coding agent gets a gateway-only token that cannot post or forge the job result (ADR 0029)`() {
        val runnerToken = register()
        val spec = spec()
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        val jobToken = lease(runnerToken).single()["jobToken"] as String
        heartbeat(runnerToken, jobId).andExpect(status().isOk)

        val response = mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/agent-token").header("Authorization", "Bearer $jobToken"))
            .andExpect(status().isCreated).andExpect(jsonPath("$.scope").value("GATEWAY")).andReturn().response.contentAsString
        val agentToken = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(response)!!.groupValues[1]
        assertThat(agentToken).startsWith("bo_job_").isNotEqualTo(jobToken)

        // the LLM proxy accepts it (same job, same model allowlist and caps), with the gateway scope
        val agentCredential = runner.verify(agentToken)!!
        assertThat(agentCredential.jobId).isEqualTo(jobId.value)
        assertThat(agentCredential.scope).isEqualTo(JobTokenScope.GATEWAY)
        assertThat(agentCredential.maxRequests).isNotNull()
        assertThat(runner.verify(jobToken)!!.scope).isEqualTo(JobTokenScope.FULL)

        // ...but none of the callbacks: a forged SUCCEEDED result, events and uploads are refused
        val forged = """{"status":"SUCCEEDED","summary":"all tests pass","diff":"diff --git a/README.md b/README.md","artifacts":[]}"""
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/result").header("Authorization", "Bearer $agentToken").contentType(MediaType.APPLICATION_JSON).content(forged))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/events").header("Authorization", "Bearer $agentToken").contentType(MediaType.APPLICATION_JSON).content("[]"))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/runner/v2/jobs/$jobId/uploads").header("Authorization", "Bearer $agentToken").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"x","contentType":"text/plain","sizeBytes":1}""")).andExpect(status().isUnauthorized)
        // nor can it mint further tokens; the runner gets one agent token per lease
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/agent-token").header("Authorization", "Bearer $agentToken")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/agent-token").header("Authorization", "Bearer $jobToken")).andExpect(status().isConflict)
        assertThat(runner.result(jobId)).isNull()

        // the runner's own result still goes through, and the end of the job revokes both tokens
        mvc.perform(post("/api/v1/internal/runner/jobs/$jobId/result").header("Authorization", "Bearer $jobToken").contentType(MediaType.APPLICATION_JSON)
            .content("""{"status":"FAILED","error":"verify failed","artifacts":[]}""")).andExpect(status().isAccepted)
        complete(runnerToken, jobId, "SUCCEEDED").andExpect(status().isAccepted)
        assertThat(runner.verify(agentToken)).isNull()
        assertThat(runner.verify(jobToken)).isNull()
    }

    @Test
    fun `an expired lease is re-queued once, a second loss ends the job LOST`() {
        val runnerToken = register()
        val spec = spec()
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        val first = lease(runnerToken).single()
        heartbeat(runnerToken, jobId).andExpect(status().isOk)

        clock.advance(Duration.ofMinutes(2))
        val scan = maintenance.scan()
        assertThat(scan.ran).isTrue()
        assertThat(runner.verify(first["jobToken"] as String)).isNull()
        assertThat(adminJobs.get(jobId).status).isEqualTo(AgentJobStatus.QUEUED)
        // the old lease holder is told to stop
        heartbeat(runnerToken, jobId).andExpect(status().isConflict)

        val second = lease(runnerToken).single()
        assertThat(second["jobToken"]).isNotEqualTo(first["jobToken"])
        heartbeat(runnerToken, jobId).andExpect(status().isOk)
        clock.advance(Duration.ofMinutes(2))
        maintenance.scan()

        val view = adminJobs.get(jobId)
        assertThat(view.status).isEqualTo(AgentJobStatus.LOST)
        assertThat(view.reason).contains("heartbeat-timeout")
        assertThat(runner.result(jobId)!!.status).isEqualTo(RunnerJobStatus.FAILED)
        assertThat(workQueue.requests.single { it.dedupeKey == "RUN_ADVANCE:${spec.usageContext.runId}" }.payload)
            .containsEntry("status", "FAILED").containsEntry("agentJobStatus", "LOST")
    }

    @Test
    fun `a running job that may have pushed is not retried`() {
        val runnerToken = register()
        val spec = spec(push = true)
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        lease(runnerToken).single()
        heartbeat(runnerToken, jobId).andExpect(status().isOk)
        clock.advance(Duration.ofMinutes(2))
        maintenance.scan()
        assertThat(adminJobs.get(jobId).status).isEqualTo(AgentJobStatus.LOST)
    }

    @Test
    fun `cancel flags a leased job and ends a queued one at once`() {
        val runnerToken = register()
        val leasedSpec = spec()
        val leasedId = runner.submit(leasedSpec, continuation(leasedSpec.usageContext.runId!!))
        lease(runnerToken).single()
        heartbeat(runnerToken, leasedId, "STARTING").andExpect(status().isOk).andExpect(jsonPath("$.cancelRequested").value(false))

        adminJobs.cancel(leasedId)
        heartbeat(runnerToken, leasedId).andExpect(status().isOk).andExpect(jsonPath("$.cancelRequested").value(true))
        complete(runnerToken, leasedId, "CANCELLED").andExpect(status().isAccepted)
        assertThat(adminJobs.get(leasedId).status).isEqualTo(AgentJobStatus.CANCELLED)
        assertThat(runner.result(leasedId)!!.status).isEqualTo(RunnerJobStatus.CANCELLED)

        val queuedSpec = spec()
        val queuedId = runner.submit(queuedSpec, continuation(queuedSpec.usageContext.runId!!))
        adminJobs.cancel(queuedId)
        assertThat(adminJobs.get(queuedId).status).isEqualTo(AgentJobStatus.CANCELLED)
        assertThat(workQueue.requests.single { it.dedupeKey == "RUN_ADVANCE:${queuedSpec.usageContext.runId}" }.payload).containsEntry("status", "CANCELLED")
    }

    @Test
    fun `completing SUCCEEDED without a result fails the job, and admin views explain waiting jobs`() {
        val spec = spec()
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        assertThat(adminJobs.get(jobId).reason).contains("no online runner in pool $poolId")
        assertThat(pools.get(poolId).queued).isEqualTo(1)

        val runnerToken = register()
        lease(runnerToken).single()
        complete(runnerToken, jobId, "SUCCEEDED").andExpect(status().isAccepted)
        val view = adminJobs.get(jobId)
        assertThat(view.status).isEqualTo(AgentJobStatus.FAILED)
        assertThat(view.reason).contains("without posting a result")
    }

    @Test
    fun `a job no pool can run waits unassigned with the reason, and admin endpoints need fleet permissions`() {
        val spec = spec().let { it.copy(policy = it.policy!!.copy(poolSelector = mapOf("suite" to "nobody"))) }
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        val view = adminJobs.get(jobId)
        assertThat(view.poolId).isNull()
        assertThat(view.reason).contains("no runner pool can run this job")

        gate.user = FakeAuthorizationGate.user()
        mvc.perform(get("/api/v1/admin/agent-jobs/$jobId")).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin/runner-pools/$poolId/registration-tokens")).andExpect(status().isForbidden)
        assertThat(gate.checked).contains(RunnerPermissions.FLEET_VIEW, RunnerPermissions.FLEET_MANAGE)
    }

    @Test
    fun `revoking a runner re-queues its jobs and rejects its token`() {
        val runnerToken = register()
        val spec = spec()
        val jobId = runner.submit(spec, continuation(spec.usageContext.runId!!))
        lease(runnerToken).single()
        val runnerId = UUID.fromString(json.readTree(mvc.perform(get("/api/v1/admin/runners").param("poolId", poolId)).andReturn().response.contentAsString).get(0).get("id").asString())
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/admin/runners/$runnerId")).andExpect(status().isNoContent)
        assertThat(adminJobs.get(RunnerJobId(jobId.value)).status).isEqualTo(AgentJobStatus.QUEUED)
        mvc.perform(get("/api/v1/runner/v2/config").header("Authorization", "Bearer $runnerToken")).andExpect(status().isUnauthorized)
    }
}
