package pl.mos.backoffice.governance.domain.service

import pl.mos.backoffice.governance.domain.model.AttestationSubject
import pl.mos.backoffice.governance.domain.model.InToto
import pl.mos.backoffice.governance.domain.model.JobEvidence
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.provenance.ProvenanceGateDecision
import pl.mos.backoffice.shared.provenance.ProvenanceNode
import pl.mos.backoffice.shared.provenance.RunProvenance
import pl.mos.backoffice.shared.security.Actor
import java.time.Duration
import java.time.Instant

/** Usage booked in the ledger for one job or run. */
data class LedgerUsage(val usage: TokenUsage, val cost: Money, val models: List<String>) {
    companion object {
        val NONE = LedgerUsage(TokenUsage.ZERO, Money.ZERO, emptyList())
    }
}

/** A statement ready to be encoded: subjects plus the predicate (plain maps, lists, strings and numbers). */
data class StatementDraft(val subjects: List<AttestationSubject>, val predicate: Map<String, Any?>) {
    fun toStatement(): Map<String, Any?> = linkedMapOf(
        "_type" to InToto.STATEMENT_TYPE,
        "subject" to subjects.map { it.toMap() },
        "predicateType" to InToto.PREDICATE_AGENT_RUN,
        "predicate" to predicate,
    )
}

/**
 * Builds the in-toto statements of docs/architecture/11-governance.md ("Provenance attestations"):
 * - agent job: subjects = one pushed commit per repository (`git+<repo>@<sha>`, `sha256` of the commit id plus
 *   `gitCommit`; ADR 0034 - from the node's per-repository results, else the job's single commit) and the diff
 *   (`sha256` of the diff); predicate = run, workflow, node, template, model and effort, prompt
 *   digests, sandbox policy, usage and cost, verification, gate decisions on the path so far, timestamps;
 * - run: subjects = every job subject, or a manifest digest of the run's artefacts when no job produced
 *   code; predicate = the path of nodes, gate decisions and totals.
 */
object ProvenanceStatements {
    const val KIND_AGENT_JOB = "AGENT_JOB"
    const val KIND_RUN = "RUN"

    fun jobSubjects(run: RunProvenance, node: ProvenanceNode, evidence: JobEvidence?): List<AttestationSubject> {
        val subjects = mutableListOf<AttestationSubject>()
        val pushed = node.repositories.filter { it.pushed && !it.commitSha.isNullOrBlank() }
        if (pushed.isNotEmpty()) {
            // ADR 0034: one subject per repository the job pushed to
            pushed.forEach { r ->
                val commit = r.commitSha!!
                val repo = r.url ?: run.repositories.firstOrNull { it.key == r.key }?.url ?: run.repositoryUrl ?: "unknown"
                subjects += AttestationSubject("git+$repo@$commit", linkedMapOf("sha256" to Digests.sha256Hex(commit), "gitCommit" to commit))
            }
        } else {
            val commit = evidence?.commitSha?.takeIf { it.isNotBlank() }
            if (commit != null) {
                val repo = node.repositories.firstOrNull()?.url ?: run.repositoryUrl ?: "unknown"
                subjects += AttestationSubject("git+$repo@$commit", linkedMapOf("sha256" to Digests.sha256Hex(commit), "gitCommit" to commit))
            }
        }
        val diffDigest = evidence?.diffSha256 ?: diffArtifact(run, node)?.sha256
        if (diffDigest != null) {
            subjects += AttestationSubject("diff:${run.runId}/${node.nodeId}/${node.attempt}", mapOf("sha256" to diffDigest))
        }
        return subjects
    }

    fun jobPredicate(
        run: RunProvenance,
        node: ProvenanceNode,
        evidence: JobEvidence?,
        ledger: LedgerUsage,
        lineage: List<String>,
        now: Instant,
        templateVersion: Int? = null,
    ): Map<String, Any?> {
        val usage = if (ledger.usage == TokenUsage.ZERO) node.usage else ledger.usage
        val cost = if (ledger.cost.isZero()) node.cost else ledger.cost
        val decisions = run.decisions.filter { d -> node.finishedAt?.let { !d.decidedAt.isAfter(it) } ?: true }
        return linkedMapOf(
            "kind" to KIND_AGENT_JOB,
            "runId" to run.runId.toString(),
            "project" to project(run),
            "workflow" to workflow(run),
            "node" to linkedMapOf(
                "id" to node.nodeId, "name" to node.nodeName, "executionId" to node.executionId.toString(),
                "attempt" to node.attempt, "status" to node.status, "port" to node.port,
            ),
            "template" to linkedMapOf("key" to node.templateKey, "version" to templateVersion, "lineage" to lineage),
            "job" to linkedMapOf(
                "id" to node.runnerJobId?.toString(), "agent" to evidence?.agent, "status" to (evidence?.status ?: node.status),
                "branch" to evidence?.branch, "mergeRequestUrl" to (evidence?.mergeRequestUrl ?: run.mergeRequestUrl),
                "repositories" to node.repositories.map {
                    linkedMapOf(
                        "key" to it.key, "url" to it.url, "access" to it.access, "branch" to it.branch, "commitSha" to it.commitSha,
                        "mergeRequestUrl" to it.mergeRequestUrl, "pushed" to it.pushed,
                    )
                },
            ),
            "model" to linkedMapOf("ref" to (evidence?.modelRef ?: ledger.models.firstOrNull()), "effort" to evidence?.effort),
            "prompts" to linkedMapOf(
                "promptSha256" to evidence?.promptSha256,
                "constitutionSha256" to evidence?.constitutionSha256,
                "skills" to (evidence?.skillSha256 ?: emptyMap<String, String>()),
            ),
            "sandbox" to linkedMapOf(
                "policyDigest" to evidence?.policyDigest, "isolation" to evidence?.isolation,
                "image" to evidence?.image, "imageDigest" to evidence?.imageDigest,
                "allowedHosts" to (evidence?.allowedHosts ?: emptyList<String>()), "egressLogDigest" to evidence?.egressLogDigest,
            ),
            "usage" to usage(usage, cost),
            "verification" to linkedMapOf("command" to evidence?.verifyCommand, "exitCode" to evidence?.verificationExitCode),
            "gateDecisions" to decisions.map(::decision),
            "timestamps" to linkedMapOf(
                "startedAt" to (evidence?.startedAt ?: node.startedAt)?.toString(),
                "finishedAt" to (evidence?.finishedAt ?: node.finishedAt)?.toString(),
                "attestedAt" to now.toString(),
            ),
        )
    }

    fun runSubjects(run: RunProvenance, jobSubjects: List<AttestationSubject>): List<AttestationSubject> {
        if (jobSubjects.isNotEmpty()) return jobSubjects.distinctBy { it.name }
        val manifest = run.artifacts.sortedBy { it.id }.joinToString("\n") { "${it.name}:${it.sha256}" }
        return listOf(AttestationSubject("back-office:run/${run.runId}", mapOf("sha256" to Digests.sha256Hex("${run.runId}\n$manifest"))))
    }

    fun runPredicate(run: RunProvenance, ledger: LedgerUsage, jobAttestations: List<String>, now: Instant): Map<String, Any?> {
        val nodeUsage = run.nodes.fold(TokenUsage.ZERO) { acc, n -> acc + n.usage }
        val usage = if (ledger.usage == TokenUsage.ZERO) nodeUsage else ledger.usage
        val cost = if (ledger.cost.isZero()) run.cost else ledger.cost
        val duration = if (run.startedAt != null && run.finishedAt != null) Duration.between(run.startedAt, run.finishedAt).seconds else null
        return linkedMapOf(
            "kind" to KIND_RUN,
            "runId" to run.runId.toString(),
            "project" to project(run),
            "workflow" to workflow(run),
            "issueKey" to run.issueKey,
            "status" to run.status,
            "outcome" to run.outcome,
            "startedBy" to actor(run.startedBy),
            "mergeRequestUrl" to run.mergeRequestUrl,
            "path" to run.nodes.map {
                linkedMapOf(
                    "nodeId" to it.nodeId, "name" to it.nodeName, "kind" to it.kind, "attempt" to it.attempt, "status" to it.status,
                    "port" to it.port, "templateKey" to it.templateKey, "jobId" to it.runnerJobId?.toString(),
                    "startedAt" to it.startedAt?.toString(), "finishedAt" to it.finishedAt?.toString(),
                )
            },
            "gateDecisions" to run.decisions.map(::decision),
            "jobAttestations" to jobAttestations,
            "totals" to linkedMapOf(
                "nodes" to run.nodes.size,
                "agentJobs" to run.nodes.count { it.runnerJobId != null },
                "durationSeconds" to duration,
                "models" to ledger.models,
            ) + usage(usage, cost),
            "timestamps" to linkedMapOf(
                "createdAt" to run.createdAt.toString(), "startedAt" to run.startedAt?.toString(),
                "finishedAt" to run.finishedAt?.toString(), "attestedAt" to now.toString(),
            ),
        )
    }

    private fun diffArtifact(run: RunProvenance, node: ProvenanceNode) =
        run.artifacts.firstOrNull { it.id in node.artifactIds && (it.kind == "DIFF" || it.name == "diff") }

    private fun project(run: RunProvenance) = linkedMapOf(
        "id" to run.projectId.toString(), "key" to run.projectKey, "repository" to run.repositoryUrl,
        "repositories" to run.repositories.map { linkedMapOf("key" to it.key, "url" to it.url, "purpose" to it.purpose, "primary" to it.primary) },
    )

    private fun workflow(run: RunProvenance) =
        linkedMapOf("id" to run.workflowId.toString(), "name" to run.workflowName, "version" to run.workflowVersion)

    private fun usage(usage: TokenUsage, cost: Money): Map<String, Any?> = linkedMapOf(
        "inputTokens" to usage.inputTokens, "outputTokens" to usage.outputTokens,
        "cacheReadTokens" to usage.cacheReadTokens, "cacheWriteTokens" to usage.cacheWriteTokens,
        "cost" to linkedMapOf("amount" to cost.rounded().amount.toPlainString(), "currency" to cost.currency),
    )

    private fun decision(d: ProvenanceGateDecision): Map<String, Any?> = linkedMapOf(
        "approvalId" to d.approvalId.toString(), "nodeId" to d.nodeId, "option" to d.option, "comment" to d.comment,
        "checkedItems" to d.checkedItems, "decidedBy" to actor(d.decidedBy), "decidedAt" to d.decidedAt.toString(),
    )

    private fun actor(a: Actor?): Map<String, Any?>? = a?.let { linkedMapOf("kind" to it.kind.name, "userId" to it.userId?.toString(), "displayName" to it.displayName) }
}
