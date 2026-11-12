package pl.mos.backoffice.evaluation.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.domain.model.Difficulty
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
            assertThat(it.repository!!.verifyCommand).isEqualTo(StarterContent.PETCLINIC_VERIFY_COMMAND)
            // ADR 0065: every hidden acceptance test class has a per-test timeout
            assertThat(it.repository!!.acceptance!!.files.filterKeys { p -> p.endsWith("AcceptanceTests.java") }.values)
                .isNotEmpty().allMatch { c -> c.contains("import org.junit.jupiter.api.Timeout;") && c.contains("@Timeout(120)\nclass ") }
            assertThat(it.context).contains("spring-javaformat")
            assertThat(it.referenceAnswer).isNotBlank()
            // ADR 0047: hidden acceptance tests, the existing tests protected, a no-op fails
            val acceptance = it.repository!!.acceptance!!
            assertThat(acceptance.requireChanges).isTrue()
            assertThat(acceptance.files.keys).anyMatch { p -> p.startsWith("src/test/java/org/springframework/samples/petclinic/acceptance/") }
            assertThat(acceptance.files.values).allMatch { c -> c.isNotBlank() && '\r' !in c }
            assertThat(acceptance.protectedPaths).isNotEmpty().allMatch { p -> p.startsWith("src/test/") }
        }
        // golden files are compared byte for byte by the hidden tests
        val golden = coding.single { it.difficulty == Difficulty.EXPERT }.repository!!.acceptance!!.files
        assertThat(golden["src/test/resources/acceptance/vets.json"]).startsWith("{").doesNotEndWith("\n")
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
    fun `starter tasks in an earlier version get the current content, edited ones stay (ADR 0047)`() {
        val suite = StarterContent.starterSuite(Fixtures.now)
        val (untouched, edited) = suite.tasks.take(2)
        val oldUntouched = untouched.copy(instructions = "old wording", weight = 2.0)
        val oldEdited = edited.copy(instructions = "the owner's wording")
        val earlier = mapOf(StarterContent.contentHash(oldUntouched) to untouched.name)
        val stale = suite.copy(tasks = listOf(oldUntouched, oldEdited) + suite.tasks.drop(2))

        val upgraded = StarterContent.upgradeUntouchedTasks(stale, Fixtures.now, earlier)!!

        assertThat(upgraded.tasks[0]).isEqualTo(untouched.copy(weight = 2.0))
        assertThat(upgraded.tasks[0].id).isEqualTo(untouched.id)
        assertThat(upgraded.tasks[1].instructions).isEqualTo("the owner's wording")
        assertThat(StarterContent.upgradeUntouchedTasks(upgraded, Fixtures.now, earlier)).isNull()
    }

    @Test
    fun `ADR 0065 - the Spec Kit task as seeded before becomes the specification task, keeping its id and weight`() {
        val suite = StarterContent.starterSuite(Fixtures.now)
        val spec = suite.tasks.single { it.name == StarterContent.SPEC_TASK_NAME }
        assertThat(spec.instructions + spec.rubric).doesNotContain("Spec Kit")
        // the task as seeded before ADR 0065 (its content hash is recorded in EARLIER_VERSIONS)
        val old = spec.copy(
            name = "Write a Spec Kit spec from a two-line ticket",
            instructions = spec.instructions.replace(
                "Turn the ticket in the context into a feature specification (spec.md) with this structure:",
                "Turn the ticket in the context into a Spec Kit feature specification (spec.md) following the Spec Kit\nspec template:",
            ),
            rubric = spec.rubric.replace("follows the structure the instructions ask for:", "follows the Spec Kit template as instructed:")
                .replace("instead of the asked structure", "instead of the template"),
            weight = 3.0,
        )
        assertThat(StarterContent.contentHash(old)).isEqualTo("49c08ab9dd842414fa6481ff241eb458")

        val upgraded = StarterContent.upgradeUntouchedTasks(suite.copy(tasks = suite.tasks.map { if (it.id == spec.id) old else it }), Fixtures.now)!!

        val task = upgraded.tasks.single { it.id == spec.id }
        assertThat(task.name).isEqualTo(StarterContent.SPEC_TASK_NAME)
        assertThat(task.instructions).isEqualTo(spec.instructions)
        assertThat(task.weight).isEqualTo(3.0)
    }

    @Test
    fun `ADR 0065 - an unedited coding task with the earlier verify command gets the current repository, an edited one stays`() {
        val suite = StarterContent.starterSuite(Fixtures.now)
        val coding = suite.tasks.filter { it.kind == TaskKind.CODING_JOB }
        val (untouched, ownCommand, ownText) = coding
        fun earlier(t: pl.mos.backoffice.evaluation.domain.model.EvalTask) = t.copy(
            repository = t.repository!!.copy(
                verifyCommand = "./mvnw -B test",
                acceptance = t.repository!!.acceptance!!.copy(files = t.repository!!.acceptance!!.files.mapValues { (_, c) -> c.replace("@Timeout(120)\n", "") }),
            ),
            timeout = java.time.Duration.ofMinutes(50),
        )
        val stale = mapOf(
            untouched.id to earlier(untouched),
            ownCommand.id to earlier(ownCommand).let { it.copy(repository = it.repository!!.copy(verifyCommand = "./mvnw -B -q test")) },
            ownText.id to earlier(ownText).copy(instructions = "the owner's wording"),
        )

        val upgraded = StarterContent.upgradeUntouchedTasks(suite.copy(tasks = suite.tasks.map { stale[it.id] ?: it }), Fixtures.now)!!

        val byId = upgraded.tasks.associateBy { it.id }
        assertThat(byId.getValue(untouched.id).repository).isEqualTo(untouched.repository)
        // only the repository is replaced: the owner's timeout stays
        assertThat(byId.getValue(untouched.id).timeout).isEqualTo(java.time.Duration.ofMinutes(50))
        assertThat(byId.getValue(ownCommand.id).repository!!.verifyCommand).isEqualTo("./mvnw -B -q test")
        assertThat(byId.getValue(ownText.id).repository!!.verifyCommand).isEqualTo("./mvnw -B test")
        assertThat(StarterContent.upgradeUntouchedTasks(upgraded, Fixtures.now)).isNull()
    }

    @Test
    fun `ADR 0065 - the seed service upgrades the earlier repository of a stored starter task`() {
        service.seedIfEmpty()
        val seeded = suites.findAll().single()
        val target = seeded.tasks.first { it.kind == TaskKind.CODING_JOB }
        suites.save(seeded.copy(tasks = seeded.tasks.map { if (it.id == target.id) it.copy(repository = it.repository!!.copy(verifyCommand = "./mvnw -B test")) else it }))

        assertThat(service.seedIfEmpty().suitesUpgraded).isEqualTo(1)
        assertThat(suites.findAll().single().tasks.single { it.id == target.id }.repository!!.verifyCommand).isEqualTo(StarterContent.PETCLINIC_VERIFY_COMMAND)
        assertThat(service.seedIfEmpty().suitesUpgraded).isZero()
    }

    @Test
    fun `the current starter content is not an earlier version of itself`() {
        assertThat(StarterContent.upgradeUntouchedTasks(StarterContent.starterSuite(Fixtures.now), Fixtures.now)).isNull()
        assertThat(StarterContent.contentHash(StarterContent.codingTasks().first())).hasSize(32)
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
