package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.runner.application.port.`in`.ManageAgentJobs
import pl.mos.backoffice.runner.application.port.`in`.ManagePersonalRunner
import pl.mos.backoffice.runner.application.port.`in`.ManageRunnerPools
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolInput
import pl.mos.backoffice.runner.application.service.AgentJobService
import pl.mos.backoffice.runner.application.service.PersonalRunnerService
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** ADR 0040 against PostgreSQL: personal pool self-service, routing to the owner's runner and LOCAL_LOGIN assignments. */
@SpringBootTest(classes = [RunnerTestApplication::class])
@AutoConfigureMockMvc
@Import(RunnerTestBeans::class)
class PersonalRunnerIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var personal: ManagePersonalRunner
    @Autowired lateinit var pools: ManageRunnerPools
    @Autowired lateinit var adminJobs: ManageAgentJobs
    @Autowired lateinit var runner: AgentJobService
    @Autowired lateinit var runnerAdmin: pl.mos.backoffice.runner.application.service.FleetAdminService
    @Autowired lateinit var loginModels: RecordingLoginModels

    private lateinit var me: UUID

    @BeforeEach
    fun signIn() {
        me = UUID.randomUUID()
        signInAs(me)
    }

    private fun signInAs(userId: UUID) {
        gate.user = FakeAuthorizationGate.user(platform = setOf(RunnerPermissions.FLEET_PERSONAL)).copy(userId = UserId(userId))
    }

    private fun asAdmin(block: () -> Unit) {
        val saved = gate.user
        gate.user = FakeAuthorizationGate.user(platform = setOf(RunnerPermissions.FLEET_MANAGE, RunnerPermissions.FLEET_VIEW))
        try {
            block()
        } finally {
            gate.user = saved
        }
    }

    private fun spec(owner: UUID?, selector: Map<String, String> = emptyMap()) = RunnerJobSpec(
        mode = RunnerMode.IMPLEMENT,
        agent = AgentHarness.CLAUDE_CODE,
        model = ModelRef(Provider.ANTHROPIC, "claude-opus-5"),
        prompt = "implement SHOP-2",
        repository = RunnerRepository("https://gitlab.example.com/g/shop.git", "main", "feature/shop-2", push = false),
        usageContext = UsageContext(UsageKind.PIPELINE_STEP, projectId = UUID.randomUUID(), runId = UUID.randomUUID(), stepId = "implement"),
        jobLabel = "job-${UUID.randomUUID()}",
        policy = SandboxPolicy(poolSelector = selector),
        personalRunnerOf = owner,
    )

    private fun continuation() = WorkRequest(WorkKinds.RUN_ADVANCE, mapOf("runId" to UUID.randomUUID().toString()))

    private fun registerPersonal(): String {
        val issued = personal.issueRegistrationToken()
        val body = """{"registrationToken":"${issued.token}","name":"laptop","version":"2.0.0","capabilities":{"backends":["DOCKER"],"maxConcurrent":2}}"""
        val response = mvc.perform(post("/api/v1/runner/v2/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response).get("runnerToken").asString()
    }

    @Test
    fun `a local stack's shared pool and registration token come from the configuration, reusable after every start (ADR 0046)`() {
        val poolId = "local-${UUID.randomUUID().toString().take(8)}"
        val token = "bo_rrt_local-${UUID.randomUUID()}"
        val admin = runnerAdmin
        admin.bootstrapLocalFleet(poolId, token, listOf("back-office/runner:*"), 2)
        admin.bootstrapLocalFleet(poolId, token, listOf("back-office/runner:*"), 2)
        fun registerWith(name: String) = mvc.perform(
            post("/api/v1/runner/v2/register").contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationToken":"$token","name":"$name","version":"2.0.0","capabilities":{"backends":["DOCKER"],"maxConcurrent":2}}"""),
        )
        registerWith("compose").andExpect(status().isCreated)
        // used once; the next start of the API arms it again (a controller that lost its volume registers anew)
        registerWith("compose-again").andExpect(status().isUnauthorized)
        admin.bootstrapLocalFleet(poolId, token, listOf("back-office/runner:*"), 2)
        registerWith("compose-again").andExpect(status().isCreated)

        asAdmin { assertThat(pools.list().map { it.input.id }).contains(poolId) }
        val targets = runner.executionTargets(me).associateBy { it.target }
        assertThat(targets.getValue(pl.mos.backoffice.shared.runner.RunnerTarget.SHARED).onlineRunners).isGreaterThanOrEqualTo(1)
        assertThat(targets.getValue(pl.mos.backoffice.shared.runner.RunnerTarget.PERSONAL).pools).isZero()
    }

    @Suppress("UNCHECKED_CAST")
    private fun lease(runnerToken: String, report: String = ""): List<Map<String, Any?>> {
        val text = mvc.perform(
            post("/api/v1/runner/v2/jobs/lease").header("Authorization", "Bearer $runnerToken")
                .contentType(MediaType.APPLICATION_JSON).content("""{"maxJobs":5,"waitSeconds":0$report}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(text, List::class.java) as List<Map<String, Any?>>
    }

    @Test
    fun `a runner reports its coding agents and Claude login with the lease, the first login creates the owner's login models (ADR 0050)`() {
        personal.enable(LlmCredentials.LOCAL_LOGIN)
        val runnerToken = registerPersonal()
        fun target() = runner.executionTargets(me).single { it.target == pl.mos.backoffice.shared.runner.RunnerTarget.PERSONAL }

        lease(runnerToken)
        assertThat(target().claudeLogin).describedAs("not reported").isNull()

        lease(runnerToken, ""","harnesses":["OPENCODE","CLAUDE_CODE"],"claudeLogin":false""")
        assertThat(target().let { it.claudeLogin to it.harnesses }).isEqualTo(false to listOf("OPENCODE", "CLAUDE_CODE"))
        assertThat(loginModels.users).doesNotContain(me)

        lease(runnerToken, ""","harnesses":["OPENCODE","CLAUDE_CODE"],"claudeLogin":true""")
        lease(runnerToken, ""","harnesses":["OPENCODE","CLAUDE_CODE"],"claudeLogin":true""")
        assertThat(target().claudeLogin).isTrue()
        assertThat(loginModels.users.filter { it == me }).describedAs("once, when the login appears").hasSize(1)
    }

    @Test
    fun `the personal pool is created once, keeps its id and changes credentials`() {
        assertThat(personal.mine()).isNull()
        val created = personal.enable(LlmCredentials.LOCAL_LOGIN)
        assertThat(created.poolId).isEqualTo(RunnerPool.personalId(me)).matches(RunnerPool.ID.toPattern())
        assertThat(created.llmCredentials).isEqualTo(LlmCredentials.LOCAL_LOGIN)
        assertThat(personal.enable(LlmCredentials.LOCAL_LOGIN).poolId).isEqualTo(created.poolId)
        assertThat(personal.enable(LlmCredentials.GATEWAY).llmCredentials).isEqualTo(LlmCredentials.GATEWAY)
        asAdmin {
            val view = pools.get(created.poolId)
            assertThat(view.ownerUserId).isEqualTo(me)
            assertThat(view.llmCredentials).isEqualTo(LlmCredentials.GATEWAY)
        }
        personal.disable()
        assertThat(personal.mine()).isNull()
    }

    @Test
    fun `the docker command carries the token and names the secrets without their values`() {
        personal.enable(LlmCredentials.LOCAL_LOGIN)
        val issued = personal.issueRegistrationToken()
        assertThat(issued.token).startsWith("bo_rrt_")
        assertThat(issued.dockerCommand)
            .contains("-e RUNNER_REGISTRATION_TOKEN=${issued.token}", "-e RUNNER_BACKOFFICE_URL=http://api.test:8080", "-e RUNNER_BACKEND=DOCKER")
            .contains("-e CLAUDE_CODE_OAUTH_TOKEN \\", "-v /var/run/docker.sock:/var/run/docker.sock", "back-office/runner-controller:dev")
            .doesNotContain("host.docker.internal")
        personal.enable(LlmCredentials.GATEWAY)
        assertThat(personal.issueRegistrationToken().dockerCommand).doesNotContain("CLAUDE_CODE_OAUTH_TOKEN")
    }

    @Test
    fun `a local Back Office is reached through host docker internal`() {
        assertThat(PersonalRunnerService.isLocalHost("http://localhost:8080")).isTrue()
        assertThat(PersonalRunnerService.isLocalHost("https://bo.example.com")).isFalse()
        assertThat(PersonalRunnerService.withHost("http://localhost:8080/x", "host.docker.internal")).isEqualTo("http://host.docker.internal:8080/x")
    }

    @Test
    fun `jobs for the owner go to the personal pool with LOCAL_LOGIN and Anthropic egress, others never do`() {
        val poolId = personal.enable(LlmCredentials.LOCAL_LOGIN).poolId
        val runnerToken = registerPersonal()
        assertThat(personal.mine()!!.runners).singleElement().matches { it.name == "laptop" }

        val sharedId = "it-shared-" + UUID.randomUUID().toString().take(8)
        val selector = mapOf("suite" to sharedId)
        asAdmin { pools.create(RunnerPoolInput(id = sharedId, name = "Shared $sharedId", labels = selector)) }

        val mine = runner.submit(spec(me), continuation())
        val someoneElse = runner.submit(spec(UUID.randomUUID(), selector), continuation())
        val team = runner.submit(spec(null, selector), continuation())
        asAdmin {
            assertThat(adminJobs.get(mine).poolId).isEqualTo(poolId)
            assertThat(adminJobs.get(someoneElse).poolId).isEqualTo(sharedId)
            assertThat(adminJobs.get(team).poolId).isEqualTo(sharedId)
        }

        val assignment = lease(runnerToken).single()
        assertThat(assignment["jobId"]).isEqualTo(mine.toString())
        @Suppress("UNCHECKED_CAST")
        val spec = assignment["spec"] as Map<String, Any?>
        assertThat(spec["llmCredentials"]).isEqualTo("LOCAL_LOGIN")
        assertThat(spec).doesNotContainKey("personalRunnerOf")
        @Suppress("UNCHECKED_CAST")
        assertThat(((assignment["policy"] as Map<String, Any?>)["network"] as Map<String, Any?>)["allow"] as List<String>).contains("api.anthropic.com")

        assertThatThrownBy { personal.disable() }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `a runner of another user cannot be revoked and admin ids cannot take the personal prefix`() {
        personal.enable(LlmCredentials.LOCAL_LOGIN)
        registerPersonal()
        val runnerId = personal.mine()!!.runners.single().id
        signInAs(UUID.randomUUID())
        personal.enable(LlmCredentials.GATEWAY)
        assertThatThrownBy { personal.revokeRunner(runnerId) }.isInstanceOf(NotFoundException::class.java)
        signInAs(me)
        personal.revokeRunner(runnerId)
        assertThat(personal.mine()!!.runners).isEmpty()
        asAdmin {
            assertThatThrownBy { pools.create(RunnerPoolInput(id = "personal-x", name = "x")) }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `REST me runner`() {
        mvc.perform(get("/api/v1/me/runner")).andExpect(status().isNoContent)
        mvc.perform(put("/api/v1/me/runner").contentType(MediaType.APPLICATION_JSON).content("""{"llmCredentials":"API_KEY"}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(put("/api/v1/me/runner").contentType(MediaType.APPLICATION_JSON).content("""{"llmCredentials":"LOCAL_LOGIN"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.llmCredentials").value("LOCAL_LOGIN")).andExpect(jsonPath("$.runners").isEmpty)
        mvc.perform(post("/api/v1/me/runner/registration-tokens")).andExpect(status().isCreated)
            .andExpect(jsonPath("$.dockerCommand").value(org.hamcrest.Matchers.containsString("docker run")))
        mvc.perform(get("/api/v1/me/runner")).andExpect(status().isOk).andExpect(jsonPath("$.poolId").value(RunnerPool.personalId(me)))
    }
}
