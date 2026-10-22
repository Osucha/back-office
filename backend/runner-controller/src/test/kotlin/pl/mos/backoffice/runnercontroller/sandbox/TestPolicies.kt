package pl.mos.backoffice.runnercontroller.sandbox

import pl.mos.backoffice.runnercontroller.egress.ProxyCredentials
import pl.mos.backoffice.runnercontroller.git.GitAccess
import pl.mos.backoffice.runnercontroller.git.RepositoryToken
import pl.mos.backoffice.runnercontroller.policy.Isolation
import pl.mos.backoffice.runnercontroller.policy.ResolvedPolicy
import java.time.Instant

object TestPolicies {
    fun resolved(isolation: Isolation = Isolation.STANDARD, caches: List<String> = emptyList(), image: String = "back-office/runner:dev") = ResolvedPolicy(
        isolation = isolation,
        image = image,
        cpu = "2",
        memory = "4Gi",
        ephemeralStorage = "10Gi",
        pids = 1024,
        timeoutSeconds = 1800,
        egressHosts = listOf("bo.example.com", "gitlab.example.com"),
        toolsAllow = listOf("git *"),
        toolsDeny = listOf("curl *"),
        toolsAsk = emptyList(),
        secrets = emptyList(),
        caches = caches,
    )

    fun launch(
        jobId: String = "11111111-2222-4333-8444-555555555555",
        policy: ResolvedPolicy = resolved(),
        gitAccess: GitAccess = GitAccess.PUSH_MR,
        gitCredential: String? = "git-gitlab",
        repositoryTokens: List<RepositoryToken> = emptyList(),
        repositoryAccess: Map<String, GitAccess> = emptyMap(),
        projectTokens: ProjectTokens = ProjectTokens.NONE,
    ) = LaunchRequest(
        jobId = jobId,
        poolId = "pool-a",
        projectId = "0b8b1c2e-0000-4000-8000-000000000001",
        runId = "0b8b1c2e-0000-4000-8000-000000000002",
        policy = policy,
        bundle = JobBundle("""{"mode":"IMPLEMENT"}""", """{"isolation":"STANDARD"}""", jobId, "bo_job_secret"),
        gitAccess = gitAccess,
        gitCredential = gitCredential,
        proxy = ProxyCredentials(jobId, "proxy-pw"),
        deadline = Instant.parse("2026-09-28T11:00:00Z"),
        repositoryTokens = repositoryTokens,
        repositoryAccess = repositoryAccess,
        projectTokens = projectTokens,
    )

    /** A docs repository (READ), a source repository (PUSH_MR) and a NONE repository without a token. */
    val MULTI_REPO_TOKENS = listOf(
        RepositoryToken("docs", GitAccess.READ, "git-docs-read"),
        RepositoryToken("src", GitAccess.PUSH_MR, "git-src-push"),
    )
    val MULTI_REPO_ACCESS = mapOf("docs" to GitAccess.READ, "src" to GitAccess.PUSH_MR, "tools" to GitAccess.NONE)

    fun multiRepoLaunch(jobId: String = "11111111-2222-4333-8444-555555555555", policy: ResolvedPolicy = resolved()) =
        launch(jobId, policy, GitAccess.PUSH_MR, null, MULTI_REPO_TOKENS, MULTI_REPO_ACCESS)
}
