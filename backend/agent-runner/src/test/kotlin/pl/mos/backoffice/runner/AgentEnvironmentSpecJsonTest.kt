package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.adapter.`in`.web.AssignmentSigner
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * ADR 0057: `RunnerJobSpec.instructions` (the environment document) and `mcpServers` are covered by the spec digest when
 * present and absent (never `""` or `[]`) when not, so the digests of otherwise unchanged specs stay the same.
 */
class AgentEnvironmentSpecJsonTest {
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val signer = AssignmentSigner(json) { null }

    private val spec = RunnerJobSpec(
        mode = RunnerMode.IMPLEMENT,
        agent = AgentHarness.CLAUDE_CODE,
        model = ModelRef(Provider.ANTHROPIC, "claude-opus-5"),
        prompt = "Implement ACME-42.",
        usageContext = UsageContext(UsageKind.PIPELINE_STEP),
        jobLabel = "run-1-implement-1",
    )

    @Suppress("UNCHECKED_CAST")
    private fun canonical(s: RunnerJobSpec) = signer.canonical(RunnerJobSpecJson.of(s)) as Map<String, Any?>

    @Test
    fun `a spec without environment omits both fields so older digests do not change`() {
        assertThat(canonical(spec)).doesNotContainKeys("instructions", "mcpServers")
        assertThat(canonical(spec.copy(instructions = "  "))).describedAs("a blank document is no document").doesNotContainKey("instructions")
        assertThat(AssignmentSigner.specDigest(canonical(spec.copy(instructions = null, mcpServers = emptyList())), null))
            .isEqualTo(AssignmentSigner.specDigest(canonical(spec), null))
    }

    @Test
    fun `instructions and MCP servers are part of the digest and round-trip`() {
        val full = spec.copy(instructions = "# Working in Back Office\nRules.", mcpServers = listOf(AgentMcpServer("docs", "https://mcp.example.com/docs")))
        val canonical = canonical(full)
        assertThat(canonical["instructions"]).isEqualTo("# Working in Back Office\nRules.")
        assertThat(canonical["mcpServers"]).isEqualTo(listOf(mapOf("name" to "docs", "url" to "https://mcp.example.com/docs")))
        val digest = AssignmentSigner.specDigest(canonical, null)
        assertThat(digest).isNotEqualTo(AssignmentSigner.specDigest(canonical(spec), null))
        assertThat(AssignmentSigner.specDigest(canonical(full.copy(instructions = "other")), null)).isNotEqualTo(digest)

        val stored = json.writeValueAsString(RunnerJobSpecJson.of(full))
        val back = json.readValue(stored, RunnerJobSpecJson::class.java).toSpec(full.usageContext, full.jobLabel, null)
        assertThat(back.instructions).isEqualTo(full.instructions)
        assertThat(back.mcpServers).isEqualTo(full.mcpServers)
        val older = json.readValue(json.writeValueAsString(RunnerJobSpecJson.of(spec)), RunnerJobSpecJson::class.java).toSpec(spec.usageContext, spec.jobLabel, null)
        assertThat(older.instructions).isNull()
        assertThat(older.mcpServers).isEmpty()
    }

    @Test
    fun `ADR 0060 - agentHome and a non-FULL access are signed and round-trip, FULL and no home are omitted`() {
        assertThat(canonical(spec)).doesNotContainKeys("agentHome", "access")
        assertThat(canonical(spec.copy(access = AgentAccess.FULL))).describedAs("FULL is never sent").doesNotContainKey("access")
        val paths = AgentAccess(AgentAccessMode.PATHS, listOf("docs/**"))
        val homed = spec.copy(agentHome = "doc-writer", access = paths)
        val canonical = canonical(homed)
        assertThat(canonical["agentHome"]).isEqualTo("doc-writer")
        assertThat(canonical["access"]).isEqualTo(mapOf("mode" to "PATHS", "paths" to listOf("docs/**")))
        assertThat(canonical(spec.copy(access = AgentAccess.READ_ONLY))["access"]).describedAs("no empty paths list").isEqualTo(mapOf("mode" to "READ_ONLY"))
        assertThat(AssignmentSigner.specDigest(canonical, null)).isNotEqualTo(AssignmentSigner.specDigest(canonical(spec), null))

        val back = json.readValue(json.writeValueAsString(RunnerJobSpecJson.of(homed)), RunnerJobSpecJson::class.java).toSpec(spec.usageContext, spec.jobLabel, null)
        assertThat(back.agentHome).isEqualTo("doc-writer")
        assertThat(back.access).isEqualTo(paths)
        assertThat(AgentAccessJson("SOMETHING").toAccess()).describedAs("unknown modes never grant more").isEqualTo(AgentAccess.READ_ONLY)
        assertThat(AgentAccessJson("PATHS").toAccess()).isEqualTo(AgentAccess.READ_ONLY)
    }

    @Test
    fun `ADR 0060 - the result reports the enforced access and the reverted changes`() {
        val body = """{"status":"SUCCEEDED","access":{"mode":"PATHS","paths":["docs/**"],
            "reverted":[{"repository":"main","path":"src/App.kt","change":"modified"},{"repository":null,"path":"x","change":"type changed"},{"path":" "}]}}"""
        val result = json.readValue(body, RunnerResultJson::class.java).toRunnerResult(java.time.Instant.EPOCH)
        val access = result.access!!
        assertThat(access.mode).isEqualTo(AgentAccessMode.PATHS)
        assertThat(access.paths).containsExactly("docs/**")
        assertThat(access.reverted.map { it.describe() }).containsExactly("main:src/App.kt (modified)", "x (type changed)")
        val again = json.readValue(json.writeValueAsString(RunnerResultJson.of(result)), RunnerResultJson::class.java).toRunnerResult(java.time.Instant.EPOCH)
        assertThat(again.access).isEqualTo(access)
        assertThat(json.readValue("""{"status":"SUCCEEDED"}""", RunnerResultJson::class.java).toRunnerResult(java.time.Instant.EPOCH).access).isNull()
    }
}
