package pl.mos.backoffice.projects.adapter.out.jira

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import pl.mos.backoffice.projects.adapter.config.IntegrationsProperties
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.adapter.out.secrets.SecretUnavailableException
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.application.port.out.IntegrationDescriptor
import pl.mos.backoffice.projects.application.port.out.IntegrationProbe
import pl.mos.backoffice.projects.application.port.out.IntegrationTestOutcome
import pl.mos.backoffice.projects.application.port.out.IssueTracker
import pl.mos.backoffice.projects.application.port.out.IssueTrackerFactory
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionType
import pl.mos.backoffice.projects.domain.model.JiraPermission
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.JiraScopes
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.ScopeProbe
import pl.mos.backoffice.projects.domain.model.ProbeFailure
import pl.mos.backoffice.projects.domain.model.ProjectProbe
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.model.VerifiedAccount
import pl.mos.backoffice.shared.IntegrationException

/**
 * The legacy `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` credential as a read-only platform connection with every
 * capability (ADR 0030): kept so existing installations keep working, always chosen last, shown with a migration notice.
 */
@Component
class LegacyJiraConnectionSource(private val props: IntegrationsProperties) : LegacyConnectionSource {
    override fun legacy(): Connection? {
        if (!props.jira.configured) return null
        return Connection(
            id = ConnectionId.LEGACY_JIRA,
            scope = ConnectionScope.PLATFORM,
            projectId = null,
            type = ConnectionType.JIRA,
            name = NAME,
            siteUrl = props.jira.url.trim().trimEnd('/'),
            accountEmail = props.jira.email.trim(),
            capabilities = ConnectionCapability.entries.toSet(),
            secret = null,
            legacy = true,
        )
    }

    companion object {
        const val NAME = "Jira (environment variables)"
    }
}

/**
 * [IssueTrackerFactory] for Jira Cloud (ADR 0030): opens the connection's sealed token with [AesGcmSecretCipher] (the
 * legacy connection uses the environment credential) and builds a [JiraIssueTracker] for that connection only.
 * [probe] answers the verification with `GET /rest/api/3/myself` and `GET /rest/api/3/mypermissions`.
 */
@Component
class JiraIssueTrackerFactory(
    private val props: IntegrationsProperties,
    private val cipher: AesGcmSecretCipher,
    private val restClientBuilder: RestClient.Builder,
) : IssueTrackerFactory {

    private val log = LoggerFactory.getLogger(JiraIssueTrackerFactory::class.java)

    override fun open(connection: Connection): IssueTracker =
        JiraIssueTracker(credentials(connection), props.jira.acceptanceCriteriaField, restClientBuilder)

    /**
     * ADR 0035: `GET <site>/_edge/tenant_info` (public, no authentication) answers `{"cloudId": "..."}`; scoped tokens
     * need it to reach the API gateway.
     */
    override fun discoverCloudId(siteUrl: String): String {
        val site = siteUrl.trim().trimEnd('/')
        val body = try {
            restClientBuilder.clone().baseUrl(site).build().get().uri("/_edge/tenant_info")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).retrieve().body(Map::class.java)
        } catch (e: RestClientResponseException) {
            throw IntegrationException("jira", "$site/_edge/tenant_info answered HTTP ${e.statusCode.value()}")
        } catch (e: ResourceAccessException) {
            throw IntegrationException("jira", "$site cannot be reached: ${e.mostSpecificCause.message ?: e.message}")
        } catch (e: RuntimeException) {
            throw IntegrationException("jira", "$site/_edge/tenant_info could not be read: ${e.message}")
        }
        val cloudId = body?.get("cloudId")?.toString()?.trim()?.lowercase()
        if (cloudId == null || !Connection.CLOUD_ID.matches(cloudId)) {
            throw IntegrationException("jira", "$site/_edge/tenant_info has no cloudId (is it a Jira Cloud site?)")
        }
        return cloudId
    }

    override fun probe(connection: Connection, projectKeys: List<String>): JiraProbe {
        val credentials = try {
            credentials(connection)
        } catch (e: IntegrationException) {
            return JiraProbe(null, ProbeFailure(VerificationCode.SECRET_UNAVAILABLE, e.message ?: "secret unavailable"))
        }
        val scoped = connection.tokenKind == JiraTokenKind.SCOPED
        val client = restClientBuilder.clone()
            .baseUrl(credentials.apiBaseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, credentials.basicAuth())
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
        val account = try {
            val me = client.get().uri("/rest/api/3/myself").retrieve().body(Map::class.java) ?: emptyMap<String, Any?>()
            VerifiedAccount(
                accountId = me["accountId"]?.toString() ?: "",
                displayName = me["displayName"]?.toString() ?: me["emailAddress"]?.toString() ?: connection.accountEmail,
                emailAddress = me["emailAddress"]?.toString(),
            )
        } catch (e: RestClientResponseException) {
            val status = e.statusCode.value()
            log.info("jira verification failed connection={} step=myself status={}", connection.id, status)
            val code = if (status == 401 || status == 403) VerificationCode.AUTHENTICATION_FAILED else VerificationCode.HTTP_ERROR
            val detail = when {
                code != VerificationCode.AUTHENTICATION_FAILED -> "Jira answered HTTP $status to /rest/api/3/myself"
                scoped -> "Jira rejected the scoped token (HTTP $status): wrong e-mail/token pair, expired or revoked token, wrong cloud id, " +
                    "or the token lacks the scope ${JiraScopes.READ_JIRA_USER} / ${JiraScopes.READ_USER} needed to identify the account"
                else -> "Jira rejected the e-mail and token (HTTP $status): wrong pair, expired or revoked token, or the account has no Jira access"
            }
            return JiraProbe(null, ProbeFailure(code, detail))
        } catch (e: ResourceAccessException) {
            return JiraProbe(null, ProbeFailure(VerificationCode.SITE_UNREACHABLE, "${credentials.apiBaseUrl} cannot be reached: ${e.mostSpecificCause.message ?: e.message}"))
        } catch (e: RuntimeException) {
            return JiraProbe(null, ProbeFailure(VerificationCode.HTTP_ERROR, "verification failed: ${e.message}"))
        }
        val projects = projectKeys.map { key -> permissions(client, connection, key, scoped) }
        val scopeProbes = if (scoped) scopeProbes(client, connection, projectKeys.firstOrNull()) else emptyList()
        return JiraProbe(account, null, projects, scopeProbes)
    }

    /**
     * ADR 0035: read calls a scoped token needs per declared capability, on the first project key: a JQL search limited
     * to one issue (ISSUE_READ), the comments of that issue (ISSUE_COMMENT, reading half) and its transitions
     * (ISSUE_TRANSITION). Writes are never tried. `401` / `403` mean the token lacks the endpoint's scopes.
     */
    private fun scopeProbes(client: RestClient, connection: Connection, projectKey: String?): List<ScopeProbe> {
        if (projectKey == null) return emptyList()
        val result = mutableListOf<ScopeProbe>()
        val search = "/rest/api/3/search/jql"
        var sample: String? = null
        val searchProbe = call(ConnectionCapability.ISSUE_READ, "GET $search") {
            val body = client.get().uri("$search?jql={jql}&maxResults=1&fields=summary", "project = \"$projectKey\" ORDER BY created DESC")
                .retrieve().body(Map::class.java)
            sample = ((body?.get("issues") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("key")?.toString()
        }
        if (ConnectionCapability.ISSUE_READ in connection.capabilities || ConnectionCapability.ISSUE_TRANSITION in connection.capabilities) result += searchProbe
        val issue = sample
        if (issue == null) {
            val needsIssue = connection.capabilities.any { it == ConnectionCapability.ISSUE_COMMENT || it == ConnectionCapability.ISSUE_TRANSITION }
            if (needsIssue && searchProbe.ok) {
                result += ScopeProbe(
                    ConnectionCapability.ISSUE_READ, "GET $search", 200, ok = false,
                    detail = "Jira project $projectKey has no issue the token can see, so the comment and transition endpoints were not checked",
                )
            }
            return result
        }
        if (ConnectionCapability.ISSUE_COMMENT in connection.capabilities) {
            result += call(ConnectionCapability.ISSUE_COMMENT, "GET /rest/api/3/issue/$issue/comment") {
                client.get().uri("/rest/api/3/issue/{key}/comment?maxResults=1", issue).retrieve().toBodilessEntity()
            }
        }
        if (ConnectionCapability.ISSUE_TRANSITION in connection.capabilities) {
            result += call(ConnectionCapability.ISSUE_TRANSITION, "GET /rest/api/3/issue/$issue/transitions") {
                client.get().uri("/rest/api/3/issue/{key}/transitions", issue).retrieve().toBodilessEntity()
            }
        }
        return result
    }

    private fun call(capability: ConnectionCapability, endpoint: String, block: () -> Unit): ScopeProbe = try {
        block()
        ScopeProbe(capability, endpoint, 200, ok = true)
    } catch (e: RestClientResponseException) {
        ScopeProbe(capability, endpoint, e.statusCode.value(), ok = false, detail = "Jira answered HTTP ${e.statusCode.value()} to $endpoint")
    } catch (e: RuntimeException) {
        ScopeProbe(capability, endpoint, null, ok = false, detail = "$endpoint failed: ${e.message}")
    }

    private fun permissions(client: RestClient, connection: Connection, projectKey: String, scoped: Boolean): ProjectProbe = try {
        val body = client.get()
            .uri("/rest/api/3/mypermissions?projectKey={key}&permissions={permissions}", projectKey, JiraPermission.CHECKED.joinToString(","))
            .retrieve().body(Map::class.java) ?: emptyMap<String, Any?>()
        val granted = (body["permissions"] as? Map<*, *>).orEmpty().mapNotNullTo(sortedSetOf()) { (name, value) ->
            val have = (value as? Map<*, *>)?.get("havePermission") == true
            JiraPermission.entries.firstOrNull { it.name == name.toString() }?.takeIf { have }
        }
        ProjectProbe(projectKey, granted)
    } catch (e: RestClientResponseException) {
        val status = e.statusCode.value()
        log.info("jira verification failed connection={} step=mypermissions project={} status={}", connection.id, projectKey, status)
        val failure = when {
            status == 404 || status == 400 ->
                ProbeFailure(VerificationCode.PROJECT_NOT_FOUND, "Jira project $projectKey does not exist or this account cannot see it (HTTP $status)")
            scoped && (status == 401 || status == 403) ->
                ProbeFailure(
                    VerificationCode.MISSING_SCOPES,
                    "the scoped token may not read the account's permissions in $projectKey (HTTP $status); add ${JiraScopes.READ_JIRA_WORK} or " +
                        "read:permission:jira to see them, or rely on the endpoint checks",
                )
            else -> ProbeFailure(VerificationCode.HTTP_ERROR, "Jira answered HTTP $status to /rest/api/3/mypermissions for $projectKey")
        }
        ProjectProbe(projectKey, null, failure)
    } catch (e: RuntimeException) {
        ProjectProbe(projectKey, null, ProbeFailure(VerificationCode.HTTP_ERROR, "permissions of $projectKey could not be read: ${e.message}"))
    }

    /** Decrypts the connection's token (or takes the legacy environment credential). */
    fun credentials(connection: Connection): JiraCredentials {
        if (connection.legacy) {
            if (!props.jira.configured) throw IntegrationException("jira", "the legacy JIRA_URL / JIRA_EMAIL / JIRA_API_TOKEN credential is no longer configured")
            return JiraCredentials(props.jira.url, props.jira.email, props.jira.apiToken)
        }
        val sealed = connection.secret ?: throw IntegrationException("jira", "connection \"${connection.name}\" has no stored secret")
        val token = try {
            cipher.open(sealed, Connection.secretAssociatedData(connection.id))
        } catch (e: SecretUnavailableException) {
            throw IntegrationException("jira", "the token of connection \"${connection.name}\" cannot be decrypted: ${e.message}; enter it again (Replace token)")
        }
        return JiraCredentials(connection.siteUrl, connection.accountEmail, token, connection.apiBaseUrl(props.jira.apiGatewayUrl))
    }
}

/**
 * Settings → Integrations card `jira` (ADR 0030): configured when the legacy credential is set or platform connections
 * exist; the test checks the legacy credential (connections are tested on their own page).
 */
@Component
class JiraIntegrationProbe(
    private val legacy: LegacyJiraConnectionSource,
    private val connections: ConnectionRepository,
    private val factory: JiraIssueTrackerFactory,
    private val props: IntegrationsProperties,
    private val restClientBuilder: RestClient.Builder,
) : IntegrationProbe {

    override val name = "jira"

    override fun describe(): IntegrationDescriptor {
        val legacyConnection = legacy.legacy()
        val platform = runCatching { connections.findPlatform().size }.getOrDefault(0)
        return IntegrationDescriptor(
            name, legacyConnection != null || platform > 0, legacyConnection?.siteUrl,
            mapOf("legacy" to (legacyConnection != null).toString(), "platformConnections" to platform.toString()),
        )
    }

    override fun test(): IntegrationTestOutcome {
        val connection = legacy.legacy()
            ?: return IntegrationTestOutcome(false, "JIRA_URL / JIRA_EMAIL / JIRA_API_TOKEN not configured; Jira credentials are managed as connections (Administration → Connections)")
        val started = System.nanoTime()
        return try {
            val me = JiraIssueTracker(factory.credentials(connection), props.jira.acceptanceCriteriaField, restClientBuilder).myself()
            IntegrationTestOutcome(true, "Authenticated as ${me?.get("displayName") ?: me?.get("emailAddress") ?: "unknown"}", (System.nanoTime() - started) / 1_000_000)
        } catch (e: IntegrationException) {
            IntegrationTestOutcome(false, e.message ?: "failed", (System.nanoTime() - started) / 1_000_000)
        }
    }
}
