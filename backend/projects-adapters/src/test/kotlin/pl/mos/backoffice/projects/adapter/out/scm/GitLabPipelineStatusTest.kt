package pl.mos.backoffice.projects.adapter.out.scm

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.adapter.out.secrets.SecretsProperties
import pl.mos.backoffice.projects.application.port.out.PipelineState
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ScmCapability
import pl.mos.backoffice.projects.domain.model.ScmConnection
import pl.mos.backoffice.projects.domain.model.ScmConnectionId
import pl.mos.backoffice.projects.domain.model.ScmProvider
import pl.mos.backoffice.shared.security.Actor
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/** ADR 0052: the latest pipeline of a branch, its failed jobs that may not fail and the tails of their logs. */
class GitLabPipelineStatusTest {

    private val server = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
    private val cipher = AesGcmSecretCipher(SecretsProperties(key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 }), keyId = "k1"))
    private val status = GitLabPipelineStatus(cipher, JsonMapper.builder().build())
    private val repository get() = GitRepository("main", "http://localhost:${server.port()}/group/app.git", "develop", primary = true)
    private val id = ScmConnectionId.new()
    private val connection = Instant.now().let { now ->
        ScmConnection(id, ProjectId.new(), "main", "ci", ScmProvider.GITLAB, setOf(ScmCapability.CI_READ), cipher.seal("tkn-ci", ScmConnection.secretAssociatedData(id)), null, null, now, Actor.SYSTEM, now, Actor.SYSTEM)
    }

    @AfterEach
    fun stop() = server.stop()

    private fun json(path: String, body: String) = server.stubFor(
        get(urlEqualTo("/api/v4$path")).withHeader("PRIVATE-TOKEN", equalTo("tkn-ci"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
    )

    @Test
    fun `a failed pipeline reports its failed jobs that may not fail with the end of their logs`() {
        json("/projects/group%2Fapp/pipelines?ref=bo%2F1234&sha=abc&per_page=1&order_by=id&sort=desc",
            """[{"id":42,"status":"failed","web_url":"http://gitlab/p/42"}]""")
        json("/projects/group%2Fapp/pipelines/42/jobs?scope[]=failed&per_page=100",
            """[{"id":1,"name":"frontend:test","stage":"test","allow_failure":false,"web_url":"http://gitlab/j/1"},
                {"id":2,"name":"backend:detekt","stage":"test","allow_failure":true}]""")
        server.stubFor(
            get(urlEqualTo("/api/v4/projects/group%2Fapp/jobs/1/trace")).willReturn(
                aResponse().withStatus(200).withBody(
                    (1..100).joinToString("\n") { "2026-10-23T12:00:00.000Z 01O line $it" } +
                        "\n\u001B[31;1m× renders a row\u001B[0;m\nsection_end:1792767400:step_script\nERROR: Job failed: exit code 1",
                ),
            ),
        )
        val report = status.latest(repository, connection, "bo/1234", "abc")
        assertThat(report.state).isEqualTo(PipelineState.FAILED)
        assertThat(report.id).isEqualTo(42)
        val job = report.failedJobs.single()
        assertThat(job.name).isEqualTo("frontend:test")
        assertThat(job.logTail.lines()).hasSize(GitLabPipelineStatus.LOG_TAIL_LINES)
        assertThat(job.logTail).endsWith("× renders a row\nERROR: Job failed: exit code 1").doesNotContain("\u001B").doesNotContain("section_end").doesNotContain("01O")
    }

    @Test
    fun `running, successful and missing pipelines, and an unreachable host`() {
        json("/projects/group%2Fapp/pipelines?ref=bo%2F1&per_page=1&order_by=id&sort=desc", """[{"id":5,"status":"running"}]""")
        assertThat(status.latest(repository, connection, "bo/1", null).state).isEqualTo(PipelineState.RUNNING)
        json("/projects/group%2Fapp/pipelines?ref=bo%2F2&per_page=1&order_by=id&sort=desc", """[{"id":6,"status":"success"}]""")
        assertThat(status.latest(repository, connection, "bo/2", null).state).isEqualTo(PipelineState.SUCCESS)
        json("/projects/group%2Fapp/pipelines?ref=bo%2F3&per_page=1&order_by=id&sort=desc", "[]")
        assertThat(status.latest(repository, connection, "bo/3", null).state).isEqualTo(PipelineState.NONE)
        assertThat(status.latest(repository.copy(url = "http://localhost:1/group/app.git"), connection, "bo/1", null).state).isEqualTo(PipelineState.NONE)
        assertThat(GitLabPipelineStatus.stateOf("manual")).isEqualTo(PipelineState.SUCCESS)
        assertThat(GitLabPipelineStatus.stateOf("canceled")).isEqualTo(PipelineState.FAILED)
        assertThat(GitLabPipelineStatus.stateOf("pending")).isEqualTo(PipelineState.RUNNING)
    }
}
