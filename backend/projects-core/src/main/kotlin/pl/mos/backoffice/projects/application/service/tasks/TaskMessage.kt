package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.domain.service.RenderedTemplate
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.shared.prompt.PromptDataBlocks
import pl.mos.backoffice.shared.prompt.UntrustedText
import pl.mos.backoffice.shared.tasks.ParameterBindingSource
import pl.mos.backoffice.shared.tasks.ResolvedTask

/**
 * ADR 0062: the message of a task step (the user message of a model answer, the prompt of a coding agent): the
 * template's instructions, then the standard data sections, each only when it has content and the instructions do not
 * place it themselves with its placeholder:
 *
 * 1. the issue (`{{issue.*}}`),
 * 2. the artefacts the task consumes (`{{artifacts.<name>}}`), except the diff,
 * 3. the parameter values bound at run start (`{{params.<name>}}`; none when the instructions use `{{runInputText}}` or
 *    `{{runInput}}`, the ADR 0053 form of the same values),
 * 4. the reviewer's feedback (`{{feedback}}`),
 * 5. the owner's answers to the agent's questions (`{{answers}}`, ADR 0060),
 * 6. a consumed diff, last: a message cut at its size limit loses diff lines, never the instructions.
 *
 * Everything is rendered in one pass with [TemplateRenderer.renderPrompt], so untrusted values are data blocks (ADR 0029)
 * whether the instructions or a standard section place them.
 */
class TaskMessage(private val renderer: TemplateRenderer) {

    /** The message of [task] with the step's [model]; [maxValueChars] caps every single value. */
    fun render(task: ResolvedTask, model: Map<String, Any?>, blocks: PromptDataBlocks, maxValueChars: Int = UntrustedText.DEFAULT_MAX_CHARS): RenderedTemplate =
        renderer.renderPrompt(template(task), model, blocks, maxValueChars).let { it.copy(text = it.text.trimEnd()) }

    companion object {
        /** The instructions of a template that has none: the step's name, so the data sections have a heading. */
        const val NO_INSTRUCTIONS = "Task: {{node.name}}"

        /** The issue, or nothing when the run has none (no bare ": " line). */
        val ISSUE_SECTION = """
            {{#issue.key}}
            ## Issue
            {{issue.key}}: {{issue.summary}}
            {{#issue.description}}

            {{issue.description}}
            {{/issue.description}}
            {{#issue.acceptanceCriteria}}

            Acceptance criteria:
            {{issue.acceptanceCriteria}}
            {{/issue.acceptanceCriteria}}
            {{/issue.key}}
        """.trimIndent()

        /** Revision comments given for this step (never those of other steps); nothing on the first attempt. */
        val FEEDBACK_SECTION = """
            {{#feedback}}
            ## Feedback on the previous attempt of this step (address every point)
            {{feedback}}
            {{/feedback}}
        """.trimIndent()

        /** ADR 0060: after the owner answered the agent's questions (agent questions are agent output: a data block). */
        val ANSWERS_SECTION = "{{#answers}}\n" + StepQuestions.ANSWERS_TEMPLATE + "\n{{/answers}}"

        private const val DIFF = "diff"
        private const val ISSUE = "issue"

        /** Headings of the consumed artefacts the built-ins use; other names read as words (`user-story` -> "User story"). */
        private val ARTIFACT_TITLES = mapOf(
            "spec" to "Specification",
            "plan" to "Plan",
            "tasks" to "Task list",
            "user-story" to "User story",
            "use-case" to "Use case",
            "impact-analysis" to "Impact analysis",
            "analysis" to "Analysis",
            "review" to "Latest review",
            DIFF to "Diff to review",
        )

        /** A name the renderer's section syntax can address. */
        private val SECTION_NAME = Regex("^[A-Za-z0-9_-]+$")

        /** The template text: [ResolvedTask.instructions] and the standard sections they do not place. */
        fun template(task: ResolvedTask): String {
            val instructions = task.instructions?.takeIf { it.isNotBlank() } ?: NO_INSTRUCTIONS
            val consumed = task.consumes.filter { it != ISSUE && SECTION_NAME.matches(it) && !places(instructions, "artifacts.$it") }
            val startParameters = if (places(instructions, "runInputText") || places(instructions, "runInput")) {
                emptyList()
            } else {
                task.parameters.filter { it.binding?.source == ParameterBindingSource.START && !places(instructions, "params.${it.name}") }
            }
            val sections = buildList {
                if (!placesRoot(instructions, ISSUE)) add(ISSUE_SECTION)
                consumed.filter { it != DIFF }.forEach { add(artifactSection(it)) }
                startParameters.forEach { add(parameterSection(it.name, it.definition.label)) }
                if (!places(instructions, "feedback")) add(FEEDBACK_SECTION)
                if (!places(instructions, "answers")) add(ANSWERS_SECTION)
                if (DIFF in consumed) add(artifactSection(DIFF))
            }
            return (listOf(instructions.trimEnd()) + sections).joinToString("\n\n")
        }

        /** The heading of a consumed artefact. */
        fun artifactTitle(name: String): String =
            ARTIFACT_TITLES[name] ?: name.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

        private fun artifactSection(name: String): String =
            "{{#artifacts.$name}}\n## ${artifactTitle(name)}\n{{artifacts.$name}}\n{{/artifacts.$name}}"

        /** A parameter value bound at run start under its label (braces dropped: a label is no template). */
        private fun parameterSection(name: String, label: String): String =
            "{{#params.$name}}\n## ${label.replace("{", "").replace("}", "").trim()}\n{{params.$name}}\n{{/params.$name}}"

        /** Whether [text] has a placeholder or section of exactly [path] (`{{path}}`, `{{ path | lower }}`, `{{#path}}`...). */
        fun places(text: String, path: String): Boolean =
            Regex("\\{\\{\\s*[#^/]?\\s*" + Regex.escape(path) + "\\s*(\\||}})").containsMatchIn(text)

        /** Whether [text] has a placeholder or section of [root] or a path below it (`{{issue.key}}`, `{{#issue.summary}}`). */
        private fun placesRoot(text: String, root: String): Boolean =
            Regex("\\{\\{\\s*[#^/]?\\s*" + Regex.escape(root) + "(\\.|\\s*(\\||}}))").containsMatchIn(text)
    }
}
