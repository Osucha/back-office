package pl.mos.backoffice.runner

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.application.service.ClaudeCodePromptLlm
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.RunnerJobId
import pl.mos.backoffice.shared.llm.LlmMessage
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.RunnerArtifact
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** ADR 0050 step 4: a model call of a Claude Code login model is a PROMPT job on the acting user's personal runner. */
@Suppress("DEPRECATION")
class ClaudeCodePromptLlmTest {

    private val runner = mockk<AgentRunner>()
    private val json = JsonMapper.builder().build()
    @Suppress("UNCHECKED_CAST")
    private val llm = ClaudeCodePromptLlm(runner, { json.readValue(it, Map::class.java) as Map<String, Any?> })
    private val opus = ModelRef(Provider.ANTHROPIC, "opus")
    private val user = UUID.randomUUID()

    private fun result(status: RunnerJobStatus, answer: String?, error: String? = null) = RunnerResult(
        status = status, summary = "summary", branch = null, commitSha = null, diffStat = null, verification = null,
        artifacts = listOfNotNull(answer?.let { RunnerArtifact("response", null, "text/markdown", it) }),
        usage = TokenUsage(120, 30), reportedCostUsd = null, error = error, finishedAt = Instant.now(), subscription = true,
    )

    private fun request(actingUser: UUID? = user, schema: String? = null) = LlmRequest(
        messages = listOf(LlmMessage.system("You review code."), LlmMessage.user("Review this diff.")),
        responseSchema = schema,
        usageContext = UsageContext(UsageKind.PIPELINE_STEP, actingUserId = actingUser),
    )

    @Test
    fun `the step's system message becomes the system prompt and the job goes to the user's personal runner`() {
        val spec = slot<RunnerJobSpec>()
        val id = RunnerJobId.new()
        every { runner.start(capture(spec)) } returns id
        every { runner.await(id, any()) } returns result(RunnerJobStatus.SUCCEEDED, "Looks good.\n```json\n{\"verdict\":\"APPROVE\",\"score\":90}\n```")

        val answer = llm.chat(opus, request(schema = """{"type":"object"}"""))

        with(spec.captured) {
            assertThat(mode).isEqualTo(RunnerMode.PROMPT)
            assertThat(agent).isEqualTo(AgentHarness.CLAUDE_CODE)
            assertThat(systemPrompt).isEqualTo("You review code.")
            assertThat(prompt).startsWith("Review this diff.").contains("one JSON object only").endsWith("""{"type":"object"}""")
            assertThat(personalRunnerOf).isEqualTo(user)
            assertThat(repository).isNull()
            assertThat(collectDiff).isFalse()
        }
        assertThat(answer.structured).containsEntry("verdict", "APPROVE").containsEntry("score", 90)
        assertThat(answer.usage).isEqualTo(TokenUsage(120, 30))
        assertThat(answer.modelRef).isEqualTo(opus)
    }

    @Test
    fun `a repair conversation keeps its turns, an unanswered job and work of nobody are errors`() {
        val turns = LlmRequest(
            messages = listOf(LlmMessage.user("Q"), LlmMessage.assistant("A"), LlmMessage.user("Fix it")),
            usageContext = UsageContext(UsageKind.PIPELINE_STEP, actingUserId = user),
        )
        assertThat(ClaudeCodePromptLlm.promptOf(turns)).isEqualTo("## user\nQ\n\n## assistant\nA\n\n## user\nFix it")

        val id = RunnerJobId.new()
        every { runner.start(any()) } returns id
        every { runner.await(id, any()) } returns result(RunnerJobStatus.FAILED, null, "no Claude login")
        assertThatThrownBy { llm.chat(opus, request()) }.isInstanceOf(IntegrationException::class.java).hasMessageContaining("no Claude login")
        assertThatThrownBy { llm.chat(opus, request(actingUser = null)) }.isInstanceOf(IntegrationException::class.java).hasMessageContaining("no acting user")
    }
}
