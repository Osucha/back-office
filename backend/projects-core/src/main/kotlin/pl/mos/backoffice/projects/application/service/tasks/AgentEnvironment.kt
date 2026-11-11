package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.service.RenderedTemplate
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.prompt.PromptDataBlocks
import pl.mos.backoffice.shared.tasks.AgentEnvironmentText
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.RuleSnapshot

/**
 * ADR 0057: the environment document of a step, rendered once and given to each harness in its own form (model answers
 * in the system prompt, OpenCode as `AGENTS.md` listed in `opencode.json`, Claude Code as the user memory `CLAUDE.md`;
 * the runner writes both outside every repository). In this order:
 *
 * 1. the platform preamble for the kind of agent ([PLATFORM_PREAMBLE] plus [MODEL_ANSWER_PREAMBLE] or
 *    [CODING_AGENT_PREAMBLE]): platform facts, not norms;
 * 2. the agent's instructions, rendered with the step's template model like a prompt (`{{project.*}}`; untrusted values
 *    become data blocks);
 * 3. the project constitution, when the project has one;
 * 4. the rules: the automatic ones (global, then the project's), then the agent's (ADR 0055: always in context).
 *
 * Skills are not part of it: a model answer gets them after the template's system prompt, a coding agent in its skill
 * directories.
 */
class AgentEnvironment(private val renderer: TemplateRenderer) {

    /**
     * The document of [task] in [project]; [model] is the step's template model, [blocks] the step's data blocks.
     * [questions] (ADR 0060): the step lets the agent ask the owner now, so the preamble tells it how.
     */
    fun document(task: ResolvedTask, project: Project, model: Map<String, Any?>, blocks: PromptDataBlocks, questions: Boolean = false): RenderedTemplate {
        val instructions = task.agent.instructions.takeIf { it.isNotBlank() }?.let { renderer.renderPrompt(it, model, blocks) }
        val text = AgentEnvironmentText.compose(task.agent.modelAnswer, instructions?.text, project.constitution, task.rules, questions)
        return RenderedTemplate(text, instructions?.warnings.orEmpty())
    }

    /** The fixed texts live in the shared kernel ([AgentEnvironmentText], ADR 0059: benchmarks render them too). */
    companion object {
        val PLATFORM_PREAMBLE: String get() = AgentEnvironmentText.PLATFORM_PREAMBLE
        val MODEL_ANSWER_PREAMBLE: String get() = AgentEnvironmentText.MODEL_ANSWER_PREAMBLE
        val CODING_AGENT_PREAMBLE: String get() = AgentEnvironmentText.CODING_AGENT_PREAMBLE

        fun preamble(modelAnswer: Boolean, questions: Boolean = false): String = AgentEnvironmentText.preamble(modelAnswer, questions)

        fun rulesSection(rules: List<RuleSnapshot>): String? = AgentEnvironmentText.rulesSection(rules)
    }
}
