package pl.mos.backoffice.runnercontroller.sandbox.k8s

import io.fabric8.kubernetes.api.model.Quantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.policy.Isolation
import pl.mos.backoffice.runnercontroller.sandbox.TestPolicies

class PodSpecRendererTest {
    private val renderer = PodSpecRenderer(ControllerProperties.Kubernetes(cachePvcs = mapOf("gradle" to "cache-gradle")))

    @Test
    fun `cold pod is locked down per doc 10`() {
        val spec = renderer.podSpec(TestPolicies.resolved(caches = listOf("gradle", "npm")), BundleDelivery.Secrets("bo-job-1", "git-gitlab"), 1920)
        assertThat(spec.restartPolicy).isEqualTo("Never")
        assertThat(spec.automountServiceAccountToken).isFalse()
        assertThat(spec.enableServiceLinks).isFalse()
        assertThat(spec.runtimeClassName).isEqualTo("gvisor")
        assertThat(spec.activeDeadlineSeconds).isEqualTo(1920L)
        // the Secrets are mounted only in the init container that copies them into memory volumes (ADR 0029)
        val init = spec.initContainers.single()
        assertThat(init.name).isEqualTo("bundle")
        assertThat(init.securityContext.readOnlyRootFilesystem).isTrue()
        assertThat(init.securityContext.capabilities.drop).containsExactly("ALL")
        assertThat(init.volumeMounts.associate { it.mountPath to it.readOnly })
            .containsEntry("/var/run/backoffice/job-secret", true).containsEntry("/var/run/backoffice/git-secret", true)
            .containsEntry("/var/run/backoffice/job", false).containsEntry("/var/run/backoffice/git", false)
        with(spec.securityContext) {
            assertThat(runAsNonRoot).isTrue()
            assertThat(runAsUser).isEqualTo(10001L)
            assertThat(fsGroup).isEqualTo(10001L)
            assertThat(seccompProfile.type).isEqualTo("RuntimeDefault")
        }
        val agent = spec.containers.single()
        assertThat(agent.name).isEqualTo("agent")
        with(agent.securityContext) {
            assertThat(readOnlyRootFilesystem).isTrue()
            assertThat(allowPrivilegeEscalation).isFalse()
            assertThat(privileged).isFalse()
            assertThat(capabilities.drop).containsExactly("ALL")
            assertThat(capabilities.add).isNullOrEmpty()
        }
        assertThat(agent.resources.limits).containsEntry("cpu", Quantity("2")).containsEntry("memory", Quantity("4Gi"))
            .containsEntry("ephemeral-storage", Quantity("10Gi"))
        assertThat(agent.resources.requests).isEqualTo(agent.resources.limits)
        val env = agent.env.associate { it.name to it.value }
        assertThat(env).containsEntry("RUNNER_JOB_DIR", "/var/run/backoffice/job").containsEntry("RUNNER_MAX_PROCS", "1024")
            .containsEntry("RUNNER_WAIT_FOR_JOB", "0").containsEntry("GRADLE_USER_HOME", "/caches/gradle")
        assertThat(env.keys).noneMatch { it.contains("API_KEY") || it == "RUNNER_CALLBACK_TOKEN" || it == "GIT_TOKEN" }
        val mounts = agent.volumeMounts.associate { it.mountPath to it.readOnly }
        assertThat(mounts).containsEntry("/workspace", false).containsEntry("/tmp", false)
            .containsEntry("/var/run/backoffice/job", false).containsEntry("/var/run/backoffice/git", false).containsEntry("/caches/gradle", false)
        assertThat(mounts).doesNotContainKey("/caches/npm") // no PVC configured for npm
        assertThat(mounts).doesNotContainKeys("/var/run/backoffice/job-secret", "/var/run/backoffice/git-secret")
        val volumes = spec.volumes.associateBy { it.name }
        assertThat(volumes["job-secret"]!!.secret.secretName).isEqualTo("bo-job-1")
        assertThat(volumes["job-secret"]!!.secret.defaultMode).isEqualTo(292)
        assertThat(volumes["git-secret"]!!.secret.secretName).isEqualTo("git-gitlab")
        assertThat(volumes["job"]!!.emptyDir.medium).isEqualTo("Memory")
        assertThat(volumes["git"]!!.emptyDir.medium).isEqualTo("Memory")
        assertThat(volumes["workspace"]!!.emptyDir.sizeLimit).isEqualTo(Quantity("10Gi"))
        assertThat(volumes["cache-gradle"]!!.persistentVolumeClaim.claimName).isEqualTo("cache-gradle")
    }

    @Test
    fun `multi-repository cold pod mounts only the selected per-repository Secrets, in the init container`() {
        val delivery = BundleDelivery.Secrets(
            "bo-job-1", null,
            listOf(BundleDelivery.RepositorySecret("docs", "git-docs-read"), BundleDelivery.RepositorySecret("src", "git-src-push")),
        )
        val spec = renderer.podSpec(TestPolicies.resolved(), delivery, 1920)
        val volumes = spec.volumes.associateBy { it.name }
        assertThat(volumes["git-repo-0"]!!.secret.secretName).isEqualTo("git-docs-read")
        assertThat(volumes["git-repo-1"]!!.secret.secretName).isEqualTo("git-src-push")
        assertThat(volumes.keys).doesNotContain("git-secret", "git-repo-2")
        assertThat(spec.volumes.mapNotNull { it.secret?.secretName }).containsExactly("bo-job-1", "git-docs-read", "git-src-push")
        val initMounts = spec.initContainers.single().volumeMounts.associate { it.mountPath to it.name }
        assertThat(initMounts).containsEntry("/var/run/backoffice/git-repo-secrets/docs", "git-repo-0")
            .containsEntry("/var/run/backoffice/git-repo-secrets/src", "git-repo-1")
        val agent = spec.containers.single()
        assertThat(agent.volumeMounts.map { it.name }).noneMatch { it.startsWith("git-repo-") || it == "git-secret" }
        assertThat(agent.env.associate { it.name to it.value }).containsEntry("RUNNER_GIT_TOKEN_DIR", "/var/run/backoffice/git")
            .containsEntry("RUNNER_GIT_TOKEN_FILE", "/var/run/backoffice/git/token")
        assertThatThrownBy {
            renderer.podSpec(TestPolicies.resolved(), BundleDelivery.Secrets("bo-job-1", null, listOf(BundleDelivery.RepositorySecret("../x", "s"))), 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the bundle copy script puts each repository token at git-key-token (run with sh when available)`(@TempDir dir: Path) {
        val sh = listOf("sh", "/bin/sh").firstOrNull { runCatching { ProcessBuilder(it, "-c", "true").start().waitFor() == 0 }.getOrDefault(false) }
        assumeTrue(sh != null, "no sh on this machine")
        val root = dir.toString().replace('\\', '/')
        fun p(rel: String) = dir.resolve(rel.removePrefix("/var/run/backoffice/"))
        Files.createDirectories(p(PodSpecRenderer.JOB_SECRET_DIR))
        Files.writeString(p(PodSpecRenderer.JOB_SECRET_DIR).resolve("spec.json"), "{}")
        Files.createDirectories(p(PodSpecRenderer.JOB_DIR))
        Files.createDirectories(p(PodSpecRenderer.GIT_DIR))
        // ADR 0051: project tokens arrive in the job Secret and move to the git volume (they win over a mounted Secret)
        Files.writeString(p(PodSpecRenderer.JOB_SECRET_DIR).resolve("scm-repo-api"), "tkn-project")
        for ((key, token) in listOf("docs" to "glpat-docs", "src" to "glpat-src")) {
            Files.createDirectories(p("${PodSpecRenderer.REPO_SECRET_DIR}/$key"))
            Files.writeString(p("${PodSpecRenderer.REPO_SECRET_DIR}/$key/token"), token)
        }
        Files.createDirectories(p("${PodSpecRenderer.REPO_SECRET_DIR}/empty"))
        // via a file: Windows argument quoting would mangle the script's embedded quotes when passed with -c
        val script = Files.writeString(dir.resolve("copy.sh"), PodSpecRenderer.BUNDLE_COPY_SCRIPT.replace("/var/run/backoffice", root))
        val process = ProcessBuilder(sh!!, script.toString().replace('\\', '/')).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertThat(process.waitFor()).describedAs(output).isEqualTo(0)
        assertThat(Files.readString(p(PodSpecRenderer.GIT_DIR).resolve("docs/token"))).isEqualTo("glpat-docs")
        assertThat(Files.readString(p(PodSpecRenderer.GIT_DIR).resolve("src/token"))).isEqualTo("glpat-src")
        assertThat(p(PodSpecRenderer.GIT_DIR).resolve("token")).doesNotExist()
        assertThat(p(PodSpecRenderer.GIT_DIR).resolve("empty")).doesNotExist()
        assertThat(p(PodSpecRenderer.JOB_DIR).resolve("spec.json")).exists()
        assertThat(Files.readString(p(PodSpecRenderer.GIT_DIR).resolve("api/token"))).isEqualTo("tkn-project")
        assertThat(p(PodSpecRenderer.JOB_DIR).resolve("scm-repo-api")).doesNotExist()
    }

    @Test
    fun `warm pod waits for the bundle in memory volumes and has no deadline`() {
        val spec = renderer.podSpec(TestPolicies.resolved(Isolation.HIGH), BundleDelivery.Warm, null)
        assertThat(spec.runtimeClassName).isEqualTo("kata")
        assertThat(spec.activeDeadlineSeconds as Long?).isNull()
        val volumes = spec.volumes.associateBy { it.name }
        assertThat(volumes["job"]!!.emptyDir.medium).isEqualTo("Memory")
        assertThat(volumes["git"]!!.emptyDir.medium).isEqualTo("Memory")
        assertThat(spec.containers.single().env.first { it.name == "RUNNER_WAIT_FOR_JOB" }.value).isEqualTo("1")
    }

    @Test
    fun `commit identity reaches the sandbox env only when configured`() {
        val env = { r: PodSpecRenderer -> r.podSpec(TestPolicies.resolved(), BundleDelivery.Warm, null).containers.single().env.associate { it.name to it.value } }
        assertThat(env(renderer).keys).doesNotContain("GIT_USER_NAME", "GIT_USER_EMAIL")
        val withIdentity = PodSpecRenderer(ControllerProperties.Kubernetes(), ControllerProperties.Git(userName = "BO Bot", userEmail = "bot@example.com"))
        assertThat(env(withIdentity)).containsEntry("GIT_USER_NAME", "BO Bot").containsEntry("GIT_USER_EMAIL", "bot@example.com")
        // warm sandboxes of another identity are not interchangeable; without one the hash is unchanged
        assertThat(withIdentity.templateHash(TestPolicies.resolved())).isNotEqualTo(renderer.templateHash(TestPolicies.resolved()))
        assertThat(PodSpecRenderer(ControllerProperties.Kubernetes()).templateHash(TestPolicies.resolved())).isEqualTo(renderer.templateHash(TestPolicies.resolved()))
    }

    @Test
    fun `runtime classes are configurable and template hash is stable`() {
        val custom = PodSpecRenderer(ControllerProperties.Kubernetes(runtimeClasses = mapOf("STANDARD" to "", "HIGH" to "kata-qemu")))
        assertThat(custom.runtimeClassFor(Isolation.STANDARD)).isNull()
        assertThat(custom.runtimeClassFor(Isolation.HIGH)).isEqualTo("kata-qemu")
        val a = renderer.templateHash(TestPolicies.resolved())
        assertThat(renderer.templateHash(TestPolicies.resolved())).isEqualTo(a).hasSize(12)
        assertThat(renderer.templateHash(TestPolicies.resolved(Isolation.HIGH))).isNotEqualTo(a)
        assertThat(renderer.templateHash(TestPolicies.resolved(image = "back-office/runner:2"))).isNotEqualTo(a)
    }
}
