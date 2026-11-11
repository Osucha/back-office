package pl.mos.backoffice.projects.application.service.tasks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.domain.model.AgentQuestion
import pl.mos.backoffice.projects.domain.model.AgentQuestions
import pl.mos.backoffice.projects.domain.model.ApprovalKind
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.service.RunDirectory
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.testing.TestAgents
import java.math.BigDecimal

/** ADR 0060: how questions are read, limited and answered. */
class StepQuestionsTest {

    private val parsed = mapOf(
        """{"agentQuestions": ["Which?"]}""" to mapOf("agentQuestions" to listOf("Which?", " ", 7)),
        """{"agentQuestions": [], "x": 1}""" to mapOf("agentQuestions" to listOf("Which?"), "x" to 1),
        """{"agentQuestions": []}""" to mapOf("agentQuestions" to emptyList<Any>()),
        """{"questions": [{"question": "Q", "why": " "}]}""" to mapOf("questions" to listOf(mapOf("question" to "Q", "why" to " "))),
    )
    private val json = object : JsonCodec {
        override fun toJson(value: Any?): String = value.toString()
        override fun parseObject(json: String): Map<String, Any?> = parsed[json.trim()] ?: throw IllegalArgumentException("not JSON")
    }

    private fun task(config: Map<String, Any?> = emptyMap()) = ResolvedTask(
        "t", "T", TestAgents.MODEL_ANSWER, null, TaskPrompt(null, "x"), emptyList(), config, null, null,
        emptyList(), emptyList(), null, listOf("t"), listOf("success", "failure"),
    )

    private fun context(rounds: Int = 0): NodeContext {
        val run = Fixtures.runningRun()
        val node = run.definitionSnapshot.requireNode("implement")
        val started = run.nodeStarted(node, now)
        return NodeContext(started, Fixtures.project(), node, started.nodes.last(), mapOf(RunStateKeys.QUESTION_ROUNDS to mapOf("implement" to rounds)))
    }

    @Test
    fun `a model answer asks only when the whole answer is the agentQuestions object`() {
        assertThat(StepQuestions.fromModelAnswer("```json\n{\"agentQuestions\": [\"Which?\"]}\n```", null, json)).containsExactly(AgentQuestion("Which?"))
        assertThat(StepQuestions.fromModelAnswer("""{"agentQuestions": [], "x": 1}""", null, json)).describedAs("other keys: a normal answer").isNull()
        assertThat(StepQuestions.fromModelAnswer("""{"agentQuestions": []}""", null, json)).describedAs("no question").isNull()
        assertThat(StepQuestions.fromModelAnswer("Here is the design.", null, json)).isNull()
        assertThat(StepQuestions.fromModelAnswer("ignored", mapOf("agentQuestions" to listOf(mapOf("question" to "Structured?"))), json)).containsExactly(AgentQuestion("Structured?"))
        assertThat(StepQuestions.fromFile("""{"questions": [{"question": "Q", "why": " "}]}""", json)).containsExactly(AgentQuestion("Q"))
        assertThat(StepQuestions.list(List(15) { "q$it" })).hasSize(AgentQuestions.MAX_QUESTIONS)
    }

    @Test
    fun `within the limit the result waits with its round, over it the step fails and logs the questions`() {
        val asking = NodeResult(port = null, usage = TokenUsage(5, 1), cost = Money(BigDecimal.ONE), questions = listOf(AgentQuestion("Which?", "because")))
        val first = StepQuestions.limited(context(0), task(), asking)
        assertThat(first.questionRound).isEqualTo(1)
        assertThat(first.failed).isFalse()

        val logged = mutableListOf<String>()
        val ctx = context(2).copy(log = { logged += it })
        val over = StepQuestions.limited(ctx, task(), asking)
        assertThat(over.error).isEqualTo("the agent asked more than 2 rounds of questions")
        assertThat(over.usage).isEqualTo(TokenUsage(5, 1))
        assertThat(logged.single()).contains("Which?").contains("because")

        assertThat(StepQuestions.limited(context(), task(mapOf(RunDirectory.ALLOW_QUESTIONS to false)), asking).questions).isNull()
        val plain = NodeResult.success()
        assertThat(StepQuestions.limited(context(), task(), plain)).isSameAs(plain)
        assertThat(StepQuestions.open(task(), context(1))).isTrue()
        assertThat(StepQuestions.open(task(mapOf(RunDirectory.MAX_QUESTION_ROUNDS to 1)), context(1))).isFalse()
    }

    @Test
    fun `an AGENT_QUESTIONS approval carries questions and needs the answers to answer`() {
        val run = Fixtures.runningRun()
        val approval = ApprovalRequest(
            ApprovalRequestId.new(), run.id, "implement", run.projectId, run.projectKey, run.workflowName, null, AgentQuestions.question(1, 1),
            AgentQuestions.OPTIONS, createdAt = now, kind = ApprovalKind.AGENT_QUESTIONS, questions = listOf(AgentQuestion("Which?")), questionRound = 1,
        )
        assertThatThrownBy { approval.decide(Decision("answer", "  ", decidedAt = now)) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("answers")
        assertThat(approval.decide(Decision("cancel", decidedAt = now)).status).isEqualTo(ApprovalStatus.DECIDED)
        assertThat(approval.decide(Decision("ANSWER", "billing", decidedAt = now)).decision!!.option).isEqualTo("answer")
        assertThatThrownBy { approval.copy(questions = emptyList()) }.isInstanceOf(ValidationException::class.java)
        assertThat(AgentQuestions.question(2, 3)).isEqualTo("The agent asks 3 questions (round 2)")
        assertThat(AgentQuestions.answersText("Round 1: earlier", 2, listOf(AgentQuestion("Which?", "because")), " billing "))
            .isEqualTo("Round 1: earlier\n\nRound 2: your questions\n1. Which? (why: because)\nThe owner's answers:\nbilling")
    }
}
