package pl.mos.backoffice.governance.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.governance.domain.model.JobEvidence
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.provenance.ProvenanceNode
import pl.mos.backoffice.shared.provenance.ProvenanceRepository
import pl.mos.backoffice.shared.provenance.ProvenanceRepositoryResult
import pl.mos.backoffice.shared.provenance.RunProvenance
import java.time.Instant
import java.util.UUID

/** ADR 0034: an agent-job attestation names one subject per repository the job pushed to. */
class ProvenanceRepositorySubjectsTest {

    private val t0 = Instant.parse("2026-10-08T10:00:00Z")
    private val job = UUID.randomUUID()

    private fun node(repositories: List<ProvenanceRepositoryResult>) = ProvenanceNode(
        UUID.randomUUID(), "impl", "Implement", "TASK", "implement-opencode", 1, "SUCCEEDED", "success", t0, t0.plusSeconds(60), job,
        TokenUsage(10, 1), Money.usd("0.10"), null, emptyList(), repositories,
    )

    private fun run(node: ProvenanceNode) = RunProvenance(
        runId = UUID.randomUUID(), projectId = UUID.randomUUID(), projectKey = "ACME", repositoryUrl = "https://git.example.com/acme/app.git",
        workflowId = UUID.randomUUID(), workflowName = "Issue to MR", workflowVersion = 1, issueKey = "ACME-1", status = "SUCCEEDED", outcome = "SUCCESS",
        terminal = true, startedBy = null, createdAt = t0, startedAt = t0, finishedAt = t0.plusSeconds(100), cost = Money.usd("0.10"),
        mergeRequestUrl = null, branch = null, nodes = listOf(node), decisions = emptyList(), artifacts = emptyList(),
        repositories = listOf(
            ProvenanceRepository("app", "https://git.example.com/acme/app.git", "SOURCE", true),
            ProvenanceRepository("docs", "https://git.example.com/acme/docs.git", "DOCS", false),
        ),
    )

    @Test
    fun `one subject per pushed repository, read-only repositories are not subjects`() {
        val node = node(
            listOf(
                ProvenanceRepositoryResult("app", "https://git.example.com/acme/app.git", "PUSH_MR", "feature/a", "aaa111", "https://mr/1", pushed = true),
                ProvenanceRepositoryResult("docs", null, "PUSH_MR", "feature/a", "ddd222", null, pushed = true),
                ProvenanceRepositoryResult("lib", "https://git.example.com/acme/lib.git", "READ", "main", "eee333", null, pushed = false),
            ),
        )
        val run = run(node)
        val subjects = ProvenanceStatements.jobSubjects(run, node, JobEvidence(job, commitSha = "aaa111"))

        assertThat(subjects.map { it.name }).containsExactly(
            "git+https://git.example.com/acme/app.git@aaa111",
            "git+https://git.example.com/acme/docs.git@ddd222",
        )
        assertThat(subjects[1].digest).containsEntry("sha256", Digests.sha256Hex("ddd222")).containsEntry("gitCommit", "ddd222")
        assertThat(ProvenanceStatements.runSubjects(run, subjects)).hasSize(2)

        @Suppress("UNCHECKED_CAST")
        val jobSection = ProvenanceStatements.jobPredicate(run, node, null, LedgerUsage.NONE, emptyList(), t0)["job"] as Map<String, Any?>
        assertThat((jobSection["repositories"] as List<*>).map { (it as Map<*, *>)["key"] }).containsExactly("app", "docs", "lib")
    }

    @Test
    fun `nodes recorded before multi-repository projects keep the single commit subject`() {
        val node = node(emptyList())
        val subjects = ProvenanceStatements.jobSubjects(run(node), node, JobEvidence(job, commitSha = "4f2a9c"))
        assertThat(subjects.map { it.name }).containsExactly("git+https://git.example.com/acme/app.git@4f2a9c")
    }
}
