package pl.mos.backoffice.projects.domain.model

/**
 * Atlassian scopes a scoped API token needs per [ConnectionCapability] (ADR 0035). A capability is covered when the
 * token's declared scopes contain one complete option: the classic set or the granular set.
 *
 * Source: Atlassian "Jira scopes for OAuth 2.0 (3LO) and Forge apps" and the per-endpoint "Scopes" boxes of the Jira
 * Cloud REST API v3 reference. The granular sets are the scopes those boxes list for the endpoints Back Office calls
 * (search/jql, issue, comment, transitions); Atlassian changes them occasionally, so the sets are kept in this one
 * place and verified against the live site by the verification calls ([ScopeProbe]).
 */
object JiraScopes {

    /** One way to cover a capability. [required] must all be declared; [recommended] are what the UI suggests ticking. */
    data class Option(val kind: Kind, val required: Set<String>, val recommended: Set<String> = required)

    enum class Kind { CLASSIC, GRANULAR }

    // classic scopes
    const val READ_JIRA_WORK = "read:jira-work"
    const val WRITE_JIRA_WORK = "write:jira-work"
    const val READ_JIRA_USER = "read:jira-user"

    // granular scopes
    const val READ_ISSUE = "read:issue:jira"
    const val READ_ISSUE_DETAILS = "read:issue-details:jira"
    const val READ_JQL = "read:jql:jira"
    const val READ_COMMENT = "read:comment:jira"
    const val WRITE_COMMENT = "write:comment:jira"
    const val WRITE_ISSUE = "write:issue:jira"
    const val READ_TRANSITION = "read:issue.transition:jira"
    const val READ_STATUS = "read:status:jira"
    const val READ_USER = "read:user:jira"
    const val READ_GROUP = "read:group:jira"
    const val READ_APPLICATION_ROLE = "read:application-role:jira"
    const val READ_AVATAR = "read:avatar:jira"
    const val READ_PERMISSION = "read:permission:jira"

    /**
     * `GET /rest/api/3/myself` (every verification): classic `read:jira-user`, or the granular scopes the endpoint's
     * reference lists (user, group, application role, avatar; HTTP 401 without all of them), plus [READ_PERMISSION]
     * for `GET /rest/api/3/mypermissions`, which checks the account's project permissions.
     */
    val VERIFICATION: List<Option> = listOf(
        Option(Kind.CLASSIC, setOf(READ_JIRA_USER)),
        Option(Kind.GRANULAR, setOf(READ_USER, READ_GROUP, READ_APPLICATION_ROLE, READ_AVATAR, READ_PERMISSION)),
    )

    private val READ_GRANULAR_RECOMMENDED = setOf(
        READ_ISSUE, READ_ISSUE_DETAILS, READ_JQL, "read:issue-meta:jira", "read:field-configuration:jira", READ_STATUS,
        "read:issue.changelog:jira", "read:field:jira", READ_USER, "read:avatar:jira",
    )

    /** The options per capability. */
    fun options(capability: ConnectionCapability): List<Option> = when (capability) {
        ConnectionCapability.ISSUE_READ -> listOf(
            Option(Kind.CLASSIC, setOf(READ_JIRA_WORK)),
            Option(Kind.GRANULAR, setOf(READ_ISSUE, READ_ISSUE_DETAILS, READ_JQL), READ_GRANULAR_RECOMMENDED),
        )
        ConnectionCapability.ISSUE_COMMENT -> listOf(
            Option(Kind.CLASSIC, setOf(READ_JIRA_WORK, WRITE_JIRA_WORK)),
            Option(
                Kind.GRANULAR, setOf(WRITE_COMMENT, READ_COMMENT),
                setOf(WRITE_COMMENT, READ_COMMENT, "read:comment.property:jira", "read:project:jira", "read:project-role:jira", "read:group:jira", READ_USER, "read:avatar:jira"),
            ),
        )
        ConnectionCapability.ISSUE_TRANSITION -> listOf(
            Option(Kind.CLASSIC, setOf(READ_JIRA_WORK, WRITE_JIRA_WORK)),
            Option(
                Kind.GRANULAR, setOf(WRITE_ISSUE, READ_TRANSITION, READ_STATUS),
                setOf(WRITE_ISSUE, "write:issue.property:jira", READ_TRANSITION, READ_STATUS, "read:field-configuration:jira"),
            ),
        )
    }

    /** Scopes that change or administer Jira. */
    fun isWrite(scope: String): Boolean = scope.startsWith("write:") || scope.startsWith("delete:") || scope.startsWith("manage:")

    /** Capabilities of [capabilities] that [declared] does not cover with any option. */
    fun missing(capabilities: Set<ConnectionCapability>, declared: Collection<String>): Set<ConnectionCapability> {
        val have = declared.toSet()
        return capabilities.filterTo(sortedSetOf()) { c -> options(c).none { have.containsAll(it.required) } }
    }

    /** `myself` is covered (the verification can identify the account). */
    fun coversVerification(declared: Collection<String>): Boolean = VERIFICATION.any { declared.toSet().containsAll(it.required) }

    /**
     * Declared write scopes that no option of [capabilities] needs (ADR 0035 "excess check"): a reader token with
     * `write:comment:jira`, a commenter with `write:issue:jira`, anything with `delete:` or `manage:`.
     */
    fun excessWrites(capabilities: Set<ConnectionCapability>, declared: Collection<String>): List<String> {
        val needed = capabilities.flatMap { c -> options(c).flatMap { it.recommended } }.toSet()
        return declared.filter { isWrite(it) && it !in needed }.distinct().sorted()
    }

    /** The minimal granular set for [capabilities] plus `myself`, for the UI and the manual. */
    fun recommendedGranular(capabilities: Set<ConnectionCapability>): List<String> =
        (capabilities.flatMap { c -> options(c).filter { it.kind == Kind.GRANULAR }.flatMap { it.recommended } } + READ_USER).distinct().sorted()
}
