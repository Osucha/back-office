package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.domain.model.CiContext
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.shared.ValidationException

/** ADR 0034: a CI execution is mapped to the project repository it runs in by normalised URL. */
class RepositoryUrlsTest {

    private val project = Fixtures.project().copy(
        repositories = listOf(
            GitRepository("app", "https://gitlab.example.com/acme/app.git", primary = true),
            GitRepository("docs", "git@gitlab.example.com:acme/docs.git", purpose = RepositoryPurpose.DOCS),
        ),
    )

    @Test
    fun `normalisation ignores scheme, credentials, git suffix, trailing slash, host case and default ports`() {
        val expected = "gitlab.example.com/acme/app"
        listOf(
            "https://gitlab.example.com/acme/app.git",
            "https://gitlab.example.com/acme/app",
            "https://GitLab.Example.com/acme/app/",
            "https://ci-token:secret@gitlab.example.com/acme/app.git",
            "ssh://git@gitlab.example.com:22/acme/app.git",
            "git@gitlab.example.com:acme/app.git",
            "https://gitlab.example.com:443/acme/app.git",
        ).forEach { assertThat(RepositoryUrls.normalise(it)).describedAs(it).isEqualTo(expected) }
        assertThat(RepositoryUrls.normalise("https://gitlab.example.com:8443/acme/app.git")).isEqualTo("gitlab.example.com:8443/acme/app")
        assertThat(RepositoryUrls.normalise(" ")).isNull()
    }

    @Test
    fun `a CI URL matches the repository written in another form`() {
        assertThat(RepositoryUrls.match(project, CiContext(repositoryUrl = "https://gitlab.example.com/acme/docs"))!!.key).isEqualTo("docs")
        assertThat(RepositoryUrls.match(project, CiContext(repositoryUrl = "git@gitlab.example.com:acme/app.git"))!!.key).isEqualTo("app")
    }

    @Test
    fun `an unknown URL is refused with the project repositories, without the credentials of the URL`() {
        assertThatThrownBy { RepositoryUrls.match(project, CiContext(repositoryUrl = "https://bot:t0ken@gitlab.example.com/acme/web.git")) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("https://gitlab.example.com/acme/web.git is not a repository of project SHOP")
            .hasMessageContaining("app: https://gitlab.example.com/acme/app.git, docs: git@gitlab.example.com:acme/docs.git")
            .hasMessageNotContaining("t0ken")
    }

    @Test
    fun `older clients that send only the project path are matched by path suffix, else not at all`() {
        assertThat(RepositoryUrls.match(project, CiContext(projectPath = "acme/app"))!!.key).isEqualTo("app")
        assertThat(RepositoryUrls.match(project, CiContext(projectPath = "acme/web"))).isNull()
        assertThat(RepositoryUrls.match(project, CiContext())).isNull()
    }
}
