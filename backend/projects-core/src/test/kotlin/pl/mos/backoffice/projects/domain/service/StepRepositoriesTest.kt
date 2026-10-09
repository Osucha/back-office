package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.GitAccess

/** ADR 0034: which repositories a step works on, with which access, and what the validator says about it. */
class StepRepositoriesTest {

    private val app = GitRepository("app", "https://git.example.com/acme/app.git", "main", RepositoryPurpose.SOURCE, primary = true)
    private val api = GitRepository("api", "https://git.example.com/acme/api.git", "develop", RepositoryPurpose.SOURCE)
    private val docs = GitRepository("docs", "https://git.example.com/acme/docs.git", "main", RepositoryPurpose.DOCS, "Product documentation")
    private val multi = Fixtures.project().copy(repositories = listOf(app, api, docs))
    private val repoLess = Fixtures.project().copy(repositories = emptyList())

    private fun entries(vararg pairs: Pair<String, String?>) = mapOf(StepRepositories.CONFIG_KEY to pairs.map { (k, a) -> mapOf("key" to k, "access" to a) })

    @Test
    fun `a runner step without a list uses the primary repository with the derived access`() {
        val pushing = StepRepositories.plan(multi, emptyMap(), runnerStep = true, pushes = true)
        assertThat(pushing.valid).isTrue()
        assertThat(pushing.repositories.single().let { Triple(it.key, it.access, it.primary) }).isEqualTo(Triple("app", GitAccess.PUSH_MR, true))

        val reading = StepRepositories.plan(multi, mapOf(StepRepositories.CONFIG_KEY to emptyList<Any>()), runnerStep = true, pushes = false)
        assertThat(reading.repositories.single().access).isEqualTo(GitAccess.READ)
    }

    @Test
    fun `steps that need no repository work in a project without repositories`() {
        val plan = StepRepositories.plan(repoLess, emptyMap(), runnerStep = false, pushes = false)
        assertThat(plan.valid).isTrue()
        assertThat(plan.repositories).isEmpty()
    }

    @Test
    fun `a runner step in a project without repositories is an error`() {
        val plan = StepRepositories.plan(repoLess, emptyMap(), runnerStep = true, pushes = true)
        assertThat(plan.valid).isFalse()
        assertThat(plan.errors.single()).contains("project SHOP has none")

        assertThat(StepRepositories.plan(repoLess, entries("app" to "READ"), runnerStep = true, pushes = true).errors.single()).contains("has none")
    }

    @Test
    fun `without a primary repository a runner step must name its repositories`() {
        val noPrimary = multi.copy(repositories = listOf(app.copy(primary = false), docs))
        val plan = StepRepositories.plan(noPrimary, emptyMap(), runnerStep = true, pushes = true)
        assertThat(plan.errors.single()).contains("no primary repository").contains("app: https://git.example.com/acme/app.git")

        val named = StepRepositories.plan(noPrimary, entries("docs" to "PUSH_MR", "app" to "READ"), runnerStep = true, pushes = true)
        assertThat(named.valid).isTrue()
        // no project primary among them: the first named entry is the job's primary (working directory)
        assertThat(named.primary!!.key).isEqualTo("docs")
    }

    @Test
    fun `unknown and duplicate keys and bad access levels are errors that list the project repositories`() {
        val unknown = StepRepositories.plan(multi, entries("web" to "READ"), runnerStep = true, pushes = true)
        assertThat(unknown.errors.single()).contains("repository web is not a repository of project SHOP").contains("app:", "api:", "docs:")

        val duplicate = StepRepositories.plan(multi, entries("app" to "READ", "app" to "PUSH_MR"), runnerStep = true, pushes = true)
        assertThat(duplicate.errors).anySatisfy { assertThat(it).contains("listed twice") }

        val badAccess = StepRepositories.plan(multi, entries("app" to "MERGE"), runnerStep = true, pushes = true)
        assertThat(badAccess.errors.single()).contains("READ or PUSH_MR, not MERGE")

        val none = StepRepositories.plan(multi, entries("app" to "NONE"), runnerStep = true, pushes = true)
        assertThat(none.errors.single()).contains("not NONE")
    }

    @Test
    fun `PUSH_MR on a step that does not push is an error, repositories on a non-runner step too`() {
        val readOnly = StepRepositories.plan(multi, entries("app" to "PUSH_MR"), runnerStep = true, pushes = false)
        assertThat(readOnly.errors.single()).contains("does not push")

        val llm = StepRepositories.plan(multi, entries("app" to "READ"), runnerStep = false, pushes = false)
        assertThat(llm.errors.single()).contains("only runner steps")
    }

    @Test
    fun `a step may read several repositories and push to several, the project primary stays the job primary`() {
        val plan = StepRepositories.plan(multi, entries("docs" to "READ", "api" to "PUSH_MR", "app" to "PUSH_MR"), runnerStep = true, pushes = true)
        assertThat(plan.valid).isTrue()
        assertThat(plan.repositories.map { "${it.key}:${it.access}:${it.primary}" }).containsExactly("docs:READ:false", "api:PUSH_MR:false", "app:PUSH_MR:true")
        assertThat(plan.pushing.map { it.key }).containsExactly("api", "app")
        assertThat(plan.warnings).isEmpty()
        // access defaults to READ (least privilege); a plain string is a read entry
        val shorthand = StepRepositories.plan(multi, mapOf(StepRepositories.CONFIG_KEY to listOf("docs", mapOf("key" to "app"))), runnerStep = true, pushes = true)
        assertThat(shorthand.repositories.map { it.access }).containsOnly(GitAccess.READ)
    }

    @Test
    fun `pushing to a documentation and a source repository in one step is allowed with a warning`() {
        val plan = StepRepositories.plan(multi, entries("app" to "PUSH_MR", "docs" to "PUSH_MR"), runnerStep = true, pushes = true)
        assertThat(plan.valid).isTrue()
        assertThat(plan.warnings.single()).contains("documentation repository and a source repository")
    }

    @Test
    fun `project repositories have unique keys, at most one primary and entrypoints restricted to existing repositories`() {
        assertThatThrownBy { multi.copy(repositories = listOf(app, app.copy(primary = false))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("not unique")
        assertThatThrownBy { multi.copy(repositories = listOf(app, api.copy(primary = true))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("at most one")
        assertThatThrownBy { GitRepository("App", "https://x/y.git") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { multi.withEntrypoints(listOf(CiEntrypoint("code-review", WorkflowDefinitionId.new(), repository = "web")), Fixtures.now) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("restricted to repository web")

        val restricted = multi.withEntrypoints(listOf(CiEntrypoint("code-review", WorkflowDefinitionId.new(), repository = "api")), Fixtures.now)
        // removing the repository drops the restriction instead of locking CI out
        val updated = restricted.update(restricted.name, "", listOf(app), restricted.issueTracker, "", restricted.defaults, Fixtures.now)
        assertThat(updated.ciEntrypoints.single().repository).isNull()
        assertThat(Project::class.java).isNotNull()
    }

    @Test
    fun `the primary repository is optional`() {
        assertThat(multi.primaryRepository).isEqualTo(app)
        assertThat(repoLess.primaryRepository).isNull()
        assertThat(multi.copy(repositories = listOf(api, docs)).primaryRepository).isNull()
        assertThat(repoLess.describeRepositories()).isEqualTo("none")
    }
}
