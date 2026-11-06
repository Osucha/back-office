package pl.mos.backoffice.shared.testing

import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentSnapshot

/** ADR 0056: the built-in agents as resolved tasks carry them, for tests. */
object TestAgents {
    val MODEL_ANSWER: AgentSnapshot = AgentSnapshot.DEFAULT
    val OPENCODE: AgentSnapshot = AgentSnapshot(AgentSnapshot.OPENCODE, "OpenCode", AgentHarness.OPENCODE)
    val CLAUDE_CODE: AgentSnapshot = AgentSnapshot(AgentSnapshot.CLAUDE_CODE, "Claude Code", AgentHarness.CLAUDE_CODE)
}
