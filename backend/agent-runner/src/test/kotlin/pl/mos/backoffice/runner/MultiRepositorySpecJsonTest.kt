package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.adapter.`in`.web.AssignmentSigner
import pl.mos.backoffice.runner.adapter.`in`.web.CanonicalJson
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.JobMergeRequest
import pl.mos.backoffice.shared.runner.JobRepository
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerMergeRequest
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * ADR 0034 contract: the control plane serialises a two-repository job exactly as the shared vector
 * "two repositories" of docs/api/fixtures/spec-digest-vectors.json (which the runner-controller verifies too), and
 * both documents tolerate the other side being older.
 */
class MultiRepositorySpecJsonTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val signer = AssignmentSigner(json) { null }

    private val title = "ACME-42: Export invoices"
    private val spec = RunnerJobSpec(
        mode = RunnerMode.IMPLEMENT,
        agent = AgentHarness.CLAUDE_CODE,
        model = ModelRef(Provider.ANTHROPIC, "claude-opus-5"),
        prompt = "Implement ACME-42.",
        repository = RunnerRepository(
            "https://gitlab.example.com/acme/app.git", "main", "feature/acme-42", push = true,
            mergeRequest = RunnerMergeRequest(true, title, null, "main", false),
        ),
        timeout = Duration.ofSeconds(3600),
        usageContext = UsageContext(UsageKind.PIPELINE_STEP),
        jobLabel = "run-1-implement-1",
        repositories = listOf(
            JobRepository("app", "https://gitlab.example.com/acme/app.git", "main", GitAccess.PUSH_MR, "app", "feature/acme-42", JobMergeRequest(true, title, null, false), primary = true),
            JobRepository("docs", "https://gitlab.example.com/acme/docs.git", "develop", GitAccess.READ, "docs"),
        ),
    )

    @Test
    fun `a two-repository spec serialises to the shared digest vector`() {
        val vector = json.readTree(File("../../docs/api/fixtures/spec-digest-vectors.json"))["vectors"].first { it["name"].stringValue().startsWith("two repositories") }
        @Suppress("UNCHECKED_CAST")
        val input = json.readValue(vector["inputJson"].stringValue(), Map::class.java) as Map<String, Any?>

        assertThat(spec.gitAccess).isEqualTo(GitAccess.PUSH_MR)
        val canonicalSpec = signer.canonical(RunnerJobSpecJson.of(spec))
        assertThat(CanonicalJson.write(mapOf("policy" to input["policy"], "spec" to canonicalSpec))).isEqualTo(vector["canonical"].stringValue())
        assertThat(AssignmentSigner.specDigest(canonicalSpec, input["policy"])).isEqualTo(vector["specDigest"].stringValue())
    }

    @Test
    fun `a spec without repositories omits the list so legacy digests do not change`() {
        @Suppress("UNCHECKED_CAST")
        val canonical = signer.canonical(RunnerJobSpecJson.of(spec.copy(repositories = emptyList(), gitAccess = GitAccess.PUSH_MR))) as Map<String, Any?>
        assertThat(canonical).doesNotContainKey("repositories")
    }

    @Test
    fun `stored specs and results round-trip and older documents read as one repository`() {
        val stored = json.writeValueAsString(RunnerJobSpecJson.of(spec))
        val back = json.readValue(stored, RunnerJobSpecJson::class.java).toSpec(spec.usageContext, spec.jobLabel, null)
        assertThat(back.repositories).isEqualTo(spec.repositories)
        assertThat(back.gitAccess).isEqualTo(GitAccess.PUSH_MR)

        // a spec stored before ADR 0031 / 0034: no list, no level - derived from the legacy repository
        val legacy = json.readValue(
            """{"mode":"IMPLEMENT","agent":"OPENCODE","model":{"provider":"ANTHROPIC","modelId":"m"},"prompt":"p","timeoutSeconds":60,"repository":{"url":"u","baseRef":"main","push":false}}""",
            RunnerJobSpecJson::class.java,
        ).toSpec(spec.usageContext, "l", null)
        assertThat(legacy.repositories).isEmpty()
        assertThat(legacy.gitAccess).isEqualTo(GitAccess.READ)

        val result = json.readValue(
            """{"status":"SUCCEEDED","branch":"feature/acme-42","commitSha":"a1","repositories":[{"key":"app","branch":"feature/acme-42","commitSha":"a1","diffStat":{"files":2,"insertions":3,"deletions":1},"mergeRequestUrl":"https://mr/1","pushed":true},{"key":"docs","pushed":false,"error":"clone failed"}]}""",
            RunnerResultJson::class.java,
        ).toRunnerResult(Instant.EPOCH)
        assertThat(result.repositories.map { "${it.key}:${it.pushed}:${it.diffStat?.insertions}:${it.error}" }).containsExactly("app:true:3:null", "docs:false:null:clone failed")
        assertThat(RunnerResultJson.of(result).repositories!!.map { it.key }).containsExactly("app", "docs")

        val old = json.readValue("""{"status":"SUCCEEDED","branch":"b","commitSha":"c"}""", RunnerResultJson::class.java).toRunnerResult(Instant.EPOCH)
        assertThat(old.repositories).isEmpty()
        assertThat(RunnerResultJson.of(old).repositories).isNull()
    }
}
