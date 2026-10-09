package pl.mos.backoffice.projects.adapter.out.jira

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import pl.mos.backoffice.projects.adapter.config.IntegrationsProperties
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.adapter.out.secrets.SecretsProperties
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import java.time.Instant
import java.util.Base64

/** ADR 0030: the Jira adapter per connection, and verification against `myself` / `mypermissions` (WireMock). */
class JiraIssueTrackerFactoryTest {

    private val jira = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
    private val cipher = AesGcmSecretCipher(SecretsProperties(key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), keyId = "k1"))
    // HTTP/1.1: the JDK client tries an h2c upgrade on plain http, which WireMock answers with RST_STREAM for bodies
    private val http11 = org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build())
    private val factory = JiraIssueTrackerFactory(IntegrationsProperties(), cipher, RestClient.builder().requestFactory(http11))
    private val token = "reader-token-123"

    @AfterEach
    fun stop() = jira.stop()

    private fun connection(capabilities: Set<ConnectionCapability>, allowed: List<String> = listOf("SHOP")): Connection {
        val id = ConnectionId.new()
        return Connection(
            id = id, scope = ConnectionScope.PLATFORM, projectId = null, type = ConnectionType.JIRA, name = "reader",
            siteUrl = jira.baseUrl(), accountEmail = "reader@acme.com", capabilities = capabilities, allowedProjectKeys = allowed,
            secret = cipher.seal(token, Connection.secretAssociatedData(id)),
        )
    }

    private fun permissions(vararg granted: JiraPermission): String =
        JiraPermission.entries.joinToString(prefix = """{"permissions":{""", postfix = "}}") { p ->
            """"${p.name}":{"key":"${p.name}","havePermission":${p in granted}}"""
        }

    private fun stubMyself(status: Int = 200) {
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/myself")).willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                    .withBody("""{"accountId":"5b10a2844c20165700ede21g","displayName":"Back Office Reader","emailAddress":"reader@acme.com"}"""),
            ),
        )
    }

    @Test
    fun `verification maps granted, missing and excess permissions per project`() {
        stubMyself()
        jira.stubFor(
            get(urlPathEqualTo("/rest/api/3/mypermissions")).withQueryParam("projectKey", equalTo("SHOP")).willReturn(
                aResponse().withHeader("Content-Type", "application/json")
                    .withBody(permissions(JiraPermission.BROWSE_PROJECTS, JiraPermission.ADD_COMMENTS, JiraPermission.DELETE_ISSUES, JiraPermission.ADMINISTER_PROJECTS)),
            ),
        )
        val connection = connection(setOf(ConnectionCapability.ISSUE_READ, ConnectionCapability.ISSUE_TRANSITION))

        val probe = factory.probe(connection, listOf("SHOP"))
        val result = ConnectionVerificationPolicy().evaluate(connection.capabilities, probe, Instant.now())

        assertThat(probe.account!!.displayName).isEqualTo("Back Office Reader")
        assertThat(result.status).isEqualTo(VerificationStatus.FAILED)
        val shop = result.projects.single()
        assertThat(shop.missing).containsExactly(JiraPermission.TRANSITION_ISSUES)
        assertThat(shop.excess).containsExactly(JiraPermission.ADD_COMMENTS, JiraPermission.DELETE_ISSUES, JiraPermission.ADMINISTER_PROJECTS)
        assertThat(result.messages.map { it.code }).containsExactlyInAnyOrder(VerificationCode.MISSING_PERMISSIONS, VerificationCode.EXCESS_PERMISSIONS)

        val basic = "Basic " + Base64.getEncoder().encodeToString("reader@acme.com:$token".toByteArray())
        jira.verify(
            getRequestedFor(urlPathEqualTo("/rest/api/3/mypermissions"))
                .withHeader("Authorization", equalTo(basic))
                .withQueryParam("permissions", equalTo(JiraPermission.CHECKED.joinToString(","))),
        )
    }

    @Test
    fun `a rejected token and an unknown project are reported`() {
        stubMyself(401)
        assertThat(factory.probe(connection(setOf(ConnectionCapability.ISSUE_READ)), listOf("SHOP")).failure!!.code)
            .isEqualTo(VerificationCode.AUTHENTICATION_FAILED)

        jira.resetAll()
        stubMyself()
        jira.stubFor(get(urlPathEqualTo("/rest/api/3/mypermissions")).willReturn(aResponse().withStatus(404).withBody("""{"errorMessages":["No project could be found"]}""")))
        val probe = factory.probe(connection(setOf(ConnectionCapability.ISSUE_READ)), listOf("NOPE"))
        assertThat(probe.projects.single().failure!!.code).isEqualTo(VerificationCode.PROJECT_NOT_FOUND)
    }

    @Test
    fun `an undecryptable secret is reported instead of calling Jira`() {
        val foreign = connection(setOf(ConnectionCapability.ISSUE_READ)).copy(id = ConnectionId.new())
        val probe = factory.probe(foreign, listOf("SHOP"))
        assertThat(probe.failure!!.code).isEqualTo(VerificationCode.SECRET_UNAVAILABLE)
        assertThat(probe.failure!!.detail).doesNotContain(token)
        assertThat(jira.allServeEvents).isEmpty()
    }

    @Test
    fun `the client of a connection authenticates with its own token`() {
        jira.stubFor(post(urlPathEqualTo("/rest/api/3/issue/SHOP-1/comment")).willReturn(aResponse().withStatus(201)))
        factory.open(connection(setOf(ConnectionCapability.ISSUE_COMMENT))).comment("SHOP-1", "Done.")

        val basic = "Basic " + Base64.getEncoder().encodeToString("reader@acme.com:$token".toByteArray())
        jira.verify(
            com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/rest/api/3/issue/SHOP-1/comment"))
                .withHeader("Authorization", equalTo(basic)),
        )
    }
}
