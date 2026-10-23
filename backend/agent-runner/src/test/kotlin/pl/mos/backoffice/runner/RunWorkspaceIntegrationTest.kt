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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.runner.application.port.`in`.ManageRunnerPools
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolInput
import pl.mos.backoffice.runner.application.service.AgentJobService
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerWorkspace
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * ADR 0044 against PostgreSQL: the jobs of a run workspace go to the runner that holds it, one at a time; the
 * workspace sync releases what Back Office no longer needs; a released workspace starts over on any runner.
 */
@SpringBootTest(classes = [RunnerTestApplication::class])
@AutoConfigureMockMvc
@Import(RunnerTestBeans::class)
class RunWorkspaceIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var pools: ManageRunnerPools
    @Autowired lateinit var runner: AgentJobService
    @Autowired lateinit var runners: pl.mos.backoffice.runner.application.port.`in`.ManageRunners

    private lateinit var poolId: String
    private lateinit var selector: Map<String, String>
    private val runId: UUID = UUID.randomUUID()
    private val workspaceName = RunnerWorkspace.nameFor(runId, "main")

    @BeforeEach
    fun pool() {
        gate.user = FakeAuthorizationGate.user(platform = setOf(RunnerPermissions.FLEET_MANAGE, RunnerPermissions.FLEET_VIEW))
        poolId = "ws-" + UUID.randomUUID().toString().take(8)
        selector = mapOf("suite" to poolId)
        pools.create(RunnerPoolInput(id = poolId, name = "Workspaces $poolId", labels = selector, maxConcurrentJobs = 5))
    }

    private fun spec(step: String, workspace: Boolean = true) = RunnerJobSpec(
        mode = RunnerMode.IMPLEMENT,
        agent = AgentHarness.CLAUDE_CODE,
        model = ModelRef(Provider.ANTHROPIC, "claude-opus-5"),
        prompt = "work",
        usageContext = UsageContext(UsageKind.PIPELINE_STEP, projectId = UUID.randomUUID(), runId = runId, stepId = step),
        jobLabel = "job-${UUID.randomUUID()}",
        policy = SandboxPolicy(poolSelector = selector),
        workspace = if (workspace) RunnerWorkspace("main", workspaceName, sessionRole = step) else null,
    )

    private fun submit(step: String) = runner.submit(spec(step), WorkRequest(WorkKinds.RUN_ADVANCE, mapOf("runId" to runId.toString()), dedupeKey = "RUN_ADVANCE:$runId:$step"))

    private fun register(name: String): String = registerWithId(name).first

    /** Runner token and runner id. */
    private fun registerWithId(name: String): Pair<String, UUID> {
        val issued = pools.issueRegistrationToken(poolId)
        val body = """{"registrationToken":"${issued.token}","name":"$name-$poolId","version":"2.0.0",
            "capabilities":{"backends":["DOCKER"],"runtimeClasses":[],"maxConcurrent":3}}"""
        val response = mvc.perform(post("/api/v1/runner/v2/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val node = json.readTree(response)
        return node.get("runnerToken").asString() to UUID.fromString(node.get("runnerId").asString())
    }

    private fun lease(token: String): List<Map<String, Any?>> {
        val text = mvc.perform(
            post("/api/v1/runner/v2/jobs/lease").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"maxJobs":5,"waitSeconds":0}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        return json.readValue(text, List::class.java) as List<Map<String, Any?>>
    }

    private fun complete(token: String, jobId: Any?) {
        mvc.perform(
            post("/api/v1/runner/v2/jobs/$jobId/complete").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"FAILED","exitCode":1,"reason":"test"}"""),
        ).andExpect(status().isAccepted)
    }

    private fun sync(token: String, vararg held: String): List<String> {
        val body = json.writeValueAsString(mapOf("held" to held.toList()))
        val text = mvc.perform(
            post("/api/v1/runner/v2/workspaces/sync").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        return (json.readValue(text, Map::class.java)["release"] as List<String>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun workspaceOf(assignment: Map<String, Any?>): Any? = ((assignment["spec"] as Map<String, Any?>)["workspace"] as Map<String, Any?>)["name"]

    @Test
    fun `jobs of a workspace stay on its runner, one at a time, until it is released`() {
        val a = register("a")
        val b = register("b")
        val first = submit("implementer")
        val leasedA = lease(a)
        assertThat(leasedA).hasSize(1)
        assertThat(leasedA[0]["jobId"]).isEqualTo(first.value.toString())
        assertThat(workspaceOf(leasedA[0])).isEqualTo(workspaceName)

        // the second step waits while the first one uses the workspace, on either runner
        val second = submit("reviewer")
        assertThat(lease(b)).isEmpty()
        assertThat(lease(a)).isEmpty()

        complete(a, first.value)
        // the workspace lives on runner a: b never gets its jobs
        assertThat(lease(b)).isEmpty()
        val next = lease(a)
        assertThat(next.map { it["jobId"] }).containsExactly(second.value.toString())

        // sync: a keeps its workspace, deletes volumes nobody knows; b is told to drop a stale copy
        val stranger = RunnerWorkspace.nameFor(UUID.randomUUID(), "main")
        assertThat(sync(a, workspaceName, stranger)).containsExactly(stranger)
        assertThat(sync(b, workspaceName)).containsExactly(workspaceName)

        // the run ends: the workspace is released at a's next sync (not while the job still runs)
        runner.releaseWorkspaces(runId, Duration.ZERO)
        assertThat(sync(a, workspaceName)).isEmpty()
        complete(a, second.value)
        assertThat(sync(a, workspaceName)).containsExactly(workspaceName)

        // a later job of the same workspace starts over and may go to any runner of the pool
        val third = submit("implementer-2")
        assertThat(lease(b).map { it["jobId"] }).containsExactly(third.value.toString())
    }

    @Test
    fun `the same computer connected again (old runner revoked) adopts its workspace and continues the queued job`() {
        val (old, oldId) = registerWithId("laptop")
        val first = submit("implementer")
        assertThat(lease(old).map { it["jobId"] }).containsExactly(first.value.toString())
        complete(old, first.value)
        val retry = submit("implementer-retry")

        // the owner connects the computer again: the old runner is revoked, a new one registers in the same pool
        runners.revoke(oldId)
        val (fresh, _) = registerWithId("laptop-again")
        val other = register("someone-else")
        // the new runner holds the volume (same Docker host): it is adopted, not deleted
        assertThat(sync(fresh, workspaceName)).isEmpty()
        // once adopted, another runner reporting a stale copy is told to delete it, as before
        assertThat(sync(other, workspaceName)).containsExactly(workspaceName)
        assertThat(lease(other)).isEmpty()
        val continued = lease(fresh)
        assertThat(continued.map { it["jobId"] }).containsExactly(retry.value.toString())
        assertThat(workspaceOf(continued[0])).isEqualTo(workspaceName)
    }

    @Test
    fun `a job whose workspace runner was revoked is not stuck, even before any sync`() {
        val (old, oldId) = registerWithId("laptop")
        val first = submit("implementer")
        lease(old)
        complete(old, first.value)
        val retry = submit("implementer-retry")
        runners.revoke(oldId)
        val fresh = register("laptop-again")
        assertThat(lease(fresh).map { it["jobId"] }).containsExactly(retry.value.toString())
    }

    @Test
    fun `jobs without a workspace are leased as before`() {
        val a = register("a")
        runner.submit(spec("plain", workspace = false), WorkRequest(WorkKinds.RUN_ADVANCE, mapOf("runId" to runId.toString()), dedupeKey = "RUN_ADVANCE:$runId:plain"))
        val leased = lease(a)
        assertThat(leased).hasSize(1)
        @Suppress("UNCHECKED_CAST")
        assertThat((leased[0]["spec"] as Map<String, Any?>)).doesNotContainKey("workspace")
    }
}
