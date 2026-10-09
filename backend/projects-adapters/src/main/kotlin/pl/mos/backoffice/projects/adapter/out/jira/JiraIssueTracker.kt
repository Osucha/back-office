package pl.mos.backoffice.projects.adapter.out.jira

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import pl.mos.backoffice.projects.application.port.out.IssueTracker
import pl.mos.backoffice.projects.application.port.out.IssueTrackerUnavailableException
import pl.mos.backoffice.projects.application.port.out.TransitionUnavailableException
import pl.mos.backoffice.projects.domain.model.IssueSnapshot
import pl.mos.backoffice.projects.domain.model.IssueHeadline
import pl.mos.backoffice.projects.domain.model.StatusChange
import pl.mos.backoffice.projects.domain.model.WatchedIssue
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import pl.mos.backoffice.shared.IntegrationException
import java.util.Base64

/**
 * Credentials of one Jira Cloud connection (the token is held only for the lifetime of the client). [apiBaseUrl] is
 * where REST calls go: the site for classic tokens, `https://api.atlassian.com/ex/jira/<cloudId>` for scoped tokens
 * (ADR 0035); issue links always use [siteUrl]. Both kinds use HTTP Basic with the account e-mail and the token.
 */
class JiraCredentials(val siteUrl: String, val email: String, private val apiToken: String, apiBaseUrl: String? = null) {
    val apiBaseUrl: String = (apiBaseUrl ?: siteUrl).trimEnd('/')
    fun basicAuth(): String = "Basic " + Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray())
    override fun toString(): String = "JiraCredentials(siteUrl=$siteUrl, apiBaseUrl=$apiBaseUrl, email=$email, apiToken=***)"
}

/**
 * [IssueTracker] on Jira Cloud REST v3 with basic auth (e-mail + classic API token) for ONE connection (ADR 0030;
 * created by [JiraIssueTrackerFactory]). Descriptions in Atlassian Document Format are flattened to markdown by
 * [AdfFlattener]. What a step may do is limited by the core's `ScopedIssueTracker` around this client.
 */
class JiraIssueTracker(
    private val credentials: JiraCredentials,
    private val acceptanceCriteriaField: String,
    restClientBuilder: RestClient.Builder,
) : IssueTracker {

    private val log = LoggerFactory.getLogger(JiraIssueTracker::class.java)

    /** Issue links (`<site>/browse/<key>`) and log lines use the site, also for scoped tokens. */
    private val baseUrl = credentials.siteUrl.trimEnd('/')

    private val client: RestClient = restClientBuilder.clone()
        .baseUrl(credentials.apiBaseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, credentials.basicAuth())
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun isConfigured(): Boolean = true

    override fun fetch(issueKey: String): IssueSnapshot = call("fetch issue $issueKey") { c ->
        val body = c.get().uri("/rest/api/3/issue/{key}?fields={fields}", issueKey, fields()).retrieve().body(Map::class.java)
            ?: throw IntegrationException("jira", "empty response for $issueKey")
        toSnapshot(body)
    }

    override fun search(jql: String, limit: Int): List<IssueSnapshot> = call("search issues") { c ->
        val body = c.get().uri("/rest/api/3/search/jql?jql={jql}&maxResults={max}&fields={fields}", jql, limit, fields())
            .retrieve().body(Map::class.java) ?: emptyMap<String, Any?>()
        (body["issues"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::toSnapshot) } ?: emptyList()
    }

    /**
     * Intake watcher query (ADR 0033): `GET /rest/api/3/search/jql` with only `status,updated,created` and the changelog,
     * following `nextPageToken` up to [limit] issues. 429 / 5xx become [IssueTrackerUnavailableException] with the
     * server's `Retry-After`.
     */
    override fun watch(jql: String, limit: Int): List<WatchedIssue> {
        val result = ArrayList<WatchedIssue>()
        var token: String? = null
        do {
            val page = try {
                val pageSize = (limit - result.size).coerceIn(1, WATCH_PAGE_SIZE)
                val uri = if (token == null) {
                    client.get().uri("/rest/api/3/search/jql?jql={jql}&maxResults={max}&fields={fields}&expand=changelog", jql, pageSize, WATCH_FIELDS)
                } else {
                    client.get().uri("/rest/api/3/search/jql?jql={jql}&maxResults={max}&fields={fields}&expand=changelog&nextPageToken={token}", jql, pageSize, WATCH_FIELDS, token)
                }
                uri.retrieve().body(Map::class.java) ?: emptyMap<String, Any?>()
            } catch (e: RestClientResponseException) {
                val status = e.statusCode.value()
                log.warn("jira watch failed status={} site={}", status, baseUrl)
                if (status == HTTP_TOO_MANY_REQUESTS || e.statusCode.is5xxServerError) {
                    throw IssueTrackerUnavailableException(status, retryAfter(e.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)), "search failed: ${e.responseBodyAsString.take(ERROR_BODY_CHARS)}")
                }
                throw IntegrationException("jira", "search failed: HTTP $status ${e.responseBodyAsString.take(ERROR_BODY_CHARS)}", e)
            } catch (e: RuntimeException) {
                if (e is IssueTrackerUnavailableException || e is IntegrationException) throw e
                throw IntegrationException("jira", "search failed: ${e.message}", e, retryable = true)
            }
            (page["issues"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::toWatched) }?.let(result::addAll)
            token = page["nextPageToken"]?.toString()?.takeIf { it.isNotBlank() && page["isLast"] != true }
        } while (token != null && result.size < limit)
        return result.take(limit)
    }


    /**
     * Personal intake query (ADR 0038): one page of `GET /rest/api/3/search/jql` with only key, summary, type, status
     * and priority. 429 / 5xx become [IssueTrackerUnavailableException] with the server's `Retry-After`.
     */
    override fun headlines(jql: String, limit: Int): List<IssueHeadline> {
        val page = try {
            client.get().uri("/rest/api/3/search/jql?jql={jql}&maxResults={max}&fields={fields}", jql, limit.coerceIn(1, HEADLINE_PAGE_SIZE), HEADLINE_FIELDS)
                .retrieve().body(Map::class.java) ?: emptyMap<String, Any?>()
        } catch (e: RestClientResponseException) {
            val status = e.statusCode.value()
            log.warn("jira headlines failed status={} site={}", status, baseUrl)
            if (status == HTTP_TOO_MANY_REQUESTS || e.statusCode.is5xxServerError) {
                throw IssueTrackerUnavailableException(status, retryAfter(e.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)), "search failed: ${e.responseBodyAsString.take(ERROR_BODY_CHARS)}")
            }
            throw IntegrationException("jira", "search failed: HTTP $status ${e.responseBodyAsString.take(ERROR_BODY_CHARS)}", e)
        } catch (e: RuntimeException) {
            if (e is IssueTrackerUnavailableException || e is IntegrationException) throw e
            throw IntegrationException("jira", "search failed: ${e.message}", e, retryable = true)
        }
        return (page["issues"] as? List<*>)?.mapNotNull { issue ->
            val map = issue as? Map<*, *> ?: return@mapNotNull null
            val key = map["key"]?.toString() ?: return@mapNotNull null
            val fields = map["fields"] as? Map<*, *> ?: emptyMap<String, Any?>()
            IssueHeadline(
                key = key,
                summary = fields["summary"]?.toString(),
                type = (fields["issuetype"] as? Map<*, *>)?.get("name")?.toString(),
                status = (fields["status"] as? Map<*, *>)?.get("name")?.toString(),
                priority = (fields["priority"] as? Map<*, *>)?.get("name")?.toString(),
            )
        }?.take(limit) ?: emptyList()
    }
    override fun comment(issueKey: String, body: String) {
        call("comment on $issueKey") { c ->
            c.post().uri("/rest/api/3/issue/{key}/comment", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("body" to AdfFlattener.toAdf(body)))
                .retrieve().toBodilessEntity()
        }
    }

    override fun transition(issueKey: String, toStatus: String) {
        call("transition $issueKey to $toStatus") { c ->
            val transitions = c.get().uri("/rest/api/3/issue/{key}/transitions", issueKey).retrieve().body(Map::class.java)
            val match = (transitions?.get("transitions") as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { t ->
                    val target = (t["to"] as? Map<*, *>)?.get("name")?.toString()
                    target.equals(toStatus, ignoreCase = true) || t["name"]?.toString().equals(toStatus, ignoreCase = true)
                }
                ?: throw TransitionUnavailableException(issueKey, toStatus)
            c.post().uri("/rest/api/3/issue/{key}/transitions", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("transition" to mapOf("id" to match["id"].toString())))
                .retrieve().toBodilessEntity()
        }
    }

    /** `GET /rest/api/3/myself` (used by the settings test of the legacy credential). */
    fun myself(): Map<*, *>? = call("get myself") { it.get().uri("/rest/api/3/myself").retrieve().body(Map::class.java) }

    private fun fields(): String {
        val base = "summary,description,issuetype,priority,status,labels,components"
        val extra = acceptanceCriteriaField.takeIf { it.isNotBlank() }
        return if (extra != null) "$base,$extra" else base
    }

    /** Key, status, dates and the status changes of the changelog; nothing else of the issue is read. */
    private fun toWatched(issue: Map<*, *>): WatchedIssue? {
        val key = issue["key"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val fields = issue["fields"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val histories = ((issue["changelog"] as? Map<*, *>)?.get("histories") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val changes = histories.flatMap { history ->
            val id = history["id"]?.toString() ?: return@flatMap emptyList()
            val at = parseTime(history["created"])
            (history["items"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                .filter { it["field"]?.toString().equals("status", ignoreCase = true) }
                .mapNotNull { item -> item["toString"]?.toString()?.let { StatusChange(id, at, it) } }
        }
        return WatchedIssue(
            key = key,
            status = (fields["status"] as? Map<*, *>)?.get("name")?.toString(),
            updated = parseTime(fields["updated"]),
            created = parseTime(fields["created"]),
            statusChanges = changes,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toSnapshot(issue: Map<*, *>): IssueSnapshot {
        val fields = issue["fields"] as? Map<String, Any?> ?: emptyMap()
        val key = issue["key"]?.toString() ?: ""
        val acceptanceField = acceptanceCriteriaField.takeIf { it.isNotBlank() }
        return IssueSnapshot(
            key = key,
            summary = fields["summary"]?.toString() ?: "",
            description = AdfFlattener.toMarkdown(fields["description"]),
            type = (fields["issuetype"] as? Map<*, *>)?.get("name")?.toString(),
            priority = (fields["priority"] as? Map<*, *>)?.get("name")?.toString(),
            status = (fields["status"] as? Map<*, *>)?.get("name")?.toString(),
            labels = (fields["labels"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            components = (fields["components"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("name")?.toString() } ?: emptyList(),
            acceptanceCriteria = acceptanceField?.let { fields[it] }?.let { AdfFlattener.toMarkdown(it).takeIf { t -> t.isNotBlank() } },
            url = baseUrl.takeIf { it.isNotBlank() }?.let { "$it/browse/$key" },
        )
    }

    companion object {
        const val WATCH_FIELDS = "status,updated,created"
        const val HEADLINE_FIELDS = "summary,issuetype,status,priority"
        const val HEADLINE_PAGE_SIZE = 100
        const val WATCH_PAGE_SIZE = 50
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val ERROR_BODY_CHARS = 300

        /** Jira's `2026-10-08T12:00:00.000+0200` (offset without colon) and ISO-8601. */
        private val JIRA_TIME: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendPattern(".SSS").optionalEnd()
            .appendPattern("[XXX][XX][X]")
            .toFormatter()

        fun parseTime(value: Any?): Instant? {
            val text = value?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { OffsetDateTime.parse(text, JIRA_TIME).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(text) }.getOrNull()
        }

        /** `Retry-After` in seconds (HTTP-dates are ignored: the back-off applies). */
        fun retryAfter(header: String?): Duration? = header?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofSeconds)
    }

    private fun <T> call(action: String, block: (RestClient) -> T): T {
        return try {
            block(client)
        } catch (e: RestClientResponseException) {
            log.warn("jira call failed action={} status={} site={}", action, e.statusCode.value(), baseUrl)
            throw IntegrationException("jira", "$action failed: HTTP ${e.statusCode.value()} ${e.responseBodyAsString.take(300)}", e, retryable = e.statusCode.is5xxServerError || e.statusCode.value() == 429)
        } catch (e: IntegrationException) {
            throw e
        } catch (e: TransitionUnavailableException) {
            throw e
        } catch (e: RuntimeException) {
            throw IntegrationException("jira", "$action failed: ${e.message}", e)
        }
    }
}
