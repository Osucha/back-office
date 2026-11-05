package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.tasks.RuleSnapshot

/** ADR 0055: the rules block every step gets. */
class RulesPromptTest {

    @Test
    fun `no rules, no block`() {
        assertThat(RulesPrompt.section(emptyList())).isNull()
    }

    @Test
    fun `every rule in order, trimmed, inside a rules block with key and name`() {
        val section = RulesPrompt.section(
            listOf(
                RuleSnapshot("ground", "Ground \"rules\"", "\n  Cite evidence.  \n", automatic = true),
                RuleSnapshot("style", "Style", "Be brief."),
            ),
        )!!

        assertThat(section).isEqualTo(
            "RULES (follow them in every part of this step):\n<rules>\n" +
                "<rule key=\"ground\" name=\"Ground 'rules'\">\nCite evidence.\n</rule>\n" +
                "<rule key=\"style\" name=\"Style\">\nBe brief.\n</rule>\n" +
                "</rules>",
        )
    }
}
