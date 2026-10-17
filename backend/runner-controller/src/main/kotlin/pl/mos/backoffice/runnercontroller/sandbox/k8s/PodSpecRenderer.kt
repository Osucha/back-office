package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.Capabilities
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.SeccompProfileBuilder
import io.fabric8.kubernetes.api.model.SecurityContextBuilder
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.git.JobRepositories
import pl.mos.backoffice.runnercontroller.policy.Isolation
import pl.mos.backoffice.runnercontroller.policy.ResolvedPolicy
import pl.mos.backoffice.runnercontroller.sandbox.Labels
import java.security.MessageDigest

/** How the job bundle reaches the pod. */
sealed interface BundleDelivery {
    /**
     * Cold start: per-job Secret (and optional git Secret), copied by the `bundle` init container into
     * memory-backed volumes, so the runner can delete the secret files before it starts the agent (ADR 0029).
     */
    data class Secrets(
        val jobSecret: String,
        /** Legacy single-repository specs: the selected git Secret (copied to `git/token`). */
        val gitSecret: String?,
        /** Multi-repository specs: repository key -> selected Secret (copied to `git/<key>/token`); only those. */
        val repositorySecrets: List<RepositorySecret> = emptyList(),
    ) : BundleDelivery

    data class RepositorySecret(val key: String, val secret: String)

    /** Warm pool: memory-backed emptyDirs the controller fills after the claim (`pods/exec`); the runner waits for `ready`. */
    data object Warm : BundleDelivery
}

/**
 * Renders the sandbox pod (doc 10, "Sandbox pod"): one `agent` container running the runner image, no
 * sidecar (egress goes through the pool's egress proxy, see [EgressInfrastructure]).
 *
 * - pod: `restartPolicy: Never`, `automountServiceAccountToken: false`, `enableServiceLinks: false`,
 *   `runtimeClassName` from the isolation (STANDARD -> gvisor, HIGH -> kata, configurable), runAsNonRoot
 *   10001:10001, fsGroup, seccomp `RuntimeDefault`, `activeDeadlineSeconds` (cold starts only);
 * - container: read-only root filesystem, `allowPrivilegeEscalation: false`, all capabilities dropped,
 *   requests = limits for cpu/memory/ephemeral-storage, writable emptyDirs for `/workspace` (size-limited)
 *   and `/tmp`, bundle at `/var/run/backoffice/job` and git token(s) at `/var/run/backoffice/git` (`token`, or
 *   `<key>/token` per repository of a multi-repository job), both
 *   memory-backed and writable: the runner reads the secrets into memory and deletes the files before the
 *   agent starts (ADR 0029). Cold pods get them from the Secrets through the `bundle` init container (the
 *   Secret volumes are mounted only there), warm pods from the controller (`pods/exec`);
 * - pids: Kubernetes has no per-pod pids field; the node's kubelet `podPidsLimit` is the hard limit and the
 *   runner additionally applies `RLIMIT_NPROC` = `resources.pids` (`RUNNER_MAX_PROCS`).
 */
class PodSpecRenderer(
    private val k8s: ControllerProperties.Kubernetes,
    /** Commit identity (`GIT_USER_NAME` / `GIT_USER_EMAIL`) of every sandbox of this controller. */
    private val git: ControllerProperties.Git = ControllerProperties.Git(),
) {

    fun runtimeClassFor(isolation: Isolation): String? = k8s.runtimeClasses[isolation.name]?.takeIf { it.isNotBlank() }

    /** [workspaceClaim] (ADR 0044): the run workspace PVC mounted at `/workspace`; null = a size-limited emptyDir. */
    fun podSpec(policy: ResolvedPolicy, delivery: BundleDelivery, activeDeadlineSeconds: Long?, workspaceClaim: String? = null): PodSpec {
        val workspace = workspaceClaim?.let { VolumeBuilder().withName("workspace").withNewPersistentVolumeClaim(it, false).build() }
            ?: emptyDir("workspace", policy.ephemeralStorage, memory = false)
        val volumes = mutableListOf(workspace, emptyDir("tmp", "1Gi", memory = false))
        val mounts = mutableListOf(mount("workspace", WORKSPACE, false), mount("tmp", "/tmp", false))
        // The agent container always gets writable memory volumes for the bundle and the git token.
        volumes += emptyDir("job", "1Mi", memory = true)
        volumes += emptyDir("git", "64Ki", memory = true)
        mounts += mount("job", JOB_DIR, false)
        mounts += mount("git", GIT_DIR, false)
        val initContainers = mutableListOf<Container>()
        if (delivery is BundleDelivery.Secrets) {
            volumes += secretVolume(JOB_SECRET_VOLUME, delivery.jobSecret, optional = false)
            val initMounts = mutableListOf(mount(JOB_SECRET_VOLUME, JOB_SECRET_DIR, true), mount("job", JOB_DIR, false), mount("git", GIT_DIR, false))
            if (delivery.gitSecret != null) {
                volumes += secretVolume(GIT_SECRET_VOLUME, delivery.gitSecret, optional = true)
                initMounts += mount(GIT_SECRET_VOLUME, GIT_SECRET_DIR, true)
            }
            // one volume per repository that has a credential; keys are validated (JobRepositories.KEY), the
            // volume names are positional because keys need not be DNS labels
            delivery.repositorySecrets.forEachIndexed { i, repo ->
                require(JobRepositories.KEY.matches(repo.key)) { "invalid repository key ${repo.key}" }
                volumes += secretVolume("$REPO_SECRET_VOLUME_PREFIX$i", repo.secret, optional = true)
                initMounts += mount("$REPO_SECRET_VOLUME_PREFIX$i", "$REPO_SECRET_DIR/${repo.key}", true)
            }
            initContainers += bundleInitContainer(policy, initMounts)
        }
        val cacheEnv = mutableListOf<EnvVar>()
        policy.caches.forEach { cache ->
            val claim = k8s.cachePvcs[cache] ?: return@forEach
            val path = "/caches/$cache"
            volumes += VolumeBuilder().withName("cache-$cache").withNewPersistentVolumeClaim(claim, false).build()
            mounts += mount("cache-$cache", path, false)
            CACHE_ENV[cache]?.let { (name, value) -> cacheEnv += EnvVar(name, value.replace(CACHE_PATH_PLACEHOLDER, path), null) }
        }
        val env = listOf(
            EnvVar("RUNNER_JOB_DIR", JOB_DIR, null),
            EnvVar("RUNNER_GIT_TOKEN_FILE", "$GIT_DIR/token", null),
            EnvVar("RUNNER_GIT_TOKEN_DIR", GIT_DIR, null),
            EnvVar("RUNNER_WORKSPACE", WORKSPACE, null),
            EnvVar("RUNNER_MAX_PROCS", policy.pids.toString(), null),
            EnvVar("RUNNER_WAIT_FOR_JOB", if (delivery is BundleDelivery.Warm) "1" else "0", null),
            EnvVar("HOME", "$WORKSPACE/.home", null),
            EnvVar("TMPDIR", "/tmp", null),
        ) + git.identityEnv().map { (name, value) -> EnvVar(name, value, null) } + cacheEnv

        return PodSpecBuilder()
            .withRestartPolicy("Never")
            .withAutomountServiceAccountToken(false)
            .withEnableServiceLinks(false)
            .withRuntimeClassName(runtimeClassFor(policy.isolation))
            .withTerminationGracePeriodSeconds(30L)
            .withActiveDeadlineSeconds(activeDeadlineSeconds)
            .withSecurityContext(
                PodSecurityContextBuilder()
                    .withRunAsNonRoot(true)
                    .withRunAsUser(RUNNER_UID)
                    .withRunAsGroup(RUNNER_UID)
                    .withFsGroup(RUNNER_UID)
                    .withSeccompProfile(SeccompProfileBuilder().withType("RuntimeDefault").build())
                    .build(),
            )
            .withVolumes(volumes)
            .withInitContainers(initContainers)
            .withContainers(agentContainer(policy, env, mounts))
            .build()
    }

    /**
     * Copies the Secret files (symlinks resolved) into the agent's memory volumes and exits; the Secret volumes
     * are mounted only in this container, so nothing in the agent container can read them after the runner
     * deleted its copies. Same image and the same lock-down as the agent container.
     */
    private fun bundleInitContainer(policy: ResolvedPolicy, mounts: List<VolumeMount>): Container {
        val resources = mapOf("cpu" to Quantity("100m"), "memory" to Quantity("64Mi"), "ephemeral-storage" to Quantity("16Mi"))
        return ContainerBuilder()
            .withName(BUNDLE_INIT_CONTAINER)
            .withImage(policy.image)
            .withImagePullPolicy("IfNotPresent")
            .withCommand("/bin/sh", "-c", BUNDLE_COPY_SCRIPT)
            .withVolumeMounts(mounts)
            .withResources(ResourceRequirementsBuilder().withRequests<String, Quantity>(resources).withLimits<String, Quantity>(resources).build())
            .withSecurityContext(lockedDown())
            .build()
    }

    private fun lockedDown() = SecurityContextBuilder()
        .withAllowPrivilegeEscalation(false)
        .withReadOnlyRootFilesystem(true)
        .withRunAsNonRoot(true)
        .withPrivileged(false)
        .withCapabilities(Capabilities(null, listOf("ALL")))
        .withSeccompProfile(SeccompProfileBuilder().withType("RuntimeDefault").build())
        .build()

    private fun agentContainer(policy: ResolvedPolicy, env: List<EnvVar>, mounts: List<VolumeMount>): Container {
        val resources = mapOf(
            "cpu" to Quantity(policy.cpu),
            "memory" to Quantity(policy.memory),
            "ephemeral-storage" to Quantity(policy.ephemeralStorage),
        )
        return ContainerBuilder()
            .withName(AGENT_CONTAINER)
            .withImage(policy.image)
            .withImagePullPolicy("IfNotPresent")
            .withWorkingDir(WORKSPACE)
            .withEnv(env)
            .withVolumeMounts(mounts)
            .withResources(ResourceRequirementsBuilder().withRequests<String, Quantity>(resources).withLimits<String, Quantity>(resources).build())
            .withSecurityContext(lockedDown())
            .build()
    }

    /** Hash of everything that makes two sandboxes interchangeable (SandboxTemplate / warm pool identity). */
    fun templateHash(policy: ResolvedPolicy): String {
        val parts = listOf(policy.image, policy.isolation.name, runtimeClassFor(policy.isolation) ?: "", policy.cpu, policy.memory,
            policy.ephemeralStorage, policy.pids.toString(), policy.caches.sorted().joinToString(",")) +
            // only when set, so hashes of controllers without a commit identity stay unchanged
            git.identityEnv().map { "${it.key}=${it.value}" }
        val key = parts.joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }

    fun poolLabels(poolId: String): Map<String, String> = mapOf(
        Labels.NAME to "back-office-sandbox",
        Labels.MANAGED_BY to Labels.MANAGED_BY_VALUE,
        Labels.SANDBOX to "true",
        Labels.POOL to Labels.value(poolId),
    )

    private fun emptyDir(name: String, size: String, memory: Boolean): Volume =
        VolumeBuilder().withName(name).withNewEmptyDir().withMedium(if (memory) "Memory" else null).withSizeLimit(Quantity(size)).endEmptyDir().build()

    private fun secretVolume(name: String, secret: String, optional: Boolean): Volume =
        VolumeBuilder().withName(name).withNewSecret().withSecretName(secret).withDefaultMode(0x124 /* 0444 */).withOptional(optional).endSecret().build()

    private fun mount(name: String, path: String, readOnly: Boolean): VolumeMount =
        VolumeMountBuilder().withName(name).withMountPath(path).withReadOnly(readOnly).build()

    companion object {
        const val AGENT_CONTAINER = "agent"
        const val BUNDLE_INIT_CONTAINER = "bundle"
        const val JOB_DIR = "/var/run/backoffice/job"
        const val GIT_DIR = "/var/run/backoffice/git"
        const val JOB_SECRET_VOLUME = "job-secret"
        const val GIT_SECRET_VOLUME = "git-secret"
        const val JOB_SECRET_DIR = "/var/run/backoffice/job-secret"
        const val GIT_SECRET_DIR = "/var/run/backoffice/git-secret"
        const val REPO_SECRET_VOLUME_PREFIX = "git-repo-"

        /** Multi-repository jobs: the Secret of repository `<key>` is mounted (init container only) at `<dir>/<key>`. */
        const val REPO_SECRET_DIR = "/var/run/backoffice/git-repo-secrets"

        /**
         * `ready` is copied too; git Secrets are optional (no file = anonymous clone). Legacy: `git-secret/token` ->
         * `git/token`; multi-repository: `git-repo-secrets/<key>/token` -> `git/<key>/token` (only key `token`).
         */
        val BUNDLE_COPY_SCRIPT = "set -e; cp -L $JOB_SECRET_DIR/* $JOB_DIR/; " +
            "if [ -d $GIT_SECRET_DIR ]; then for f in $GIT_SECRET_DIR/*; do [ -e \"${'$'}f\" ] && cp -L \"${'$'}f\" $GIT_DIR/; done; fi; " +
            "if [ -d $REPO_SECRET_DIR ]; then for d in $REPO_SECRET_DIR/*; do k=\"${'$'}{d##*/}\"; " +
            "[ -e \"${'$'}d/token\" ] && mkdir -p \"$GIT_DIR/${'$'}k\" && cp -L \"${'$'}d/token\" \"$GIT_DIR/${'$'}k/token\"; done; fi; exit 0"
        const val WORKSPACE = "/workspace"
        const val RUNNER_UID = 10001L

        /** Replaced by the mount path of the cache in the values of [CACHE_ENV]. */
        const val CACHE_PATH_PLACEHOLDER = "{path}"

        /** Cache name -> environment variable that points the tool at the mounted cache. */
        val CACHE_ENV = mapOf(
            "gradle" to ("GRADLE_USER_HOME" to CACHE_PATH_PLACEHOLDER),
            "maven" to ("MAVEN_OPTS" to "-Dmaven.repo.local=$CACHE_PATH_PLACEHOLDER"),
            "npm" to ("npm_config_cache" to CACHE_PATH_PLACEHOLDER),
            "pnpm" to ("npm_config_store_dir" to CACHE_PATH_PLACEHOLDER),
            "pip" to ("PIP_CACHE_DIR" to CACHE_PATH_PLACEHOLDER),
            "cargo" to ("CARGO_HOME" to CACHE_PATH_PLACEHOLDER),
            "go" to ("GOMODCACHE" to CACHE_PATH_PLACEHOLDER),
        )
    }
}
