package pl.mos.backoffice.projects.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.domain.model.ScmCapability.CI_READ
import pl.mos.backoffice.projects.domain.model.ScmCapability.GIT_READ
import pl.mos.backoffice.projects.domain.model.ScmCapability.MR_COMMENT
import pl.mos.backoffice.projects.domain.model.ScmCapability.PUSH_BRANCH
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.time.LocalDate

/** ADR 0051: what a tested token grants, lacks and can do beyond what it declares. */
class ScmVerificationPolicyTest {

    private val now = Instant.parse("2026-10-22T10:00:00Z")
    private val reader = ScmProbe(
        gitRead = true, gitPush = false, scopes = listOf("read_api", "read_repository"), tokenName = "bo-read",
        tokenExpiresAt = LocalDate.parse("2027-01-15"), accessLevel = 20, defaultBranch = "main", defaultBranchProtected = true, mergeAccessLevel = 40,
    )

    private fun evaluate(declared: Set<ScmCapability>, probe: ScmProbe) = ScmVerificationPolicy.evaluate(declared, probe, now)

    @Test
    fun `a Reporter token with read scopes grants reading and is verified read-only`() {
        val v = evaluate(setOf(GIT_READ, CI_READ), reader)
        assertThat(v.status).isEqualTo(VerificationStatus.OK)
        assertThat(v.granted).containsExactlyInAnyOrder(GIT_READ, CI_READ)
        assertThat(v.missing).isEmpty()
        assertThat(v.writePowers).isEmpty()
        assertThat(v.role).isEqualTo("Reporter")
        assertThat(v.tokenExpiresAt).isEqualTo(LocalDate.parse("2027-01-15"))
        assertThat(connection(setOf(GIT_READ, CI_READ), v).verifiedReadOnly).isTrue()
    }

    @Test
    fun `a token that can push or call the whole API is flagged as excess and never counts as read-only`() {
        val developer = reader.copy(gitPush = true, scopes = listOf("api"), accessLevel = 30)
        val v = evaluate(setOf(GIT_READ), developer)
        assertThat(v.status).isEqualTo(VerificationStatus.WARNING)
        assertThat(v.writePowers).containsExactly("git push", "api scope")
        assertThat(connection(setOf(GIT_READ), v).verifiedReadOnly).isFalse()
        // declaring the writes removes the excess
        assertThat(evaluate(setOf(GIT_READ, PUSH_BRANCH, MR_COMMENT), developer).writePowers).isEmpty()
    }

    @Test
    fun `missing rights fail the test with the reason`() {
        val v = evaluate(setOf(GIT_READ, PUSH_BRANCH), reader)
        assertThat(v.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(v.missing).containsExactly(PUSH_BRANCH)
        assertThat(v.messages.single { it.code == "MISSING_PUSH_BRANCH" }.text).contains("write_repository")

        val gitOnly = reader.copy(scopes = null, accessLevel = null, defaultBranchProtected = null, mergeAccessLevel = null)
        val noApi = evaluate(setOf(CI_READ), gitOnly)
        assertThat(noApi.missing).containsExactly(CI_READ)
        assertThat(noApi.messages.single().text).contains("cannot call the API")
        assertThat(evaluate(setOf(GIT_READ), gitOnly).messages.single().code).isEqualTo("NO_API")
    }

    @Test
    fun `a role that can merge, an unprotected default branch and an unreachable host are reported`() {
        val maintainer = reader.copy(accessLevel = 40)
        val v = evaluate(setOf(GIT_READ, CI_READ), maintainer)
        assertThat(v.writePowers).containsExactly("merge into main")
        assertThat(v.messages.map { it.code }).contains("EXCESS", "ROLE_TOO_HIGH")

        val unprotected = evaluate(setOf(GIT_READ, PUSH_BRANCH), reader.copy(gitPush = true, defaultBranchProtected = false, mergeAccessLevel = null, accessLevel = 30))
        assertThat(unprotected.writePowers).containsExactly("merge into main")
        assertThat(unprotected.messages.map { it.code }).contains("UNPROTECTED_DEFAULT_BRANCH")

        val down = evaluate(setOf(GIT_READ), ScmProbe(failure = "git.example.com is not reachable"))
        assertThat(down.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(down.missing).containsExactly(GIT_READ)
    }

    @Test
    fun `the selector takes the least privileged working connection of the repository`() {
        val read = connection(setOf(GIT_READ, CI_READ), evaluate(setOf(GIT_READ, CI_READ), reader), "read")
        val push = connection(setOf(GIT_READ, PUSH_BRANCH), evaluate(setOf(GIT_READ, PUSH_BRANCH), reader.copy(gitPush = true, accessLevel = 30)), "push")
        val broken = connection(setOf(GIT_READ), evaluate(setOf(GIT_READ), ScmProbe(failure = "down")), "aaa-broken")
        val other = connection(setOf(GIT_READ), evaluate(setOf(GIT_READ), reader), "docs", repositoryKey = "docs")
        val all = listOf(push, broken, read, other)

        assertThat(ScmConnectionSelector.select(all, "main", setOf(GIT_READ))).isEqualTo(read)
        assertThat(ScmConnectionSelector.select(all, "main", setOf(PUSH_BRANCH))).isEqualTo(push)
        assertThat(ScmConnectionSelector.select(all, "main", setOf(GIT_READ), readOnly = true)).isEqualTo(read)
        assertThat(ScmConnectionSelector.select(listOf(push), "main", setOf(GIT_READ), readOnly = true)).isNull()
        assertThat(ScmConnectionSelector.select(all, "docs", setOf(CI_READ))).isNull()
    }

    private fun connection(capabilities: Set<ScmCapability>, verification: ScmVerification, name: String = "c", repositoryKey: String = "main") = ScmConnection(
        id = ScmConnectionId.new(), projectId = ProjectId.new(), repositoryKey = repositoryKey, name = name, provider = ScmProvider.GITLAB,
        capabilities = capabilities, secret = SealedSecret("k1", "w", "c"), secretExpiresAt = null, verification = verification,
        createdAt = now, createdBy = Actor.SYSTEM, updatedAt = now, updatedBy = Actor.SYSTEM,
    )
}
