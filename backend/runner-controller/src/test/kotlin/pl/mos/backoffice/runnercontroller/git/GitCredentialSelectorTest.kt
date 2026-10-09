package pl.mos.backoffice.runnercontroller.git

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.policy.PolicyViolation
import tools.jackson.databind.json.JsonMapper

/** Least-privilege git credentials (ADR 0031): selection by repository and level, refusals, fallbacks. */
class GitCredentialSelectorTest {
    private val json = JsonMapper.builder().build()
    private val app = "https://gitlab.example.com/shop/app.git"

    private val selector = GitCredentialSelector(
        listOf(
            RepoCredential("*", read = "any-read"),
            RepoCredential("gitlab.example.com", read = "gl-read", push = "gl-push"),
            RepoCredential("gitlab.example.com/shop/*", read = "shop-read", push = "shop-push"),
            RepoCredential("gitlab.example.com/shop/app", read = "app-read", push = "app-push"),
            RepoCredential("*.corp.example", read = "corp-read", push = "corp-push"),
        ),
    )

    @Test
    fun `READ gets the read Secret, PUSH_MR the push Secret, NONE nothing`() {
        assertThat(selector.select(app, GitAccess.READ)).isEqualTo(GitCredentialSelection(GitAccess.READ, "app-read", GitAccess.READ))
        assertThat(selector.select(app, GitAccess.PUSH_MR)).isEqualTo(GitCredentialSelection(GitAccess.PUSH_MR, "app-push", GitAccess.PUSH_MR))
        assertThat(selector.select(app, GitAccess.NONE)).isEqualTo(GitCredentialSelection.NONE)
        assertThat(selector.select(null, GitAccess.PUSH_MR)).isEqualTo(GitCredentialSelection.NONE)
    }

    @Test
    fun `the most specific pattern wins - repository, group, host, sub-domain wildcard, default`() {
        assertThat(selector.select("https://GitLab.example.com:8443/Shop/App/", GitAccess.PUSH_MR).credential).isEqualTo("app-push")
        assertThat(selector.select("https://gitlab.example.com/shop/sub/web.git", GitAccess.PUSH_MR).credential).isEqualTo("shop-push")
        assertThat(selector.select("https://oauth2@gitlab.example.com/other/lib.git", GitAccess.READ).credential).isEqualTo("gl-read")
        assertThat(selector.select("https://git.corp.example/x/y.git", GitAccess.PUSH_MR).credential).isEqualTo("corp-push")
        assertThat(selector.select("git@github.com:org/repo.git", GitAccess.READ).credential).isEqualTo("any-read")
    }

    @Test
    fun `a PUSH_MR job whose match has no push credential is refused`() {
        assertThatThrownBy { selector.select("https://github.com/org/repo.git", GitAccess.PUSH_MR) }
            .isInstanceOf(PolicyViolation::class.java)
            .hasMessageContaining("PUSH_MR job for github.com/org/repo").hasMessageContaining("no push credential")
    }

    @Test
    fun `a READ job with only a push credential is refused unless allowPushCredentialForRead`() {
        val pushOnly = listOf(RepoCredential("gitlab.example.com", push = "gl-push"))
        assertThatThrownBy { GitCredentialSelector(pushOnly).select(app, GitAccess.READ) }
            .isInstanceOf(PolicyViolation::class.java).hasMessageContaining("only a push credential")
        val allowed = GitCredentialSelector(pushOnly, allowPushCredentialForRead = true).select(app, GitAccess.READ)
        assertThat(allowed.credential).isEqualTo("gl-push")
        assertThat(allowed.credentialLevel).isEqualTo(GitAccess.PUSH_MR)
        assertThat(allowed.notes.single()).contains("allowPushCredentialForRead")
    }

    @Test
    fun `legacy existingSecret and hostSecrets are push-level fallbacks used only when no entry matches, with a warning`() {
        val legacy = GitCredentialSelector.forSecrets(
            ControllerProperties.Git(
                repoSecrets = listOf(RepoCredential("gitlab.example.com/shop/app", read = "app-read", push = "app-push")),
                secrets = mapOf("*" to "back-office-git", "github.com" to "git-github"),
            ),
        )
        assertThat(legacy.select(app, GitAccess.READ).credential).isEqualTo("app-read")
        assertThat(legacy.select("https://github.com/org/x.git", GitAccess.PUSH_MR).credential).isEqualTo("git-github")
        assertThat(legacy.select("https://gitlab.example.com/other/x.git", GitAccess.PUSH_MR).credential).isEqualTo("back-office-git")
        assertThatThrownBy { legacy.select("https://gitlab.example.com/other/x.git", GitAccess.READ) }
            .isInstanceOf(PolicyViolation::class.java).hasMessageContaining("existingSecret")
        assertThat(legacy.warnings().single()).contains("push-level").contains("back-office-git").contains("repoSecrets")
        assertThat(GitCredentialSelector.forSecrets(ControllerProperties.Git()).warnings()).isEmpty()
    }

    @Test
    fun `without any credential the clone is anonymous and noted`() {
        val none = GitCredentialSelector.EMPTY.select(app, GitAccess.PUSH_MR)
        assertThat(none.credential).isNull()
        assertThat(none.notes.single()).contains("anonymous")
    }

    @Test
    fun `docker - BO_GIT_TOKEN_READ for READ, BO_GIT_TOKEN_PUSH for PUSH_MR, BO_GIT_TOKEN as push fallback`() {
        val git = ControllerProperties.Git()
        val both = GitCredentialSelector.forEnvironment(git, mapOf("BO_GIT_TOKEN_READ" to "r", "BO_GIT_TOKEN_PUSH" to "p")::get)
        assertThat(both.select(app, GitAccess.READ).credential).isEqualTo("BO_GIT_TOKEN_READ")
        assertThat(both.select(app, GitAccess.PUSH_MR).credential).isEqualTo("BO_GIT_TOKEN_PUSH")
        assertThat(both.warnings()).isEmpty()

        val legacy = GitCredentialSelector.forEnvironment(git, mapOf("BO_GIT_TOKEN" to "x")::get)
        assertThat(legacy.select(app, GitAccess.PUSH_MR).credential).isEqualTo("BO_GIT_TOKEN")
        assertThatThrownBy { legacy.select(app, GitAccess.READ) }.isInstanceOf(PolicyViolation::class.java)
        assertThat(legacy.warnings().single()).contains("BO_GIT_TOKEN")

        val readOnly = GitCredentialSelector.forEnvironment(git, mapOf("BO_GIT_TOKEN_READ" to "r", "BO_GIT_TOKEN" to "")::get)
        assertThatThrownBy { readOnly.select(app, GitAccess.PUSH_MR) }.isInstanceOf(PolicyViolation::class.java)
    }

    @Test
    fun `access level from the signed spec, derived for specs without it`() {
        fun of(text: String) = GitAccess.of(json.readTree(text) as tools.jackson.databind.node.ObjectNode)
        assertThat(of("""{"mode":"IMPLEMENT","gitAccess":"READ","repository":{"url":"$app","push":true}}""")).isEqualTo(GitAccess.READ)
        assertThat(of("""{"mode":"IMPLEMENT","repository":{"url":"$app","push":true}}""")).isEqualTo(GitAccess.PUSH_MR)
        assertThat(of("""{"mode":"SPEC_KIT","repository":{"url":"$app","push":false}}""")).isEqualTo(GitAccess.READ)
        assertThat(of("""{"mode":"EVAL_CODING","repository":{"url":"$app","push":true}}""")).isEqualTo(GitAccess.READ)
        assertThat(of("""{"mode":"IMPLEMENT","gitAccess":"PUSH_MR"}""")).isEqualTo(GitAccess.NONE)
        assertThatThrownBy { of("""{"mode":"IMPLEMENT","gitAccess":"ADMIN","repository":{"url":"$app"}}""") }.isInstanceOf(PolicyViolation::class.java)
    }

    @Test
    fun `multi-repository spec - repositories parsed with their own level, legacy when absent or empty next to repository`() {
        fun repos(text: String) = JobRepositories.of(json.readTree(text))
        val multi = repos(
            """{"gitAccess":"READ","repository":{"url":"$app"},"repositories":[
               {"key":"docs","url":"https://gitlab.example.com/shop/docs.git","access":"READ"},
               {"key":"src","url":"$app","access":"push_mr","primary":true},
               {"key":"tools","url":"https://gitlab.example.com/shop/tools.git"}]}""",
        )
        assertThat(multi.multi).isTrue()
        assertThat(multi.repositories).containsExactly(
            JobRepository("docs", "https://gitlab.example.com/shop/docs.git", GitAccess.READ),
            JobRepository("src", app, GitAccess.PUSH_MR, primary = true),
            JobRepository("tools", "https://gitlab.example.com/shop/tools.git", GitAccess.READ), // absent access = READ
        )
        assertThat(multi.access).isEqualTo(GitAccess.PUSH_MR)
        assertThat(multi.urls).hasSize(3)

        val legacy = repos("""{"mode":"IMPLEMENT","repository":{"url":"$app","push":true},"repositories":[]}""")
        assertThat(legacy.multi).isFalse()
        assertThat(legacy.repositories.single().access).isEqualTo(GitAccess.PUSH_MR)
        assertThat(repos("""{"mode":"IMPLEMENT"}""")).isEqualTo(JobRepositories(false, emptyList()))
        assertThat(repos("""{"repositories":[]}""")).isEqualTo(JobRepositories(true, emptyList()))

        for (bad in listOf(
            """[{"key":"a/b","url":"u"}]""", """[{"key":"a"}]""", """[{"key":"a","url":"u","access":"ADMIN"}]""",
            """[{"key":"a","url":"u"},{"key":"a","url":"v"}]""", """["x"]""",
        )) {
            assertThatThrownBy { repos("""{"repositories":$bad}""") }.describedAs(bad).isInstanceOf(PolicyViolation::class.java)
        }
    }

    @Test
    fun `plan selects per repository and names the refused repository`() {
        val plan = selector.plan(
            json.readTree(
                """{"repositories":[{"key":"docs","url":"https://gitlab.example.com/shop/docs.git","access":"READ"},
                   {"key":"src","url":"$app","access":"PUSH_MR"},{"key":"pub","url":"https://github.com/o/p.git","access":"NONE"}]}""",
            ),
        )
        assertThat(plan.tokens).containsExactly(
            RepositoryToken("docs", GitAccess.READ, "shop-read"),
            RepositoryToken("src", GitAccess.PUSH_MR, "app-push"),
        )
        assertThat(plan.legacy).isEqualTo(GitCredentialSelection.NONE)
        assertThat(plan.describe()).isEqualTo("docs=READ(shop-read), src=PUSH_MR(app-push), pub=NONE(none)")

        val readOnly = GitCredentialSelector(listOf(RepoCredential("gitlab.example.com", read = "gl-read")))
        assertThatThrownBy {
            readOnly.plan(json.readTree("""{"repositories":[{"key":"docs","url":"https://gitlab.example.com/shop/docs.git"},{"key":"src","url":"$app","access":"PUSH_MR"}]}"""))
        }.isInstanceOf(PolicyViolation::class.java)
            .hasMessageStartingWith("repository 'src' (gitlab.example.com/shop/app): git credential: PUSH_MR job")
    }
}
