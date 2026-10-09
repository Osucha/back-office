package pl.mos.backoffice.governance.adapter

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.mos.backoffice.governance.adapter.`in`.events.AttestationTrigger
import pl.mos.backoffice.governance.adapter.out.json.DsseJson
import pl.mos.backoffice.governance.adapter.out.json.JacksonJsonCodec
import pl.mos.backoffice.governance.application.port.`in`.ProvenanceQueries
import pl.mos.backoffice.governance.application.port.`in`.RecordAttestations
import pl.mos.backoffice.governance.domain.model.AttestationKind
import pl.mos.backoffice.governance.domain.service.Digests
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.provenance.ProvenanceArtifact
import pl.mos.backoffice.shared.provenance.ProvenanceGateDecision
import pl.mos.backoffice.shared.provenance.ProvenanceNode
import pl.mos.backoffice.shared.provenance.RunProvenance
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.usage.UsageRecord
import java.io.File
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Attestations end to end against PostgreSQL: a finished run with one agent job (evidence in `runner_job`)
 * produces a job attestation (commit + diff subjects) and a run attestation; creation is idempotent; the REST
 * endpoints return / verify the DSSE envelope; the sweep attests runs the events missed; the Node verification
 * script of docs/guides/provenance.md accepts the envelope and rejects a tampered copy.
 */
@SpringBootTest(classes = [GovernanceTestApplication::class])
@AutoConfigureMockMvc
@Import(GovernanceTestBeans::class)
class ProvenanceIntegrationTest {

    @Autowired lateinit var record: RecordAttestations
    @Autowired lateinit var queries: ProvenanceQueries
    @Autowired lateinit var source: FakeProvenanceSource
    @Autowired lateinit var ledger: FakeLedger
    @Autowired lateinit var audit: RecordingAudit
    @Autowired lateinit var gate: TestGate
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var trigger: AttestationTrigger
    private val json = JacksonJsonCodec()

    private val diff = "diff --git a/src/App.kt b/src/App.kt\n+fun hello() = 1\n"

    private fun seedRun(runId: UUID = UUID.randomUUID(), projectId: UUID = UUID.randomUUID()): RunProvenance {
        val job = UUID.randomUUID()
        val failedJob = UUID.randomUUID()
        val diffArtifact = UUID.randomUUID()
        val t0 = Instant.parse("2026-09-20T10:00:00Z")
        jdbc.sql(
            """INSERT INTO runner_job (id, label, agent, model_label, status, spec, result, created_at, started_at, finished_at)
               VALUES (:id, 'run-x-impl-2', 'CLAUDE_CODE', 'anthropic/claude-opus-5', 'SUCCEEDED', CAST(:spec AS jsonb), CAST(:result AS jsonb), :t, :t, :t)""",
        ).param("id", job).param("t", Timestamp.from(t0))
            .param("spec", """{"mode":"IMPLEMENT","agent":"CLAUDE_CODE","model":{"provider":"ANTHROPIC","modelId":"claude-opus-5","options":{"reasoning":"HIGH"}},"prompt":"Implement SHOP-1","constitution":"Be careful","verifyCommand":"./gradlew test","skills":[{"key":"kotlin","name":"Kotlin","content":"# Kotlin"}],"policy":{"isolation":"STANDARD","networkAllow":["repo.maven.apache.org"],"environment":{"image":"ghcr.io/org/runner:2.0"}}}""")
            .param("result", json.encodeToString(mapOf("status" to "SUCCEEDED", "commitSha" to "4f2a9c1e0b7d", "branch" to "feature/shop-1", "diff" to diff, "verification" to mapOf("command" to "./gradlew test", "exitCode" to 0))))
            .update()
        val nodes = listOf(
            ProvenanceNode(UUID.randomUUID(), "impl", "Implement", "TASK", "implement-claude", 1, "FAILED", "failure", t0, t0.plusSeconds(60), failedJob, TokenUsage(10, 1), Money.usd("0.10"), "boom", emptyList()),
            ProvenanceNode(UUID.randomUUID(), "impl", "Implement", "TASK", "implement-claude", 2, "SUCCEEDED", "success", t0.plusSeconds(60), t0.plusSeconds(600), job, TokenUsage(1000, 200), Money.usd("1.50"), null, listOf(diffArtifact)),
            ProvenanceNode(UUID.randomUUID(), "gate", "Review", "HUMAN_GATE", null, 1, "SUCCEEDED", "approve", t0.plusSeconds(600), t0.plusSeconds(900), null, TokenUsage.ZERO, Money.ZERO, null, emptyList()),
        )
        val run = RunProvenance(
            runId = runId, projectId = projectId, projectKey = "shop", repositoryUrl = "https://git.example.com/shop.git", workflowId = UUID.randomUUID(),
            workflowName = "Issue to MR", workflowVersion = 3, issueKey = "SHOP-1", status = "SUCCEEDED", outcome = "SUCCESS", terminal = true,
            startedBy = Actor(PrincipalKind.USER, UUID.randomUUID(), "Olivia Owner"), createdAt = t0, startedAt = t0, finishedAt = t0.plusSeconds(1000),
            cost = Money.usd("1.60"), mergeRequestUrl = "https://git.example.com/shop/-/merge_requests/7", branch = "feature/shop-1", nodes = nodes,
            decisions = listOf(ProvenanceGateDecision(UUID.randomUUID(), "gate", "approve", "LGTM", listOf("dod"), Actor(PrincipalKind.USER, UUID.randomUUID(), "Ada Approver"), t0.plusSeconds(900))),
            artifacts = listOf(ProvenanceArtifact(diffArtifact, "impl", "diff", "DIFF", "text/x-diff", Digests.sha256Hex(diff), diff.length.toLong(), t0.plusSeconds(600))),
        )
        source.runs[runId] = run
        ledger.record(UsageRecord(occurredAt = t0.plusSeconds(300), modelRef = ModelRef(Provider.ANTHROPIC, "claude-opus-5"), usage = TokenUsage(1200, 300), cost = Money.usd("1.75"), estimatedCost = Money.usd("1.75"), durationMs = 1, context = UsageContext(UsageKind.PIPELINE_STEP, projectId, runId, "impl")))
        return run
    }

    @Test
    fun `a finished run yields a job and a run attestation, idempotently`() {
        val run = seedRun()
        val created = gate.asSystem("test") { record.attestRun(run.runId) }
        assertThat(created.map { it.kind }).containsExactly(AttestationKind.AGENT_JOB, AttestationKind.RUN)
        assertThat(gate.asSystem("test") { record.attestRun(run.runId) }).isEmpty()
        assertThat(audit.entries.filter { it.details["runId"] == run.runId.toString() }.map { it.operation }).containsOnly("ATTESTATION_CREATE").hasSize(2)

        val job = created.first()
        assertThat(job.subjects.map { it.name }).containsExactly("git+https://git.example.com/shop.git@4f2a9c1e0b7d", "diff:${run.runId}/impl/2")
        assertThat(job.subjects[0].digest).containsEntry("sha256", Digests.sha256Hex("4f2a9c1e0b7d")).containsEntry("gitCommit", "4f2a9c1e0b7d")
        assertThat(job.subjects[1].digest).containsEntry("sha256", Digests.sha256Hex(diff))

        val statement = json.decodeObject(Base64.getDecoder().decode(job.envelope.payloadBase64))
        assertThat(statement["_type"]).isEqualTo("https://in-toto.io/Statement/v1")
        assertThat(statement["predicateType"]).isEqualTo("https://back-office.dev/attestations/agent-run/v1")
        @Suppress("UNCHECKED_CAST") val predicate = statement["predicate"] as Map<String, Any?>
        assertThat((predicate["model"] as Map<*, *>)["ref"]).isEqualTo("anthropic/claude-opus-5")
        assertThat((predicate["model"] as Map<*, *>)["effort"]).isEqualTo("HIGH")
        assertThat((predicate["prompts"] as Map<*, *>)["promptSha256"]).isEqualTo(Digests.sha256Hex("Implement SHOP-1"))
        assertThat(((predicate["prompts"] as Map<*, *>)["skills"] as Map<*, *>)["kotlin"]).isEqualTo(Digests.sha256Hex("# Kotlin"))
        assertThat((predicate["sandbox"] as Map<*, *>)["isolation"]).isEqualTo("STANDARD")
        assertThat((predicate["sandbox"] as Map<*, *>)["allowedHosts"]).isEqualTo(listOf("repo.maven.apache.org"))
        assertThat((predicate["sandbox"] as Map<*, *>)["policyDigest"].toString()).hasSize(64)
        assertThat((predicate["verification"] as Map<*, *>)["exitCode"]).isEqualTo(0)
        assertThat(((predicate["usage"] as Map<*, *>)["cost"] as Map<*, *>)["amount"]).isEqualTo("1.750000")
        assertThat((predicate["gateDecisions"] as List<*>)).isEmpty() // the gate was decided after the job finished

        val runStatement = json.decodeObject(Base64.getDecoder().decode(created[1].envelope.payloadBase64))
        @Suppress("UNCHECKED_CAST") val runPredicate = runStatement["predicate"] as Map<String, Any?>
        assertThat(runPredicate["kind"]).isEqualTo("RUN")
        assertThat(runPredicate["path"] as List<*>).hasSize(3)
        assertThat(runPredicate["jobAttestations"]).isEqualTo(listOf(job.id.toString()))
        assertThat(((runPredicate["gateDecisions"] as List<*>)[0] as Map<*, *>)["option"]).isEqualTo("approve")
        assertThat(created[1].subjects).isEqualTo(job.subjects)

        assertThat(queries.verify(job.envelope).valid).isTrue()
        writeSample(job.envelope)
    }

    @Test
    fun `REST returns the envelope as in-toto JSON, lists summaries and verifies`() {
        val run = seedRun()
        gate.asSystem("test") { record.attestRun(run.runId) }
        val list = mvc.get("/api/v1/runs/${run.runId}/attestations").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertThat(list).contains("\"kind\":\"AGENT_JOB\"", "\"kind\":\"RUN\"", "gitCommit")
        val id = queries.listForRun(run.runId).first().id
        val response = mvc.get("/api/v1/attestations/$id") { accept = MediaType.parseMediaType("application/vnd.in-toto+json") }
            .andExpect { status { isOk() }; content { contentTypeCompatibleWith("application/vnd.in-toto+json") } }.andReturn().response.contentAsString
        val envelope = json.decodeObject(response)
        assertThat(envelope.keys).containsExactly("payloadType", "payload", "signatures")
        val ok = mvc.post("/api/v1/attestations/verify") { contentType = MediaType.APPLICATION_JSON; content = response }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertThat(ok).contains("\"valid\":true", "in-toto.io/Statement/v1")
        val tampered = response.replace(envelope["payload"].toString(), Base64.getEncoder().encodeToString("{}".toByteArray()))
        val bad = mvc.post("/api/v1/attestations/verify") { contentType = MediaType.APPLICATION_JSON; content = tampered }.andReturn().response.contentAsString
        assertThat(bad).contains("\"valid\":false", "does not match")
        val keys = mvc.get("/.well-known/back-office/keys").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertThat(keys).contains("\"kty\":\"OKP\"", "\"crv\":\"Ed25519\"")
    }

    @Test
    fun `reading attestations needs project view on the run's project`() {
        val run = seedRun()
        gate.asSystem("test") { record.attestRun(run.runId) }
        assertThatThrownBy { gate.runAs(TestGate.viewer(UUID.randomUUID())) { queries.listForRun(run.runId) } }.isInstanceOf(InsufficientPermissionException::class.java)
        assertThat(gate.runAs(TestGate.viewer(run.projectId)) { queries.listForRun(run.runId) }).hasSize(2)
    }

    @Test
    fun `the sweep attests finished runs without a run attestation`() {
        val run = seedRun()
        jdbc.sql("INSERT INTO workflow_run (id, project_id, project_key, workflow_id, workflow_name, status, nodes, created_at, finished_at) VALUES (:id, :p, 'shop', :w, 'Issue to MR', 'SUCCEEDED', '[]'::jsonb, TIMESTAMPTZ '2025-01-01T00:00:00Z', now())")
            .param("id", run.runId).param("p", run.projectId).param("w", run.workflowId).update()
        assertThat(trigger.sweepNow()).isGreaterThanOrEqualTo(1)
        assertThat(queries.listForRun(run.runId).map { it.kind }).contains(AttestationKind.RUN)
        assertThat(trigger.sweepNow()).isZero()
    }

    /** Writes the envelope and the JWK set for the verification script and runs it when Node is installed. */
    private fun writeSample(envelope: pl.mos.backoffice.shared.platform.DsseEnvelope) {
        val dir = File("build/provenance-sample").apply { mkdirs() }
        val envelopeFile = File(dir, "attestation.intoto.json").apply { writeText(json.encodeToString(DsseJson.toJson(envelope))) }
        val keysFile = File(dir, "keys.json").apply { writeText(json.encodeToString(mapOf("keys" to queries.publicKeys()))) }
        val script = File("../../docs/guides/verify-attestation.mjs")
        val node = listOf("node", "node.exe").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        assumeTrue(script.exists() && node != null, "node or the script is not available")
        fun verify(file: File): Pair<Int, String> {
            val process = ProcessBuilder(node, script.path, file.path, keysFile.path).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            return process.exitValue() to out
        }
        val (code, output) = verify(envelopeFile)
        assertThat(code).describedAs(output).isEqualTo(0)
        assertThat(output).contains("OK", "git+https://git.example.com/shop.git@4f2a9c1e0b7d")
        val tampered = File(dir, "tampered.intoto.json").apply {
            writeText(json.encodeToString(DsseJson.toJson(envelope.copy(payloadBase64 = Base64.getEncoder().encodeToString("{\"_type\":\"x\"}".toByteArray())))))
        }
        assertThat(verify(tampered).first).isEqualTo(1)
    }
}
