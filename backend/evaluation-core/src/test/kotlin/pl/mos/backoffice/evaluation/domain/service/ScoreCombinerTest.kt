package pl.mos.backoffice.evaluation.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.JudgeCriterion
import pl.mos.backoffice.shared.ValidationException

class ScoreCombinerTest {

    @Test
    fun `coding score weights verification and judge`() {
        val passed = ScoreCombiner.coding(verificationPassed = true, judgeScore = 50.0, weights = CodingScoreWeights.DEFAULT)
        assertThat(passed.score).isCloseTo(80.0, within(1e-9))
        assertThat(passed.passed).isTrue()

        val failed = ScoreCombiner.coding(verificationPassed = false, judgeScore = 50.0, weights = CodingScoreWeights.DEFAULT)
        assertThat(failed.score).isCloseTo(20.0, within(1e-9))
        assertThat(failed.passed).isFalse()

        val custom = ScoreCombiner.coding(true, 100.0, CodingScoreWeights(verification = 0.5, judge = 0.5))
        assertThat(custom.score).isCloseTo(100.0, within(1e-9))
    }

    @Test
    fun `prompt score passes at the 70 threshold`() {
        assertThat(ScoreCombiner.prompt(70.0).passed).isTrue()
        assertThat(ScoreCombiner.prompt(69.9).passed).isFalse()
        assertThat(ScoreCombiner.prompt(140.0).score).isEqualTo(100.0)
    }

    @Test
    fun `weights must sum to one`() {
        assertThatThrownBy { CodingScoreWeights(0.7, 0.4) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `judge verdict is parsed leniently`() {
        val verdict = JudgePrompts.parseVerdict(
            mapOf(
                "score" to 82,
                "passed" to "true",
                "criteria" to listOf(mapOf("name" to "Correctness", "score" to "9", "comment" to "ok"), "garbage"),
                "rationale" to "Solid.",
            ),
            rawContent = "",
        )
        assertThat(verdict.score).isEqualTo(82.0)
        assertThat(verdict.passed).isTrue()
        assertThat(verdict.criteria).containsExactly(JudgeCriterion("Correctness", 9.0, "ok"))
        assertThat(verdict.rationale).isEqualTo("Solid.")

        assertThatThrownBy { JudgePrompts.parseVerdict(null, "not json") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { JudgePrompts.parseVerdict(mapOf("passed" to true), "") }.isInstanceOf(ValidationException::class.java)
    }
}
