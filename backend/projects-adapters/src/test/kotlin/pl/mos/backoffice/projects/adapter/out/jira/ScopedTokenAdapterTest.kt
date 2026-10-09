package pl.mos.backoffice.projects.adapter.out.jira

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerificationStatus
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import pl.mos.backoffice.shared.IntegrationException
import java.time.Instant
import java.util.Base64

/**
 * ADR 0035, scoped Atlassian API tokens (WireMock): the cloud id comes from `<site>/_edge/tenant_info`; REST calls go to
 * `<gateway>/ex/jira/<cloudId>/rest/api/3/...` with Basic e-mail:token; issue links stay on the site; the verification
 * calls the endpoints each capability needs and maps 401/403 to missing scopes.
 */
class ScopedTokenAdapterTest {

    private val site = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
    private val gateway = WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
    private val cipher = AesGcmSecretCipher(SecretsProperties(key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), keyId = "k1"))
    private val http11 = org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build())
    private val factory = JiraIssueTrackerFactory(
        IntegrationsProperties(jira = IntegrationsProperties.Jira(apiGatewayUrl = gateway.baseUrl())), cipher, RestClient.builder().requestFactory(http11),
    )
    private val token = "ATATT-scoped-token"
    private val cloudId = "11111111-2222-3333-4444-555555555555"
    private val prefix = "/ex/jira/$cloudId"
    private val basic = "Basic " + Base64.getEncoder().encodeToString("anna@acme.com:$token".toByteArray())

    @AfterEach
    fun stop() {
        site.stop()
        gateway.stop()
    }

    private fun scoped(capabilities: Set<ConnectionCapability>, scopes: List<String>): Connection {
        val id = ConnectionId.new()
        return Connection(
            id = id, scope = ConnectionScope.USER, projectId = null, type = ConnectionType.JIRA, name = "Anna's Jira",
            siteUrl = site.baseUrl(), accountEmail = "anna@acme.com", capabilities = capabilities, allowedProjectKeys = listOf("SHOP"),
            secret = cipher.seal(token, Connection.secretAssociatedData(id)), ownerUserId = java.util.UUID.randomUUID(), ownerName = "Anna",
            tokenKind = JiraTokenKind.SCOPED, cloudId = cloudId, tokenScopes = scopes,
        )
    }

    private fun json(body: String, status: Int = 200) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    @Test
    fun `the cloud id is discovered from the public tenant_info of the site`() {
        site.stubFor(get(urlPathEqualTo("/_edge/tenant_info")).willReturn(json("""{"cloudId":"${cloudId.uppercase()}"}""")))
        assertThat(factory.discoverCloudId(site.baseUrl() + "/")).isEqualTo(cloudId)
        site.verify(getRequestedFor(urlPathEqualTo("/_edge/tenant_info")).withoutHeader("Authorization"))

        site.resetAll()
        site.stubFor(get(urlPathEqualTo("/_edge/tenant_info")).willReturn(json("""{"nothing":true}""")))
        assertThatThrownBy { factory.discoverCloudId(site.baseUrl()) }.isInstanceOf(IntegrationException::class.java).hasMessageContaining("no cloudId")
        site.resetAll()
        site.stubFor(get(urlPathEqualTo("/_edge/tenant_info")).willReturn(aResponse().withStatus(404)))
        assertThatThrownBy { factory.discoverCloudId(site.baseUrl()) }.isInstanceOf(IntegrationException::class.java).hasMessageContaining("HTTP 404")
    }

    @Test
    fun `a scoped client calls the gateway with Basic auth, issue links stay on the site`() {
        gateway.stubFor(
            get(urlPathEqualTo("$prefix/rest/api/3/issue/SHOP-7")).willReturn(json("""{"key":"SHOP-7","fields":{"summary":"Cart"}}""")),
        )
        gateway.stubFor(post(urlPathEqualTo("$prefix/rest/api/3/issue/SHOP-7/comment")).willReturn(aResponse().withStatus(201)))
        val client = factory.open(scoped(setOf(ConnectionCapability.ISSUE_READ, ConnectionCapability.ISSUE_COMMENT), listOf("read:jira-work", "write:comment:jira")))

        val issue = client.fetch("SHOP-7")
        client.comment("SHOP-7", "Done.")

        assertThat(issue.url).isEqualTo("${site.baseUrl()}/browse/SHOP-7")
        gateway.verify(getRequestedFor(urlPathEqualTo("$prefix/rest/api/3/issue/SHOP-7")).withHeader("Authorization", equalTo(basic)))
        gateway.verify(postRequestedFor(urlPathEqualTo("$prefix/rest/api/3/issue/SHOP-7/comment")).withHeader("Authorization", equalTo(basic)))
        assertThat(site.allServeEvents).isEmpty()
    }

    @Test
    fun `the verification of a scoped token calls the capability endpoints and maps 403 to missing scopes`() {
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/myself")).willReturn(json("""{"accountId":"a1","displayName":"Anna Nowak"}""")))
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/mypermissions")).willReturn(aResponse().withStatus(401)))
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/search/jql")).willReturn(json("""{"issues":[{"key":"SHOP-1"}]}""")))
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/issue/SHOP-1/transitions")).willReturn(aResponse().withStatus(403)))
        val connection = scoped(
            setOf(ConnectionCapability.ISSUE_READ, ConnectionCapability.ISSUE_TRANSITION),
            listOf("read:jira-work", "read:jira-user", "write:jira-work"),
        )

        val probe = factory.probe(connection, listOf("SHOP"))
        val result = ConnectionVerificationPolicy().evaluate(connection.capabilities, probe, Instant.now(), connection.tokenKind, connection.tokenScopes)

        assertThat(probe.account!!.displayName).isEqualTo("Anna Nowak")
        assertThat(probe.projects.single().failure!!.code).isEqualTo(VerificationCode.MISSING_SCOPES)
        assertThat(probe.scopeProbes.map { it.endpoint to it.ok }).containsExactly(
            "GET /rest/api/3/search/jql" to true,
            "GET /rest/api/3/issue/SHOP-1/transitions" to false,
        )
        assertThat(result.status).isEqualTo(VerificationStatus.FAILED)
        assertThat(result.messages.filter { it.code == VerificationCode.MISSING_SCOPES }.map { it.text })
            .anyMatch { it.contains("transitions") && it.contains("ISSUE_TRANSITION") }
        assertThat(result.messages.map { it.code }).contains(VerificationCode.BROAD_SCOPE)
        gateway.verify(getRequestedFor(urlPathEqualTo("$prefix/rest/api/3/search/jql")).withHeader("Authorization", equalTo(basic)).withQueryParam("maxResults", equalTo("1")))
        assertThat(gateway.allServeEvents).noneMatch { it.request.method.value() != "GET" } // a verification never writes
    }

    @Test
    fun `a scoped token with exactly the needed scopes verifies OK even though the account can do more`() {
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/myself")).willReturn(json("""{"accountId":"a1","displayName":"Anna Nowak"}""")))
        val all = JiraPermission.entries.joinToString(prefix = """{"permissions":{""", postfix = "}}") { """"${it.name}":{"havePermission":true}""" }
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/mypermissions")).willReturn(json(all)))
        gateway.stubFor(get(urlPathEqualTo("$prefix/rest/api/3/search/jql")).willReturn(json("""{"issues":[]}""")))
        val connection = scoped(setOf(ConnectionCapability.ISSUE_READ), listOf("read:jira-work", "read:jira-user"))

        val result = ConnectionVerificationPolicy().evaluate(connection.capabilities, factory.probe(connection, listOf("SHOP")), Instant.now(), connection.tokenKind, connection.tokenScopes)

        assertThat(result.status).isEqualTo(VerificationStatus.OK)
        assertThat(result.excess).contains(JiraPermission.DELETE_ISSUES) // reported as INFO: the scopes bound the token
    }
}
