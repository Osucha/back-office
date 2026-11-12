package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.domain.model.AgentQuestion
import pl.mos.backoffice.projects.domain.model.AgentQuestions
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.shared.prompt.UntrustedText
import pl.mos.backoffice.shared.tasks.AgentEnvironmentText
import pl.mos.backoffice.shared.tasks.ResolvedTask

/**
 * ADR 0060: agents ask questions instead of guessing. How a step's agent asks (a model answer replies with only
 * `{"agentQuestions": [...]}`, a coding agent writes `agent-questions.json` with `{"questions": [...]}`), whether the step
 * lets it ask now (`allowQuestions`, default true, and rounds left of `maxQuestionRounds`, default 2), the answers that
 * go back into its prompt, and the limit ([limited]: over the limit the questions are logged and the step fails).
 */
object StepQuestions {

    /** Whether the step's agent may ask now: questions allowed for the step and rounds left in this visit. */
    fun open(task: ResolvedTask, context: NodeContext): Boolean {
        val settings = RunDirectory.questions(task.config)
        return settings.allowed && RunStateKeys.questionRounds(context.state, context.node.id) < settings.maxRounds
    }

    /** Whether questions are read from the agent at all (`allowQuestions`); over the limit they fail the step. */
    fun allowed(task: ResolvedTask): Boolean = RunDirectory.questions(task.config).allowed

    /**
     * The questions of a model answer: the whole answer (markdown fences allowed around it) is one JSON object whose only
     * key is `agentQuestions`, a non-empty list; null for any other answer.
     */
    fun fromModelAnswer(content: String, structured: Map<String, Any?>?, json: JsonCodec): List<AgentQuestion>? {
        val parsed = structured?.takeIf { it.keys == setOf(AgentEnvironmentText.QUESTIONS_KEY) }
            ?: runCatching { json.parseObject(stripFences(content)) }.getOrNull()
            ?: return null
        if (parsed.keys != setOf(AgentEnvironmentText.QUESTIONS_KEY)) return null
        return list(parsed[AgentEnvironmentText.QUESTIONS_KEY])
    }

    /** The questions of a coding agent's `agent-questions.json` (`{"questions": [...]}`); null when it holds none. */
    fun fromFile(content: String, json: JsonCodec): List<AgentQuestion>? {
        val parsed = runCatching { json.parseObject(stripFences(content)) }.getOrNull() ?: return null
        return list(parsed["questions"] ?: parsed[AgentEnvironmentText.QUESTIONS_KEY])
    }

    /** Strings or `{question, why}` objects, sanitised and bounded; null when none is usable. */
    fun list(value: Any?): List<AgentQuestion>? {
        val items = value as? List<*> ?: return null
        val questions = items.mapNotNull { item ->
            when (item) {
                is String -> clean(item)?.let { AgentQuestion(it) }
                is Map<*, *> -> clean(item["question"]?.toString())?.let { AgentQuestion(it, clean(item["why"]?.toString())) }
                else -> null
            }
        }.take(AgentQuestions.MAX_QUESTIONS)
        return questions.ifEmpty { null }
    }

    /**
     * Applies the limit to a [result] that carries questions: within it the result waits for the answers (round set);
     * over it the questions go to the step log and the step fails. Results without questions pass unchanged.
     */
    fun limited(context: NodeContext, task: ResolvedTask, result: NodeResult): NodeResult {
        val questions = result.questions ?: return result
        if (result.failed) return result.copy(questions = null)
        val settings = RunDirectory.questions(task.config)
        if (!settings.allowed) return result.copy(questions = null)
        val answered = RunStateKeys.questionRounds(context.state, context.node.id)
        if (answered >= settings.maxRounds) {
            questions.forEachIndexed { i, q -> context.log("question ${i + 1} of the agent (not asked, over the limit): ${q.question}" + (q.why?.let { " (why: $it)" } ?: "")) }
            val error = "the agent asked more than ${settings.maxRounds} rounds of questions"
            return NodeResult.failure(error, usage = result.usage, cost = result.cost, runnerJobId = result.runnerJobId)
                .copy(apiCost = result.apiCost, agent = result.agent, summary = error)
        }
        return result.copy(port = null, questionRound = answered + 1, maxQuestionRounds = settings.maxRounds, artifacts = emptyList(), stateUpdates = emptyMap())
    }

    /**
     * The section after the owner answered (ADR 0062: a standard section of [TaskMessage]): `{{answers}}` is agent output
     * and the owner's text, rendered as a data block.
     */
    const val ANSWERS_TEMPLATE = "## Answers of the owner\nYou asked the owner earlier in this step; these are the answers. Continue the step with them.\n\n{{answers}}"

    private fun clean(text: String?): String? = text?.let { UntrustedText.sanitize(it, AgentQuestions.MAX_CHARS).trim() }?.takeIf { it.isNotEmpty() }

    private fun stripFences(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    }
}
