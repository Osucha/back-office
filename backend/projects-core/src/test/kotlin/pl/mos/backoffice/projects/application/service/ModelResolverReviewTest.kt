package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.shared.testing.TestAgents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.application.service.ModelResolver.Companion.producesReview
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskPrompt
import pl.mos.backoffice.shared.tasks.TemplateCategory

/** ADR 0055: a review step (for the project's review model) is one that produces a review or is in the REVIEW category. */
class ModelResolverReviewTest {

    private fun task(produces: List<String> = emptyList(), category: TemplateCategory? = null) = ResolvedTask(
        key = "t", name = "t", agent = TestAgents.MODEL_ANSWER, modelProfileId = null, prompt = TaskPrompt(null, null), skills = emptyList(),
        config = emptyMap(), outputSchema = null, confidenceField = null, produces = produces, consumes = emptyList(), category = category,
        lineage = listOf("t"), ports = listOf("success", "failure"),
    )

    @Test
    fun `the review category or a produced review make a review step`() {
        assertThat(task(category = TemplateCategory.REVIEW).producesReview).isTrue()
        assertThat(task(produces = listOf("review")).producesReview).isTrue()
        assertThat(task(category = TemplateCategory.ANALYSIS, produces = listOf("analysis")).producesReview).isFalse()
        assertThat(task().producesReview).isFalse()
    }
}
