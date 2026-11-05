package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.shared.tasks.RuleSnapshot

/**
 * ADR 0055: how rules reach the model. Rules are always in context, unlike skills (an agent loads those on demand):
 * model answer steps get this block in the system prompt, coding agents at the start of their prompt (right after the
 * template's system part), so nothing is written into the repository and both agents see the same text.
 */
object RulesPrompt {

    /** `<rules>` block with every rule in order, or null when there are none. */
    fun section(rules: List<RuleSnapshot>): String? {
        if (rules.isEmpty()) return null
        return buildString {
            append("RULES (follow them in every part of this step):\n<rules>\n")
            rules.forEach { rule ->
                append("<rule key=\"").append(rule.key).append("\" name=\"").append(rule.name.replace("\"", "'")).append("\">\n")
                append(rule.content.trim()).append("\n</rule>\n")
            }
            append("</rules>")
        }
    }
}
