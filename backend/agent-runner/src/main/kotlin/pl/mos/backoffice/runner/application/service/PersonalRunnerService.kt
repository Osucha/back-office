package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.IssuedPersonalRegistration
import pl.mos.backoffice.runner.application.port.`in`.ManagePersonalRunner
import pl.mos.backoffice.runner.application.port.`in`.PersonalRunnerView
import pl.mos.backoffice.runner.application.port.out.AgentJobStore
import pl.mos.backoffice.runner.application.port.out.RegistrationTokenStore
import pl.mos.backoffice.runner.application.port.out.RunnerNodeStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.FleetTokens
import pl.mos.backoffice.runner.domain.LlmCredentials
import pl.mos.backoffice.runner.domain.RegistrationToken
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import java.net.URI
import java.util.UUID

/**
 * The signed-in user's own runner pool (ADR 0040, `fleet.personal`): one pool per user, created on demand, which
 * runs only the jobs routed to that user's personal runner (steps of their private projects). The user registers
 * runner-controllers on their own computer with a single-use token and the `docker run` command built here.
 */
class PersonalRunnerService(
    private val pools: RunnerPoolStore,
    private val nodes: RunnerNodeStore,
    private val registrations: RegistrationTokenStore,
    private val jobs: AgentJobStore,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManagePersonalRunner {

    override fun mine(): PersonalRunnerView? {
        val me = gate.require(RunnerPermissions.FLEET_PERSONAL)
        return ownPool(userOf(me))?.let(::view)
    }

    override fun enable(llmCredentials: LlmCredentials): PersonalRunnerView {
        val me = gate.require(RunnerPermissions.FLEET_PERSONAL)
        val pool = tx.inTransaction {
            val now = lifecycle.now()
            val existing = ownPool(userOf(me))
            when {
                existing == null -> {
                    val settings = lifecycle.settings.personal
                    val created = RunnerPool(
                        id = RunnerPool.personalId(userOf(me)), name = "Personal runner of ${me.displayName}".take(200),
                        description = "Computer of ${me.login} (ADR 0040)", labels = mapOf(PERSONAL_LABEL to "true"),
                        ceiling = SandboxPolicy.DEFAULT, allowedImages = settings.allowedImages, warmPoolSize = 0,
                        maxConcurrentJobs = settings.maxConcurrentJobs, projectIds = emptySet(), createdAt = now, updatedAt = now,
                        ownerUserId = userOf(me), llmCredentials = llmCredentials,
                    )
                    pools.insert(created)
                    audit.record(AuditEntry("RUNNER_POOL_CREATE", "RunnerPool", created.id, details = mapOf("personal" to true, "llmCredentials" to llmCredentials.name)))
                    created
                }
                existing.llmCredentials != llmCredentials -> {
                    val updated = existing.copy(llmCredentials = llmCredentials, updatedAt = now)
                    pools.update(updated)
                    audit.record(
                        AuditEntry(
                            "RUNNER_POOL_UPDATE", "RunnerPool", updated.id,
                            details = mapOf("llmCredentials" to mapOf("from" to existing.llmCredentials.name, "to" to llmCredentials.name)),
                        ),
                    )
                    updated
                }
                else -> existing
            }
        }
        return view(pool)
    }

    override fun disable() {
        val me = gate.require(RunnerPermissions.FLEET_PERSONAL)
        tx.inTransaction {
            val pool = ownPool(userOf(me)) ?: return@inTransaction
            if (jobs.hasLiveJobs(pool.id)) throw InvalidStateException("your personal runner still has queued or running agent jobs; cancel them or wait")
            pools.delete(pool.id)
            audit.record(AuditEntry("RUNNER_POOL_DELETE", "RunnerPool", pool.id, details = mapOf("personal" to true)))
        }
    }

    override fun issueRegistrationToken(): IssuedPersonalRegistration {
        val me = gate.require(RunnerPermissions.FLEET_PERSONAL)
        return tx.inTransaction {
            val pool = ownPool(userOf(me)) ?: throw NotFoundException("RunnerPool", "personal runner of ${me.login}")
            val now = lifecycle.now()
            val generated = FleetTokens.generate(FleetTokens.REGISTRATION_PREFIX)
            val token = RegistrationToken(UUID.randomUUID(), pool.id, now, now.plus(lifecycle.settings.registrationTokenValidity), null)
            registrations.insert(token, generated.hash, me.login)
            audit.record(
                AuditEntry(
                    "RUNNER_REGISTRATION_TOKEN_CREATE", "RunnerPool", pool.id,
                    details = mapOf("tokenId" to token.id.toString(), "expiresAt" to token.expiresAt.toString(), "personal" to true),
                ),
            )
            IssuedPersonalRegistration(generated.plaintext, token.expiresAt, dockerCommand(pool, generated.plaintext))
        }
    }

    override fun revokeRunner(runnerId: UUID) {
        val me = gate.require(RunnerPermissions.FLEET_PERSONAL)
        tx.inTransaction {
            val pool = ownPool(userOf(me)) ?: throw NotFoundException("Runner", runnerId)
            // another user's runner is reported as missing, not as forbidden
            val node = nodes.find(runnerId)?.takeIf { it.poolId == pool.id } ?: throw NotFoundException("Runner", runnerId)
            if (node.revokedAt != null) return@inTransaction
            val requeued = lifecycle.revokeRunner(node)
            audit.record(AuditEntry("RUNNER_REVOKE", "Runner", runnerId.toString(), details = mapOf("poolId" to pool.id, "name" to node.name, "requeuedJobs" to requeued, "personal" to true)))
        }
    }

    private fun userOf(me: CurrentUser): UUID = me.userId?.value ?: throw ValidationException("a personal runner needs a signed-in person", null)

    private fun ownPool(userId: UUID): RunnerPool? = pools.findAll().firstOrNull { it.ownerUserId == userId }

    private fun view(pool: RunnerPool): PersonalRunnerView {
        val now = lifecycle.now()
        val running = jobs.countActiveByRunners()
        val counts = jobs.countsByPool()[pool.id]
        val runners = nodes.list(pool.id).filter { it.revokedAt == null }
            .map { runnerView(it, running[it.id] ?: 0, it.online(now, lifecycle.settings.onlineWindow)) }
        return PersonalRunnerView(pool.id, pool.llmCredentials, runners, counts?.queued ?: 0, counts?.active ?: 0, pool.createdAt)
    }

    /**
     * One `docker run` of the runner-controller with the DOCKER backend on the user's computer. Secrets that are not
     * Back Office's (the Claude login, git tokens) are passed by name only (`-e NAME`): Docker copies them from the
     * shell, so they never appear in this command, the UI or Back Office.
     */
    internal fun dockerCommand(pool: RunnerPool, token: String): String {
        val settings = lifecycle.settings.personal
        val api = lifecycle.settings.apiUrl.trimEnd('/')
        val local = isLocalHost(api)
        val url = if (local) withHost(api, DOCKER_HOST) else api
        val lines = buildList {
            add("docker run -d --name back-office-runner --hostname back-office-runner --restart unless-stopped")
            // the Docker socket is root-equivalent anyway; the image's own user cannot open it on most hosts
            add("--user 0:0")
            add("-v /var/run/docker.sock:/var/run/docker.sock")
            // same path inside and outside: the job directories are bind-mounted into sandboxes by the Docker daemon
            add("-v $DATA_DIR:$DATA_DIR")
            if (local) add("--add-host $DOCKER_HOST:host-gateway")
            add("-e RUNNER_BACKOFFICE_URL=$url")
            add("-e RUNNER_REGISTRATION_TOKEN=$token")
            add("-e RUNNER_BACKEND=DOCKER")
            add("-e RUNNER_TOKEN_FILE=$DATA_DIR/runner-token")
            add("-e RUNNER_DOCKER_WORK_DIR=$DATA_DIR/jobs")
            add("-e RUNNER_MAX_CONCURRENT=${settings.maxConcurrentJobs}")
            add("-e RUNNER_DEFAULT_IMAGE=${settings.runnerImage}")
            add("-e RUNNER_EGRESS_PROXY_IMAGE=${settings.egressProxyImage}")
            // Back Office on this computer: its address is private, the egress proxy must be allowed to reach it
            if (local) add("-e RUNNER_EGRESS_ALLOW_RANGES=$PRIVATE_RANGES")
            if (pool.llmCredentials == LlmCredentials.LOCAL_LOGIN) add("-e CLAUDE_CODE_OAUTH_TOKEN")
            // ADR 0051: no git tokens on the runner; the project's repository tokens are released sealed per job
            add(settings.controllerImage)
        }
        return lines.joinToString(" \\\n  ")
    }

    companion object {
        const val PERSONAL_LABEL = "backoffice.io/personal"
        const val DATA_DIR = "/var/lib/back-office-runner"
        const val DOCKER_HOST = "host.docker.internal"
        private const val PRIVATE_RANGES = "172.16.0.0/12,192.168.0.0/16"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        fun isLocalHost(url: String): Boolean = runCatching { URI(url).host?.lowercase() }.getOrNull().let { it == null || it in LOCAL_HOSTS || it == DOCKER_HOST }

        /** [url] with its host replaced (scheme, port and path kept). */
        fun withHost(url: String, host: String): String {
            val uri = URI(url)
            return URI(uri.scheme, uri.userInfo, host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }
    }
}
