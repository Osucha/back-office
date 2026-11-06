package pl.mos.backoffice.projects.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.NodeExecution
import pl.mos.backoffice.projects.domain.model.NodeExecutionId
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.NodeKind
import pl.mos.backoffice.projects.domain.model.StepAgentRecord
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.runner.AgentHarness
import java.time.Instant

/** ADR 0059: `NodeExecution.agent` of the REST contract (`StepAgentRecord`). */
class NodeExecutionAgentMappingTest {

    private val mapper = RestMapper()
    private val execution = NodeExecution(
        id = NodeExecutionId.new(), nodeId = "implement", nodeName = "Implement", kind = NodeKind.TASK, templateKey = "agent-task", attempt = 1,
        status = NodeExecutionStatus.SUCCEEDED, startedAt = Instant.parse("2026-11-06T10:00:00Z"),
    )

    @Test
    fun `a task execution shows its agent and model, an older one none`() {
        val model = ModelProfileId.new()
        val dto = mapper.toDto(execution.copy(agent = StepAgentRecord("claude-code", "Claude Code", AgentHarness.CLAUDE_CODE, model, "Sonnet")))
        assertThat(dto.agent).isEqualTo(StepAgentRecordDto("claude-code", "Claude Code", "CLAUDE_CODE", model.value, "Sonnet"))

        val answer = mapper.toDto(execution.copy(agent = StepAgentRecord("model-answer", "Model answer")))
        assertThat(answer.agent).isEqualTo(StepAgentRecordDto("model-answer", "Model answer", null, null, null))

        assertThat(mapper.toDto(execution).agent).isNull()
    }
}
