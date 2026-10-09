package pl.mos.backoffice.projects.domain.model

import pl.mos.backoffice.shared.runner.GitAccess

/** Jira issue as exposed to templates (`{{issue.summary}}`), expressions (`issue.status`) and the graph state. */
data class IssueSnapshot(
    val key: String,
    val summary: String,
    val description: String = "",
    val type: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val labels: List<String> = emptyList(),
    val components: List<String> = emptyList(),
    val acceptanceCriteria: String? = null,
    val url: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "key" to key,
        "summary" to summary,
        "description" to description,
        "type" to type,
        "priority" to priority,
        "status" to status,
        "labels" to labels,
        "components" to components,
        "acceptanceCriteria" to acceptanceCriteria,
        "url" to url,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): IssueSnapshot = IssueSnapshot(
            key = map["key"]?.toString() ?: "",
            summary = map["summary"]?.toString() ?: "",
            description = map["description"]?.toString() ?: "",
            type = map["type"]?.toString(),
            priority = map["priority"]?.toString(),
            status = map["status"]?.toString(),
            labels = (map["labels"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            components = (map["components"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            acceptanceCriteria = map["acceptanceCriteria"]?.toString(),
            url = map["url"]?.toString(),
        )
    }
}

/**
 * The merge request an implementation task pushed (URL parsed from the push output, may be missing on
 * hosts without push options). `branch` is the key CI signals are matched on when no run id is known.
 */
data class MergeRequestSnapshot(
    val url: String? = null,
    val branch: String,
    val targetBranch: String,
) {
    fun toMap(): Map<String, Any?> = mapOf("url" to url, "branch" to branch, "targetBranch" to targetBranch)

    companion object {
        fun fromMap(map: Map<String, Any?>): MergeRequestSnapshot = MergeRequestSnapshot(
            url = map["url"]?.toString()?.takeIf { it.isNotBlank() },
            branch = map["branch"]?.toString() ?: map["sourceBranch"]?.toString() ?: "",
            targetBranch = map["targetBranch"]?.toString() ?: "",
        )
    }
}

enum class CiProvider { GITLAB, GITHUB, OTHER }

data class CiMergeRequest(
    val iid: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sourceBranch: String? = null,
    val targetBranch: String? = null,
    val url: String? = null,
    val author: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "iid" to iid, "title" to title, "description" to description, "sourceBranch" to sourceBranch,
        "targetBranch" to targetBranch, "url" to url, "author" to author,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): CiMergeRequest = CiMergeRequest(
            iid = map["iid"]?.toString(), title = map["title"]?.toString(), description = map["description"]?.toString(),
            sourceBranch = map["sourceBranch"]?.toString(), targetBranch = map["targetBranch"]?.toString(),
            url = map["url"]?.toString(), author = map["author"]?.toString(),
        )
    }
}

/** What the CI job tells us about itself (`backoffice-ci.sh` sends it with every call). */
data class CiContext(
    val provider: CiProvider = CiProvider.OTHER,
    val projectPath: String? = null,
    val pipelineId: String? = null,
    val jobId: String? = null,
    val jobUrl: String? = null,
    val ref: String? = null,
    val commitSha: String? = null,
    val defaultBranch: String? = null,
    val mergeRequest: CiMergeRequest? = null,
    val actor: String? = null,
    /** URL of the repository the CI job runs in (backoffice-ci.sh 2.1+); matched to a project repository (ADR 0034). */
    val repositoryUrl: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "provider" to provider.name, "projectPath" to projectPath, "pipelineId" to pipelineId, "jobId" to jobId, "jobUrl" to jobUrl,
        "ref" to ref, "commitSha" to commitSha, "defaultBranch" to defaultBranch, "mergeRequest" to mergeRequest?.toMap(), "actor" to actor,
        "repositoryUrl" to repositoryUrl,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): CiContext = CiContext(
            provider = map["provider"]?.toString()?.let { p -> CiProvider.entries.firstOrNull { it.name == p } } ?: CiProvider.OTHER,
            projectPath = map["projectPath"]?.toString(), pipelineId = map["pipelineId"]?.toString(), jobId = map["jobId"]?.toString(),
            jobUrl = map["jobUrl"]?.toString(), ref = map["ref"]?.toString(), commitSha = map["commitSha"]?.toString(),
            defaultBranch = map["defaultBranch"]?.toString(),
            mergeRequest = (map["mergeRequest"] as? Map<String, Any?>)?.let(CiMergeRequest::fromMap),
            actor = map["actor"]?.toString(),
            repositoryUrl = map["repositoryUrl"]?.toString(),
        )
    }
}

/** Files / insertions / deletions of a change (`git diff --shortstat`). */
data class DiffStat(val files: Int, val insertions: Int, val deletions: Int)

/**
 * What a runner step did in one repository (ADR 0034): built from `RunnerResult.repositories`, or from the legacy
 * single-repository fields of older runners. [pushed] is true only when the job branch reached the repository.
 */
data class RepositoryResult(
    val key: String,
    val url: String? = null,
    val purpose: RepositoryPurpose? = null,
    val access: GitAccess = GitAccess.READ,
    val primary: Boolean = false,
    val branch: String? = null,
    val commitSha: String? = null,
    val diffStat: DiffStat? = null,
    val mergeRequestUrl: String? = null,
    val pushed: Boolean = false,
    val error: String? = null,
) {
    /** The routing / template view (`repositoryResults.<key>`). */
    fun toStateMap(): Map<String, Any?> = mapOf(
        "key" to key, "url" to url, "access" to access.name, "primary" to primary, "branch" to branch, "commitSha" to commitSha,
        "mergeRequestUrl" to mergeRequestUrl, "pushed" to pushed, "error" to error,
    )
}
