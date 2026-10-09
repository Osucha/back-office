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

    fun coding(verificationPassed: Boolean, judgeScore: Double, weights: CodingScoreWeights): Combined {
        val score = weights.verification * (if (verificationPassed) 100.0 else 0.0) + weights.judge * judgeScore
        return Combined(score.coerceIn(0.0, 100.0), verificationPassed)
    }

    fun prompt(judgeScore: Double): Combined {
        val score = judgeScore.coerceIn(0.0, 100.0)
        return Combined(score, score >= PROMPT_PASS_THRESHOLD)
    }
}
