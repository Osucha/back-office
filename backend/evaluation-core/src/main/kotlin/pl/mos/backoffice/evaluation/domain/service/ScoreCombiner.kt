package pl.mos.backoffice.evaluation.domain.service

import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights

/**
 * Combines verification outcome and judge score into the attempt score.
 *
 * - coding: `w_v * (passed ? 100 : 0) + w_j * judgeScore` (weights per suite, default 0.6 / 0.4)
 * - prompt: the judge score; `passed` when the score reaches [PROMPT_PASS_THRESHOLD]
 */
object ScoreCombiner {
    const val PROMPT_PASS_THRESHOLD = 70.0

    data class Combined(val score: Double, val passed: Boolean)

    /**
     * ADR 0047: passed = the verification passed (the repository's build and the hidden acceptance tests) AND the
     * combined score reaches the same threshold as prompt tasks, so green tests with a poor change do not pass.
     */
    fun coding(verificationPassed: Boolean, judgeScore: Double, weights: CodingScoreWeights): Combined {
        val score = (weights.verification * (if (verificationPassed) 100.0 else 0.0) + weights.judge * judgeScore).coerceIn(0.0, 100.0)
        return Combined(score, verificationPassed && score >= PROMPT_PASS_THRESHOLD)
    }

    fun prompt(judgeScore: Double): Combined {
        val score = judgeScore.coerceIn(0.0, 100.0)
        return Combined(score, score >= PROMPT_PASS_THRESHOLD)
    }
}
