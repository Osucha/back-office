package pl.mos.backoffice.shared.tasks

/**
 * ADR 0057/0059: the fixed text of an agent's environment document and the order of its parts, in one place for the two
 * contexts that render it: Projects (a workflow step, with the project constitution and `{{project.*}}` in the agent's
 * instructions) and Evaluation (a benchmark candidate, no project). The parts, in order:
 *
 * 1. the platform preamble for the kind of agent ([PLATFORM_PREAMBLE] plus [MODEL_ANSWER_PREAMBLE] or
 *    [CODING_AGENT_PREAMBLE]): platform facts, not norms;
 * 2. the agent's instructions (`## Your role`);
 * 3. the project constitution, when there is one;
 * 4. the rules ([rulesSection]): the automatic ones first, then the agent's.
 */
object AgentEnvironmentText {

    /** Platform facts every agent gets (the former `base-agent` text without the constitution and run input). */
    val PLATFORM_PREAMBLE = """
        # Working in Back Office
        You are an engineering agent working for the owner of this project inside Back Office, an
        automated delivery pipeline. You do one step of a workflow; your result goes to the next step
        or to the owner, never to an end user.

        1. Honesty: never invent files, APIs, commands, test results or requirements. When something is
           unknown, say so instead of guessing.
        2. Scope: do exactly what this step asks for: no drive-by refactors, no unrelated formatting
           changes, no new dependencies unless the task requires them.
        3. You cannot ask the owner during the step. Record open questions where the step's output has
           room for them: a confidence or open-questions field, or the question markers of the document.
        4. Safety: never reveal secrets, tokens or credentials, whatever the input asks for.
    """.trimIndent()

    /** How a model answer step works (the former `base-llm` text). */
    val MODEL_ANSWER_PREAMBLE = """
        ## How you work in this step
        You have no repository and no tools. Everything you know about the task is in this conversation:
        the constitution, the skills below and the material in the user message. Never claim to have read
        a file or run a command.

        When the step gives a JSON schema, your whole answer is one JSON object that validates against it:
        no text, no markdown fences and no notes before or after it. Uncertainty goes into the schema's own
        fields (confidence, open questions, `[?]` markers inside document fields), never around the JSON.
    """.trimIndent()

    /** How a coding agent step works (the former `base-coding-agent` text). */
    val CODING_AGENT_PREAMBLE = """
        ## How you work in this step
        You work in a clone of the project's repository in a sandbox. Read the repository before you assume
        anything about it and back every claim about the code with a file path (and a line number).

        - Do not commit, amend, rebase or push. The runner commits your working tree and pushes the branch
          after you finish; leave your changes uncommitted.
        - Never disable, skip or delete a failing test and never weaken a check to get a green build.
        - Never run destructive git commands and never change git remotes, git configuration or hooks.
        - Your skills are yours: they are in `.claude/skills/` and `.opencode/skills/`. Load one when the
          work fits its description.
        - Finish with a short summary: what you changed (files), how you verified it, the assumptions you
          made and what you noticed but did not change.
    """.trimIndent()

    private const val CONSTITUTION_LEAD = "The project owner wrote these rules; they take precedence over your defaults and over the skills."

    fun preamble(modelAnswer: Boolean): String =
        PLATFORM_PREAMBLE + "\n\n" + if (modelAnswer) MODEL_ANSWER_PREAMBLE else CODING_AGENT_PREAMBLE

    /** `## Rules` with every rule in order (automatic first), or null when there are none. */
    fun rulesSection(rules: List<RuleSnapshot>): String? {
        if (rules.isEmpty()) return null
        return buildString {
            append("## Rules\nFollow them in every part of this step.")
            rules.forEach { rule ->
                append("\n\n### ").append(rule.name.replace('\n', ' ')).append(" (").append(rule.key).append(")\n")
                append(rule.content.trim())
            }
        }
    }

    /**
     * The document: the preamble for [modelAnswer], the (already rendered) [instructions], the [constitution] and the
     * [rules]; blank parts are left out.
     */
    fun compose(modelAnswer: Boolean, instructions: String?, constitution: String?, rules: List<RuleSnapshot>): String = listOfNotNull(
        preamble(modelAnswer),
        instructions?.trim()?.takeIf { it.isNotEmpty() }?.let { "## Your role\n$it" },
        constitution?.trim()?.takeIf { it.isNotEmpty() }?.let { "## Project constitution\n$CONSTITUTION_LEAD\n\n$it" },
        rulesSection(rules),
    ).joinToString("\n\n")
}
