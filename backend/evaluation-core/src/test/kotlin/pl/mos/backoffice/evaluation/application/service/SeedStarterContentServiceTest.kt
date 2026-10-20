package pl.mos.backoffice.evaluation.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
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
    fun `seeds the twelve task suite once and no model profiles (ADR 0045)`() {
        val first = service.seedIfEmpty()
        assertThat(first.profilesCreated).isZero()
        assertThat(first.suitesCreated).isEqualTo(1)

        val second = service.seedIfEmpty()
        assertThat(second.suitesCreated).isZero()
        assertThat(profiles.count()).isZero()
        assertThat(suites.count()).isEqualTo(1)
    }

    @Test
    fun `seed content matches the documented suite`() {
        service.seedIfEmpty()

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
            assertThat(it.repository!!.url).isEqualTo(StarterContent.PETCLINIC_REPO_URL)
            assertThat(it.repository!!.baseRef).isEqualTo(StarterContent.PETCLINIC_COMMIT)
            assertThat(it.repository!!.verifyCommand).isEqualTo("./mvnw -B -q test")
            assertThat(it.context).contains("spring-javaformat")
        }
        assertThat(suite.tasks.filter { it.kind == TaskKind.PROMPT }).allSatisfy { assertThat(it.repository).isNull() }
    }

    @Test
    fun `coding tasks still on the placeholder repository become the PetClinic tasks, the owner's own stay (ADR 0046)`() {
        service.seedIfEmpty()
        val seeded = suites.findAll().single()
        val old = seeded.tasks.filter { it.kind == TaskKind.CODING_JOB }
            .map { it.copy(id = EvalTaskId.new(), name = "Old " + it.name, repository = it.repository!!.copy(url = StarterContent.PLACEHOLDER_REPO_URL, baseRef = "main")) }
        val mine = old.first().copy(id = EvalTaskId.new(), name = "Mine", repository = old.first().repository!!.copy(url = "https://github.com/me/app.git"))
        val prompts = seeded.tasks.filter { it.kind == TaskKind.PROMPT }
        suites.save(seeded.copy(tasks = prompts + old + mine))

        val result = service.seedIfEmpty()

        assertThat(result.suitesUpgraded).isEqualTo(1)
        val upgraded = suites.findAll().single()
        assertThat(upgraded.tasks.map { it.repository?.url }).doesNotContain(StarterContent.PLACEHOLDER_REPO_URL)
        assertThat(upgraded.tasks.filter { it.repository?.url == StarterContent.PETCLINIC_REPO_URL }).hasSize(5)
        assertThat(upgraded.tasks.map { it.name }).contains("Mine").containsAll(prompts.map { it.name })
        assertThat(service.seedIfEmpty().suitesUpgraded).isZero()
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
