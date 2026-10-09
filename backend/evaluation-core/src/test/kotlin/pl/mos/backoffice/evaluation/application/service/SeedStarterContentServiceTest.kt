package pl.mos.backoffice.evaluation.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.evaluation.support.InMemoryModelProfileRepository
import pl.mos.backoffice.evaluation.support.InMemoryTaskSuiteRepository
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import java.math.BigDecimal

class SeedStarterContentServiceTest {

    private val profiles = InMemoryModelProfileRepository()
    private val suites = InMemoryTaskSuiteRepository()
    private val service = SeedStarterContentService(profiles, suites, Clock.fixed(Fixtures.now))

    @Test
    fun `seeds eight profiles and a twelve task suite once`() {
        val first = service.seedIfEmpty()
        assertThat(first.profilesCreated).isEqualTo(8)
        assertThat(first.suitesCreated).isEqualTo(1)

        val second = service.seedIfEmpty()
        assertThat(second.profilesCreated).isZero()
        assertThat(second.suitesCreated).isZero()
        assertThat(profiles.count()).isEqualTo(8)
        assertThat(suites.count()).isEqualTo(1)
    }

    @Test
    fun `seed content matches the documented catalogue and suite`() {
        service.seedIfEmpty()

        val byModel = profiles.findAll().associateBy { it.ref.modelId }
        assertThat(byModel.keys).containsExactlyInAnyOrder(
            "claude-fable-5-1", "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5", "gpt-5", "gemini-2.5-pro", "mistral-large-latest", "qwen3:32b",
        )
        val fable = byModel["claude-fable-5-1"]!!
        assertThat(fable.ref.provider).isEqualTo(Provider.ANTHROPIC)
        assertThat(fable.ref.options.reasoning).isEqualTo(Reasoning.HIGH)
        assertThat(fable.pricing.inputPerMTok).isEqualByComparingTo(BigDecimal("10"))
        assertThat(fable.pricing.outputPerMTok).isEqualByComparingTo(BigDecimal("50"))
        assertThat(fable.pricing.cacheReadPerMTok).isEqualByComparingTo(BigDecimal("0.25"))
        assertThat(fable.notes).contains("2026-06")
        assertThat(byModel["claude-opus-5"]!!.ref.options.reasoning).isEqualTo(Reasoning.HIGH)
        assertThat(byModel["claude-sonnet-5"]!!.ref.options.reasoning).isEqualTo(Reasoning.MEDIUM)
        assertThat(byModel["gpt-5"]!!.notes).containsIgnoringCase("verify current pricing")
        assertThat(byModel["qwen3:32b"]!!.ref.provider).isEqualTo(Provider.OLLAMA)

        val suite = suites.findAll().single()
        assertThat(suite.name).isEqualTo(StarterContent.STARTER_SUITE_NAME)
        assertThat(suite.tasks).hasSize(12)
        assertThat(suite.tasks.count { it.category == TaskCategory.ANALYSIS }).isEqualTo(2)
        assertThat(suite.tasks.count { it.category == TaskCategory.SPECIFICATION }).isEqualTo(2)
        assertThat(suite.tasks.count { it.category == TaskCategory.REVIEW }).isEqualTo(1)
        assertThat(suite.tasks.count { it.category == TaskCategory.ARCHITECTURE }).isEqualTo(2)
        val coding = suite.tasks.filter { it.category == TaskCategory.CODING }
        assertThat(coding).hasSize(5)
        assertThat(coding).allSatisfy {
            assertThat(it.kind).isEqualTo(TaskKind.CODING_JOB)
            assertThat(it.repository!!.url).isEqualTo(StarterContent.PLACEHOLDER_REPO_URL)
            assertThat(it.repository!!.verifyCommand).isEqualTo("./gradlew test")
            assertThat(it.context).contains("placeholder")
        }
        assertThat(suite.tasks.filter { it.kind == TaskKind.PROMPT }).allSatisfy { assertThat(it.repository).isNull() }
    }

    @Test
    fun `does not seed profiles when the owner already has some`() {
        profiles.save(Fixtures.profile("Mine"))
        val result = service.seedIfEmpty()
        assertThat(result.profilesCreated).isZero()
        assertThat(result.suitesCreated).isEqualTo(1)
        assertThat(profiles.count()).isEqualTo(1)
    }
}
