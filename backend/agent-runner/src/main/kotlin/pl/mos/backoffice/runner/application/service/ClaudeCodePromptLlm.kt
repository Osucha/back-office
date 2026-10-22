package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.OwnLoginLlm
import pl.mos.backoffice.shared.llm.Role
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import java.time.Duration
import java.util.UUID

/**
 * ADR 0050 step 4: a model call answered by Claude Code with the acting user's own login: a PROMPT job routed to
 * their personal runner, awaited like a gateway call (one answer, minutes at most). The step's system message becomes
 * the job's system prompt; with a response schema the answer is asked for as one JSON object and parsed here.
 */
class ClaudeCodePromptLlm(
    private val runner: AgentRunner,
    private val parseJson: (String) -> Map<String, Any?>?,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : OwnLoginLlm {

    @Suppress("DEPRECATION")
    override fun chat(model: ModelRef, request: LlmRequest): LlmResult {
        val user = request.usageContext.actingUserId
            ?: throw IntegrationException(SYSTEM, "a Claude Code login model answers only a user's own work (no acting user)", persistent = true)
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
        val spec = RunnerJobSpec(
            mode = RunnerMode.PROMPT,
            agent = AgentHarness.CLAUDE_CODE,
            model = model,
            prompt = promptOf(request),
            systemPrompt = system,
            timeout = timeout,
            collectDiff = false,
            usageContext = request.usageContext,
            jobLabel = "prompt-" + UUID.randomUUID(),
            personalRunnerOf = user,
        )
        val started = System.nanoTime()
        val result = runner.await(runner.start(spec), timeout.plusMinutes(1))
        if (result.status != RunnerJobStatus.SUCCEEDED) {
            throw IntegrationException(SYSTEM, "Claude Code did not answer: " + (result.error ?: result.status.name), retryable = result.status == RunnerJobStatus.TIMED_OUT)
        }
        val content = result.artifacts.firstOrNull { it.name == RESPONSE_ARTIFACT }?.content ?: result.summary.orEmpty()
        return LlmResult(
            content = content,
            structured = request.responseSchema?.let { jsonObjectIn(content) },
            usage = result.usage,
            latency = Duration.ofNanos(System.nanoTime() - started),
            modelRef = model,
        )
    }

    private fun jsonObjectIn(text: String): Map<String, Any?>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { parseJson(text.substring(start, end + 1)) }.getOrNull()
    }

    companion object {
        const val SYSTEM = "claude-code"
        const val RESPONSE_ARTIFACT = "response"
        val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(10)

        /** The user turn; a conversation (a repair turn) is written out with its roles, the schema asked for at the end. */
        fun promptOf(request: LlmRequest): String {
            val turns = request.messages.filter { it.role != Role.SYSTEM }
            val body = turns.singleOrNull()?.content ?: turns.joinToString("\n\n") { "## " + it.role.name.lowercase() + "\n" + it.content }
            return request.responseSchema?.let {
                body + "\n\nAnswer with one JSON object only (no prose, no code fence) that matches this JSON schema:\n" + it
            } ?: body
        }
    }
}
