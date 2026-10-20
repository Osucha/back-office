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

        // ADR 0047: green tests with a poor change (60 + 0.4 × 20 = 68) do not pass
        val poor = ScoreCombiner.coding(verificationPassed = true, judgeScore = 20.0, weights = CodingScoreWeights.DEFAULT)
        assertThat(poor.score).isCloseTo(68.0, within(1e-9))
        assertThat(poor.passed).isFalse()

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
    fun `judge verdict is parsed leniently and the overall score comes from the criteria`() {
        val verdict = JudgePrompts.parseVerdict(
            mapOf(
                "score" to 99,
                "criteria" to listOf(
                    mapOf("name" to "Correctness", "score" to "9", "comment" to "ok"),
                    mapOf("name" to "Tests", "score" to 6, "comment" to "thin"),
                    "garbage",
                ),
                "rationale" to "Solid.",
            ),
            rawContent = "",
        )
        // ADR 0047: (9 + 6) / 2 × 10, the judge's own 99 is ignored
        assertThat(verdict.score).isCloseTo(75.0, within(1e-9))
        assertThat(verdict.passed).isTrue()
        assertThat(verdict.criteria).containsExactly(JudgeCriterion("Correctness", 9.0, "ok"), JudgeCriterion("Tests", 6.0, "thin"))
        assertThat(verdict.rationale).isEqualTo("Solid.")
        // an answer without criteria (older prompt) falls back to its own score
        assertThat(JudgePrompts.parseVerdict(mapOf("score" to 64), "").let { it.score to it.passed }).isEqualTo(64.0 to false)

        assertThatThrownBy { JudgePrompts.parseVerdict(null, "not json") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { JudgePrompts.parseVerdict(mapOf("passed" to true), "") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the judge reads the diff, source and tests first, cut with a note`() {
        val readme = "diff --git a/README.md b/README.md\n+docs\n"
        val main = "diff --git a/src/main/java/A.java b/src/main/java/A.java\n+class A {}\n"
        val test = "diff --git a/src/test/java/ATest.java b/src/test/java/ATest.java\n+class ATest {}\n"
        val text = JudgePrompts.judgeDiff(readme + test + main)
        assertThat(text).startsWith("Changed files: src/main/java/A.java, src/test/java/ATest.java, README.md")
        assertThat(text.indexOf("class A {}")).isLessThan(text.indexOf("+docs"))
        assertThat(JudgePrompts.judgeDiff(null)).isEqualTo("(no changes)")
        val huge = "diff --git a/src/main/java/B.java b/src/main/java/B.java\n" + "+x\n".repeat(JudgePrompts.MAX_JUDGE_DIFF_CHARS)
        assertThat(JudgePrompts.judgeDiff(main + huge)).contains("class A {}").contains("src/main/java/B.java left out")
    }
}
