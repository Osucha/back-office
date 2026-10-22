package pl.mos.backoffice.projects.adapter.out.scm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.adapter.out.secrets.SecretsProperties
import pl.mos.backoffice.projects.application.port.out.ScmToken
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.shared.security.Actor
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** ADR 0051: the token test reads git advertisements, the token itself, the role and the branch protection; it writes nothing. */
class GitLabScmProbeTest {

    private val server = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
    private val cipher = AesGcmSecretCipher(SecretsProperties(key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), keyId = "k1"))
    private val probe = GitLabScmProbe(cipher, JsonMapper.builder().build())
    private val token = "glpat-reader"
    private val basic = "Basic " + Base64.getEncoder().encodeToString("oauth2:$token".toByteArray())
    private val repository get() = GitRepository("main", "http://localhost:${server.port()}/group/app.git", "main", primary = true)

    @AfterEach
    fun stop() = server.stop()

    private fun gitlab() {
        server.stubFor(
            get(urlEqualTo("/group/app.git/info/refs?service=git-upload-pack")).withHeader("Authorization", equalTo(basic))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-git-upload-pack-advertisement").withBody("001e# service=git-upload-pack\n")),
        )
        server.stubFor(get(urlEqualTo("/group/app.git/info/refs?service=git-receive-pack")).willReturn(aResponse().withStatus(403)))
        server.stubFor(
            get(urlEqualTo("/api/v4/personal_access_tokens/self")).withHeader("PRIVATE-TOKEN", equalTo(token)).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"name":"bo-read","scopes":["read_api","read_repository"],"expires_at":"2027-01-15","active":true}"""),
            ),
        )
        server.stubFor(
            get(urlEqualTo("/api/v4/projects/group%2Fapp")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"default_branch":"main","permissions":{"project_access":null,"group_access":{"access_level":20}}}"""),
            ),
        )
        server.stubFor(
            get(urlEqualTo("/api/v4/projects/group%2Fapp/protected_branches/main")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("""{"name":"main","merge_access_levels":[{"access_level":40}],"push_access_levels":[{"access_level":0}]}"""),
            ),
        )
    }

    @Test
    fun `a reader token can clone, cannot push, and its scopes, role, expiry and the protection are read`() {
        gitlab()
        val result = probe.probe(ScmProvider.GITLAB, repository, ScmToken.Plain(token))
        assertThat(result.failure).isNull()
        assertThat(result.gitRead).isTrue()
        assertThat(result.gitPush).isFalse()
        assertThat(result.scopes).containsExactly("read_api", "read_repository")
        assertThat(result.tokenName).isEqualTo("bo-read")
        assertThat(result.tokenExpiresAt).isEqualTo(LocalDate.parse("2027-01-15"))
        assertThat(result.accessLevel).isEqualTo(20)
        assertThat(result.defaultBranchProtected).isTrue()
        assertThat(result.mergeAccessLevel).isEqualTo(40)
        server.verify(0, anyRequestedFor(anyUrl()).withHeader("X-HTTP-Method-Override", equalTo("POST")))
        assertThat(server.allServeEvents.map { it.request.method.value() }.toSet()).containsExactly("GET")
    }

    @Test
    fun `the stored token is opened by the adapter, a refused token and an unreachable host are failures`() {
        gitlab()
        val id = ScmConnectionId.new()
        val now = Instant.now()
        val stored = ScmConnection(
            id, ProjectId.new(), "main", "reader", ScmProvider.GITLAB, setOf(ScmCapability.GIT_READ),
            cipher.seal(token, ScmConnection.secretAssociatedData(id)), null, null, now, Actor.SYSTEM, now, Actor.SYSTEM,
        )
        assertThat(probe.probe(ScmProvider.GITLAB, repository, ScmToken.Stored(stored)).gitRead).isTrue()

        server.resetAll()
        server.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(401)))
        assertThat(probe.probe(ScmProvider.GITLAB, repository, ScmToken.Plain("wrong")).failure).contains("refused the token")

        val down = GitRepository("main", "http://localhost:1/group/app.git", "main", primary = true)
        assertThat(probe.probe(ScmProvider.GITLAB, down, ScmToken.Plain(token)).failure).contains("not reachable")
        assertThat(probe.probe(ScmProvider.GITLAB, repository.copy(url = "git@host:group/app.git"), ScmToken.Plain(token)).failure).contains("not an http(s) git URL")
    }
}
