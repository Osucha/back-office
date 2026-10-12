package pl.mos.backoffice.runnercontroller.sandbox.docker

import io.fabric8.kubernetes.api.model.Quantity
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.AclService
import pl.mos.backoffice.runnercontroller.egress.EgressAcl
import pl.mos.backoffice.runnercontroller.egress.EgressDecision
import pl.mos.backoffice.runnercontroller.git.GitCredentialSelector
import pl.mos.backoffice.runnercontroller.git.JobRepositories
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.sandbox.LOCAL_LOGIN_FILE
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import pl.mos.backoffice.runnercontroller.sandbox.LaunchRequest
import pl.mos.backoffice.runnercontroller.sandbox.ManagedSandbox
import pl.mos.backoffice.runnercontroller.sandbox.PoolSettings
import pl.mos.backoffice.runnercontroller.sandbox.SandboxBackend
import pl.mos.backoffice.runnercontroller.sandbox.SandboxHandle
import pl.mos.backoffice.runnercontroller.sandbox.SandboxLaunchFailed
import pl.mos.backoffice.runnercontroller.sandbox.SandboxObservation
import pl.mos.backoffice.runnercontroller.sandbox.SandboxPhase
import pl.mos.backoffice.runnercontroller.sandbox.k8s.PodSpecRenderer
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator

/**
 * `DOCKER` backend (local development only; runc, no gVisor). Mirrors the Kubernetes egress design with
 * Docker primitives:
 *
 * - a per-job `--internal` network `bo-job-<id>`: containers on it have no route to anything outside it;
 * - a per-job egress proxy container `bo-egress-<id>` (same image as in Kubernetes, `BO_EGRESS_MODE=static`,
 *   ACL = the job's hosts as default rule) on the default bridge **and** the job network (alias `egress-proxy`);
 * - the agent container only on the job network, `HTTPS_PROXY=http://egress-proxy:4750`, read-only root
 *   filesystem, tmpfs `/workspace` and `/tmp`, `--cap-drop ALL`, `no-new-privileges`, `--pids-limit`,
 *   `--memory`, `--cpus`, the bundle mounted read-only at `/var/run/backoffice/job`.
 *
 * Decisions are read from the proxy's `BO-EGRESS {json}` log lines.
 */
class DockerBackend(
    private val props: ControllerProperties,
    private val docker: DockerCli,
    private val json: JsonMapper,
    private val env: (String) -> String? = System::getenv,
) : SandboxBackend {
    private val log = LoggerFactory.getLogger(javaClass)
    override val kind = BackendKind.DOCKER

    private val workDir: Path = props.docker.workDir.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("java.io.tmpdir"), "bo-runner-controller")

    private fun agentName(jobId: String) = Labels.sandboxName(jobId)
    private fun proxyName(jobId: String) = "bo-egress-$jobId"
    private fun networkName(jobId: String) = Labels.sandboxName(jobId)
    private fun jobDir(jobId: String) = workDir.resolve(jobId)

    override fun ensurePool(pool: PoolSettings) {
        Files.createDirectories(workDir)
    }

    /** `rwx-wx-wx`: the sandbox user may delete (not list) the files; a no-op on file systems without POSIX modes. */
    private fun allowSandboxToDelete(dir: Path) {
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx-wx-wx"))
        } catch (e: UnsupportedOperationException) {
            Unit // Windows / Docker Desktop: bind mounts are writable for the container user anyway
        } catch (e: java.io.IOException) {
            Unit
        }
    }

    override fun proxyUrl(request: LaunchRequest): String = "http://$PROXY_ALIAS:${props.egress.proxyPort}"

    /**
     * `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` of the controller, `BO_GIT_TOKEN` as push-level fallback (ADR 0031).
     * They apply to every repository host; a multi-repository job gets the variable of each repository's own level.
     */
    override val gitCredentials: GitCredentialSelector get() = GitCredentialSelector.forEnvironment(props.git, env)

    override fun launch(request: LaunchRequest): SandboxHandle {
        val jobId = request.jobId
        val dir = jobDir(jobId)
        val bundleDir = dir.resolve("job")
        val gitDir = dir.resolve("git")
        Files.createDirectories(bundleDir)
        Files.createDirectories(gitDir)
        // writable for the sandbox user (10001): the runner deletes the secret files before the agent starts (ADR 0029)
        listOf(bundleDir, gitDir).forEach(::allowSandboxToDelete)
        // ADR 0040: the owner's Claude Code login, before `ready`; the runner reads and deletes it
        request.localLoginCredential?.let(env)?.takeIf { it.isNotBlank() }?.let { Files.writeString(bundleDir.resolve(LOCAL_LOGIN_FILE), it.trim()) }
        request.bundle.files(proxyUrl(request)).forEach { (name, content) -> Files.writeString(bundleDir.resolve(name), content) }
        // only the variable selected for this job's level (ADR 0031): READ -> BO_GIT_TOKEN_READ, PUSH_MR -> BO_GIT_TOKEN_PUSH
        request.gitCredential?.let(env)?.takeIf { it.isNotBlank() }?.let { Files.writeString(gitDir.resolve("token"), it) }
        // multi-repository jobs: git/<key>/token, only for the repositories that got a credential
        request.repositoryTokens.forEach { repo ->
            require(JobRepositories.KEY.matches(repo.key)) { "invalid repository key ${repo.key}" }
            val token = env(repo.credential)?.takeIf { it.isNotBlank() } ?: return@forEach
            val repoDir = Files.createDirectories(gitDir.resolve(repo.key))
            allowSandboxToDelete(repoDir)
            Files.writeString(repoDir.resolve("token"), token)
        }
        val acl = EgressAcl.forSingleJob(AclService(jobId, request.poolId, request.policy.egressHosts))
        val aclFile = dir.resolve("acl.yaml")
        Files.writeString(aclFile, EgressAcl.toYaml(acl))

        val labels = labels(request)
        try {
            docker.run(listOf("network", "create", "--internal") + labelArgs(labels) + networkName(jobId))
            startProxy(request, aclFile, labels)
            val policy = request.policy
            val args = mutableListOf(
                "run", "-d", "--name", agentName(jobId),
                "--network", networkName(jobId),
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,size=1g",
                "--tmpfs", "${PodSpecRenderer.WORKSPACE}:rw,exec,size=${bytes(policy.ephemeralStorage)},uid=10001,gid=10001,mode=0755",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", policy.pids.toString(),
                "--memory", bytes(policy.memory).toString(),
                "--cpus", cpus(policy.cpu),
                "-v", "${bundleDir.toAbsolutePath()}:${PodSpecRenderer.JOB_DIR}:rw",
                "-v", "${gitDir.toAbsolutePath()}:${PodSpecRenderer.GIT_DIR}:rw",
                "-e", "RUNNER_JOB_DIR=${PodSpecRenderer.JOB_DIR}",
                "-e", "RUNNER_GIT_TOKEN_FILE=${PodSpecRenderer.GIT_DIR}/token",
                "-e", "RUNNER_GIT_TOKEN_DIR=${PodSpecRenderer.GIT_DIR}",
                "-e", "RUNNER_WORKSPACE=${PodSpecRenderer.WORKSPACE}",
                "-e", "RUNNER_MAX_PROCS=${policy.pids}",
                "-e", "HOME=${PodSpecRenderer.WORKSPACE}/.home",
                "-e", "TMPDIR=/tmp",
            )
            props.git.identityEnv().forEach { (k, v) -> args += listOf("-e", "$k=$v") }
            props.docker.extraEnv.forEach { (k, v) -> args += listOf("-e", "$k=$v") }
            args += labelArgs(labels + (ROLE_LABEL to "agent"))
            args += policy.image
            docker.run(args)
        } catch (e: DockerException) {
            delete(SandboxHandle(jobId, agentName(jobId), kind))
            throw SandboxLaunchFailed("docker launch failed: ${e.message}", e)
        }
        return SandboxHandle(jobId, agentName(jobId), kind)
    }

    private fun startProxy(request: LaunchRequest, aclFile: Path, labels: Map<String, String>) {
        val jobId = request.jobId
        val args = mutableListOf(
            "run", "-d", "--name", proxyName(jobId),
            "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "-v", "${aclFile.toAbsolutePath()}:/etc/bo/acl.yaml:ro",
            "-e", "BO_EGRESS_MODE=static",
            "-e", "BO_EGRESS_ACL_FILE=/etc/bo/acl.yaml",
        )
        props.docker.proxyExtraHosts.forEach { args += listOf("--add-host", it) }
        args += labelArgs(labels + (ROLE_LABEL to "egress"))
        args += props.egress.proxyImage
        args += listOf("--listen-port", props.egress.proxyPort.toString())
        props.egress.allowRanges.forEach { args += listOf("--allow-range", it) }
        props.egress.allowAddresses.forEach { args += listOf("--allow-address", it) }
        docker.run(args)
        docker.run(listOf("network", "connect", "--alias", PROXY_ALIAS, networkName(jobId), proxyName(jobId)))
    }

    override fun observe(handle: SandboxHandle): SandboxObservation {
        val result = docker.run(listOf("inspect", "--format", "{{json .State}}|{{.Image}}", handle.name), check = false)
        if (result.exitCode != 0) return SandboxObservation(SandboxPhase.GONE)
        val (stateJson, image) = result.stdout.trim().split('|', limit = 2).let { it[0] to it.getOrNull(1) }
        val state = json.readTree(stateJson)
        val status = state.get("Status")?.asString()
        val exit = state.get("ExitCode")?.asInt()
        val oom = state.get("OOMKilled")?.asBoolean() == true
        val started = state.get("StartedAt")?.asString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return when (status) {
            "created" -> SandboxObservation(SandboxPhase.PENDING)
            "running", "paused", "restarting" -> SandboxObservation(SandboxPhase.RUNNING, node = "docker", imageDigest = image, startedAt = started)
            "exited", "dead" -> SandboxObservation(
                phase = if (exit == 0 && !oom) SandboxPhase.SUCCEEDED else SandboxPhase.FAILED,
                exitCode = exit,
                reason = if (oom) "OOMKilled" else state.get("Error")?.asString()?.takeIf { it.isNotBlank() },
                node = "docker",
                imageDigest = image,
                startedAt = started,
            )
            else -> SandboxObservation(SandboxPhase.PENDING)
        }
    }

    override fun egressDecisions(handle: SandboxHandle): List<EgressDecision> {
        val logs = docker.run(listOf("logs", proxyName(handle.jobId)), check = false)
        return logs.stdout.lineSequence()
            .filter { it.startsWith(DECISION_PREFIX) }
            .mapNotNull { runCatching { json.readValue(it.removePrefix(DECISION_PREFIX), EgressDecision::class.java) }.getOrNull() }
            .map { if (it.role.isEmpty()) it.copy(role = handle.jobId) else it }
            .toList()
    }

    /** Agent container output (debugging; the runner also streams it to Back Office). */
    fun agentLogs(handle: SandboxHandle): String {
        val r = docker.run(listOf("logs", handle.name), check = false)
        return r.stdout + r.stderr
    }

    override fun delete(handle: SandboxHandle) {
        val jobId = handle.jobId
        docker.run(listOf("rm", "-f", agentName(jobId), proxyName(jobId)), check = false)
        docker.run(listOf("network", "rm", networkName(jobId)), check = false)
        runCatching {
            val dir = jobDir(jobId)
            if (Files.exists(dir)) Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }.onFailure { log.warn("cannot remove {}: {}", jobDir(jobId), it.message) }
    }

    override fun listManaged(poolId: String): List<ManagedSandbox> {
        val r = docker.run(
            listOf("ps", "-a", FILTER_FLAG, "label=${Labels.MANAGED_BY}=${Labels.MANAGED_BY_VALUE}",
                FILTER_FLAG, "label=${Labels.POOL}=${Labels.value(poolId)}", FILTER_FLAG, "label=$ROLE_LABEL=agent", "--format", "{{.Names}}"),
            check = false,
        )
        return r.stdout.lines().map { it.trim() }.filter { it.startsWith("bo-job-") }
            .map { ManagedSandbox(SandboxHandle(it.removePrefix("bo-job-"), it, kind), poolId) }
    }

    private fun labels(request: LaunchRequest) = buildMap {
        put(Labels.MANAGED_BY, Labels.MANAGED_BY_VALUE)
        put(Labels.POOL, Labels.value(request.poolId))
        put(Labels.JOB_ID, request.jobId)
        put(Labels.DEADLINE, request.deadline.toString())
        request.projectId?.let { put(Labels.PROJECT_ID, it) }
        request.runId?.let { put(Labels.RUN_ID, it) }
    }

    private fun labelArgs(labels: Map<String, String>) = labels.flatMap { listOf("--label", "${it.key}=${it.value}") }

    companion object {
        const val PROXY_ALIAS = "egress-proxy"
        private const val FILTER_FLAG = "--filter"
        const val ROLE_LABEL = "backoffice.io/role"
        const val DECISION_PREFIX = "BO-EGRESS "

        fun bytes(quantity: String): Long = Quantity.getAmountInBytes(Quantity(quantity)).setScale(0, RoundingMode.UP).toLong()

        fun cpus(quantity: String): String = Quantity.getAmountInBytes(Quantity(quantity)).setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros().max(BigDecimal("0.01")).toPlainString()
    }
}
