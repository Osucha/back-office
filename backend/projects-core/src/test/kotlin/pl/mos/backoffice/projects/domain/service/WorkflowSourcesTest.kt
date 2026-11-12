package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowSource
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.GitAccess
import java.time.Instant

/** ADR 0063: the workflow's sources (everything READ for agents) and what a delivery writes back to. */
class WorkflowSourcesTest {

    private val app = GitRepository("app", "https://git.example.com/acme/app.git", "main", RepositoryPurpose.SOURCE, primary = true)
    private val api = GitRepository("api", "https://git.example.com/acme/api.git", "develop", RepositoryPurpose.SOURCE)
    private val docs = GitRepository("docs", "https://git.example.com/acme/docs.git", "main", RepositoryPurpose.DOCS, "Product documentation")
    private val multi = Fixtures.project().copy(repositories = listOf(app, api, docs))
    private val repoLess = Fixtures.project().copy(repositories = emptyList())

    private val standards = LibrarySource(
        LibrarySourceId.new(), "standards", "Company standards", url = "https://git.example.com/acme/standards.git",
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
    private val library: (String) -> LibrarySource? = { if (it == "standards") standards else null }

    @Test
    fun `no sources = the primary repository, read-only`() {
        val plan = WorkflowSources.plan(multi, emptyList())
        assertThat(plan.valid).isTrue()
        assertThat(plan.repositories.single().let { Triple(it.key, it.access, it.primary) }).isEqualTo(Triple("app", GitAccess.READ, true))
        assertThat(WorkflowSources.plan(repoLess, emptyList()).repositories).isEmpty()
    }

    @Test
    fun `listed sources are cloned in order, all READ - the project primary stays the job primary`() {
        val plan = WorkflowSources.plan(multi, listOf(WorkflowSource("docs"), WorkflowSource("api"), WorkflowSource("app")))
        assertThat(plan.repositories.map { "${it.key}:${it.access}:${it.primary}" }).containsExactly("docs:READ:false", "api:READ:false", "app:READ:true")
        // no project primary among them: the first listed is the job's primary (working directory)
        assertThat(WorkflowSources.plan(multi, listOf(WorkflowSource("docs"), WorkflowSource("api"))).primary!!.key).isEqualTo("docs")
    }

    @Test
    fun `library sources join under lib-key next to the primary repository, also in a project without repositories (ADR 0043)`() {
        val plan = WorkflowSources.plan(multi, listOf(WorkflowSource("standards", library = true)), library)
        assertThat(plan.repositories.map { Triple(it.key, it.access, it.library) })
            .containsExactly(Triple("app", GitAccess.READ, false), Triple("lib-standards", GitAccess.READ, true))
        assertThat(plan.primary!!.key).isEqualTo("app")
        assertThat(plan.repositories.last().toTemplateMap()["library"]).isEqualTo(true)
        assertThat(plan.projectRepositories.map { it.key }).containsExactly("app")

        val onlyShared = WorkflowSources.plan(repoLess, listOf(WorkflowSource("standards", library = true)), library)
        assertThat(onlyShared.primary!!.key).isEqualTo("lib-standards")

        val unknown = WorkflowSources.plan(multi, listOf(WorkflowSource("nope", library = true)), library)
        assertThat(unknown.errors.single()).contains("library source nope does not exist")
        val clash = WorkflowSources.plan(multi.copy(repositories = listOf(app, GitRepository("lib-standards", "https://x/y.git"))), listOf(WorkflowSource("lib-standards"), WorkflowSource("standards", true)), library)
        assertThat(clash.errors.single()).contains("clashes")
    }

    @Test
    fun `unknown and duplicate keys and a missing primary are errors that list the project repositories`() {
        val unknown = WorkflowSources.plan(multi, listOf(WorkflowSource("web")))
        assertThat(unknown.errors.single()).contains("repository web is not a repository of project SHOP").contains("app:", "api:", "docs:")
        assertThat(WorkflowSources.plan(multi, listOf(WorkflowSource("app"), WorkflowSource("app"))).errors).anySatisfy { assertThat(it).contains("listed twice") }
        val noPrimary = multi.copy(repositories = listOf(app.copy(primary = false), docs))
        assertThat(WorkflowSources.plan(noPrimary, emptyList()).errors.single()).contains("no primary repository").contains("app: https://git.example.com/acme/app.git")
        assertThat(WorkflowSources.plan(repoLess, listOf(WorkflowSource("app"))).errors.single()).contains("is not a repository of project SHOP")
        assertThatThrownBy { WorkflowSource(" ") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a step needs a repository unless it says repositoryOptional`() {
        val empty = WorkflowSources.plan(repoLess, emptyList())
        assertThat(WorkflowSources.stepProblem(repoLess, empty, emptyMap())).contains("project SHOP has none")
        assertThat(WorkflowSources.stepProblem(repoLess, empty, mapOf("repositoryOptional" to true))).isNull()
        assertThat(WorkflowSources.stepProblem(multi, WorkflowSources.plan(multi, emptyList()), emptyMap())).isNull()
        assertThat(WorkflowSources.repositoryOptional(mapOf("repositoryOptional" to "true"))).isTrue()
    }

    @Test
    fun `a delivery writes back to the project repositories of the sources, never to library sources`() {
        val plan = WorkflowSources.plan(multi, listOf(WorkflowSource("app"), WorkflowSource("docs"), WorkflowSource("standards", true)), library)
        val all = WorkflowSources.delivery(plan, emptyList())
        assertThat(all.repositories.map { "${it.key}:${it.access}:${it.primary}" }).containsExactly("app:PUSH_MR:true", "docs:PUSH_MR:false")
        assertThat(all.pushing).hasSize(2)
        // pushing to a documentation and a source repository is allowed with a warning
        assertThat(all.warnings.single()).contains("documentation repository and a source repository")

        val docsOnly = WorkflowSources.delivery(plan, listOf("docs"))
        assertThat(docsOnly.repositories.single().let { it.key to it.primary }).isEqualTo("docs" to true)
        assertThat(docsOnly.warnings).isEmpty()

        assertThat(WorkflowSources.delivery(plan, listOf("lib-standards")).errors.single()).contains("read-only")
        assertThat(WorkflowSources.delivery(plan, listOf("api")).errors.single()).contains("api is not a project repository among the workflow's sources (app, docs)")
        assertThat(WorkflowSources.delivery(WorkflowSources.plan(repoLess, emptyList()), emptyList()).errors.single()).contains("no project repository")
        val invalid = WorkflowSources.plan(multi, listOf(WorkflowSource("web")))
        assertThat(WorkflowSources.delivery(invalid, emptyList())).isSameAs(invalid)
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
        assertThat(multi.primaryRepository).isEqualTo(app)
        assertThat(repoLess.describeRepositories()).isEqualTo("none")
    }
}
