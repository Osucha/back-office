package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.crypto.hpke.HPKE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.runner.application.port.`in`.ManageRunnerPools
import pl.mos.backoffice.runner.application.port.`in`.RunnerPoolInput
import pl.mos.backoffice.runner.application.service.AgentJobService
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.JobCredentialRef
import pl.mos.backoffice.shared.runner.JobRepository
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.runner.SandboxResources
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

/**
 * ADR 0051: a job's project repository tokens travel only sealed to the key of the runner holding its lease, once per
 * lease; the signed spec names them, never contains them.
 */
@SpringBootTest(classes = [RunnerTestApplication::class])
@AutoConfigureMockMvc
@Import(RunnerTestBeans::class)
class SealedCredentialsIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: JsonMapper
    @Autowired lateinit var gate: FakeAuthorizationGate
    @Autowired lateinit var pools: ManageRunnerPools
    @Autowired lateinit var runner: AgentJobService
    @Autowired lateinit var audit: RecordingAuditPort

    private lateinit var poolId: String
    private val hpke = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)
    private val keys = hpke.generatePrivateKey()
    private val publicKey = Base64.getEncoder().encodeToString(hpke.serializePublicKey(keys.public))
    private val ref = UUID.randomUUID().toString()
    private val token = "tkn-project-read-token"

    @BeforeEach
    fun pool() {
        gate.user = FakeAuthorizationGate.user(platform = setOf(RunnerPermissions.FLEET_MANAGE, RunnerPermissions.FLEET_VIEW))
        poolId = "sealed-" + UUID.randomUUID().toString().take(8)
        pools.create(RunnerPoolInput(id = poolId, name = "Sealed $poolId", labels = mapOf("suite" to poolId), ceiling = SandboxPolicy(resources = SandboxResources(cpu = "1")), maxConcurrentJobs = 5))
        REPOSITORY_TOKENS[ref] = token
    }

    private fun spec(credentials: List<JobCredentialRef>) = RunnerJobSpec(
        mode = RunnerMode.IMPLEMENT,
        agent = AgentHarness.CLAUDE_CODE,
        model = ModelRef(Provider.ANTHROPIC, "claude-opus-5"),
        prompt = "fix CI",
        repositories = listOf(JobRepository("main", "https://gitlab.example.com/g/shop.git", "main", GitAccess.READ, primary = true)),
        usageContext = UsageContext(UsageKind.PIPELINE_STEP, projectId = UUID.randomUUID(), runId = UUID.randomUUID(), stepId = "analyse"),
        jobLabel = "job-${UUID.randomUUID()}",
        policy = SandboxPolicy(poolSelector = mapOf("suite" to poolId)),
        credentials = credentials,
    )

    private fun register(): String {
        val issued = pools.issueRegistrationToken(poolId)
        val body = """{"registrationToken":"${issued.token}","name":"ctrl-$poolId-${UUID.randomUUID().toString().take(4)}","version":"2.1.0","capabilities":{"backends":["DOCKER"],"maxConcurrent":5}}"""
        val response = mvc.perform(post("/api/v1/runner/v2/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response).get("runnerToken").asString()
    }

    private fun setKey(runnerToken: String, key: String): ResultActions = mvc.perform(
        put("/api/v1/runner/v2/key").header("Authorization", "Bearer $runnerToken").contentType(MediaType.APPLICATION_JSON).content("""{"publicKey":"$key"}"""),
    )

    private fun lease(runnerToken: String): JsonNode = json.readTree(
        mvc.perform(post("/api/v1/runner/v2/jobs/lease").header("Authorization", "Bearer $runnerToken").contentType(MediaType.APPLICATION_JSON).content("""{"maxJobs":1,"waitSeconds":0}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString,
    ).get(0)

    private fun credentials(runnerToken: String, jobId: String): ResultActions =
        mvc.perform(post("/api/v1/runner/v2/jobs/$jobId/credentials").header("Authorization", "Bearer $runnerToken"))

    @Test
    fun `the tokens named in the signed spec are sealed to the leasing runner's key, once, and open only with its private key`() {
        val runnerToken = register()
        setKey(runnerToken, "not-a-key").andExpect(status().isBadRequest)
        setKey(runnerToken, publicKey).andExpect(status().isNoContent)
        setKey(runnerToken, publicKey).andExpect(status().isNoContent) // idempotent

        val jobId = runner.start(spec(listOf(JobCredentialRef(ref, "main", "GIT_READ")))).toString()
        val assignment = lease(runnerToken)
        val signed = assignment.get("spec").get("credentials").get(0)
        assertThat(signed.get("ref").asString()).isEqualTo(ref)
        assertThat(assignment.toString()).doesNotContain(token)

        val answer = json.readTree(credentials(runnerToken, jobId).andExpect(status().isOk).andReturn().response.contentAsString)
        val sealed = answer.get("credentials").get(0)
        assertThat(sealed.get("repository").asString()).isEqualTo("main")
        assertThat(sealed.get("capability").asString()).isEqualTo("GIT_READ")
        assertThat(answer.toString()).doesNotContain(token)
        val aad = "$jobId|$ref|${answer.get("expiresAt").asString()}".toByteArray()
        val opened = hpke.open(
            Base64.getDecoder().decode(sealed.get("enc").asString()), keys, "back-office credentials v1".toByteArray(), aad,
            Base64.getDecoder().decode(sealed.get("ciphertext").asString()), null, null, null,
        )
        assertThat(String(opened)).isEqualTo(token)
        // another job id in the associated data does not open it
        val wrongAad = "${UUID.randomUUID()}|$ref|${answer.get("expiresAt").asString()}".toByteArray()
        assertThat(runCatching {
            hpke.open(Base64.getDecoder().decode(sealed.get("enc").asString()), keys, "back-office credentials v1".toByteArray(), wrongAad,
                Base64.getDecoder().decode(sealed.get("ciphertext").asString()), null, null, null)
        }.isFailure).isTrue()

        credentials(runnerToken, jobId).andExpect(status().isConflict) // once per lease
        val other = register()
        setKey(other, publicKey).andExpect(status().isNoContent)
        credentials(other, jobId).andExpect(status().isConflict) // not this runner's lease

        val released = audit.entries.single { it.operation == "RUNNER_CREDENTIALS_RELEASED" && it.entityId == jobId }
        assertThat(released.details.toString()).contains(ref).doesNotContain(token)
    }

    @Test
    fun `a job without project tokens gets none, a runner without a key and a vanished token are refused`() {
        val runnerToken = register()
        val plain = runner.start(spec(emptyList())).toString()
        assertThat(lease(runnerToken).get("spec").has("credentials")).isFalse()
        val none = json.readTree(credentials(runnerToken, plain).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(none.get("credentials").size()).isZero()

        val keyless = runner.start(spec(listOf(JobCredentialRef(ref, "main", "GIT_READ")))).toString()
        lease(runnerToken)
        credentials(runnerToken, keyless).andExpect(status().isConflict)

        setKey(runnerToken, publicKey).andExpect(status().isNoContent)
        val gone = runner.start(spec(listOf(JobCredentialRef("missing", "main", "GIT_READ")))).toString()
        lease(runnerToken)
        credentials(runnerToken, gone).andExpect(status().isConflict)
        // the failed release did not use up the lease's release
        REPOSITORY_TOKENS["missing"] = "tkn-late"
        credentials(runnerToken, gone).andExpect(status().isOk)
    }
}
