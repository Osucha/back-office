package pl.mos.backoffice.runnercontroller.sandbox.docker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.git.GitAccess
import pl.mos.backoffice.runnercontroller.git.RepositoryToken
import pl.mos.backoffice.runnercontroller.sandbox.TestPolicies
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

class DockerBackendTest {
    @TempDir
    lateinit var dir: Path

    /** Records the docker CLI calls instead of running them. */
    private class RecordingDocker : DockerCli() {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>, check: Boolean): DockerResult {
            calls += args
            return DockerResult(0, "", "")
        }
    }

    private fun launch(
        git: ControllerProperties.Git,
        env: Map<String, String>,
        access: GitAccess = GitAccess.PUSH_MR,
    ): Pair<List<String>, Path> {
        val docker = RecordingDocker()
        val props = ControllerProperties(git = git, docker = ControllerProperties.Docker(workDir = dir.toString()))
        val backend = DockerBackend(props, docker, JsonMapper.builder().build(), env::get)
        // what JobSupervisor does: select by repository and level, then launch with the selected variable
        val selected = backend.gitCredentials.select("https://gitlab.example.com/g/app.git", access)
        val request = TestPolicies.launch(gitAccess = selected.access, gitCredential = selected.credential)
        backend.launch(request)
        return docker.calls.single { it.firstOrNull() == "run" && "bo-job-${request.jobId}" in it } to dir.resolve(request.jobId).resolve("git").resolve("token")
    }

    private fun envOf(args: List<String>): Map<String, String> =
        args.zipWithNext().filter { it.first == "-e" }.associate { it.second.substringBefore('=') to it.second.substringAfter('=') }

    @Test
    fun `legacy BO_GIT_TOKEN (push fallback) and commit identity reach a PUSH_MR agent container`() {
        val (args, tokenFile) = launch(ControllerProperties.Git(userName = "BO Bot", userEmail = "bot@example.com"), mapOf("BO_GIT_TOKEN" to "glpat-x"))
        val env = envOf(args)
        assertThat(env).containsEntry("GIT_USER_NAME", "BO Bot").containsEntry("GIT_USER_EMAIL", "bot@example.com")
            .containsEntry("RUNNER_GIT_TOKEN_FILE", "/var/run/backoffice/git/token")
        // the token is a mounted file, never an environment variable
        assertThat(env.keys).doesNotContain("GIT_TOKEN", "BO_GIT_TOKEN")
        assertThat(Files.readString(tokenFile)).isEqualTo("glpat-x")
    }

    @Test
    fun `least privilege - READ jobs get only BO_GIT_TOKEN_READ, PUSH_MR jobs only BO_GIT_TOKEN_PUSH, NONE nothing`() {
        val env = mapOf("BO_GIT_TOKEN_READ" to "glpat-read", "BO_GIT_TOKEN_PUSH" to "glpat-push", "BO_GIT_TOKEN" to "glpat-legacy")
        val (readArgs, readToken) = launch(ControllerProperties.Git(), env, GitAccess.READ)
        assertThat(Files.readString(readToken)).isEqualTo("glpat-read")
        assertThat(readArgs.joinToString(" ")).doesNotContain("glpat")
        Files.delete(readToken)

        val (_, pushToken) = launch(ControllerProperties.Git(), env, GitAccess.PUSH_MR)
        assertThat(Files.readString(pushToken)).isEqualTo("glpat-push")
        Files.delete(pushToken)

        val (_, noneToken) = launch(ControllerProperties.Git(), env, GitAccess.NONE)
        assertThat(noneToken).doesNotExist()
    }

    @Test
    fun `multi-repository job - each repository gets the variable of its own level, NONE and anonymous nothing`() {
        val env = mapOf("BO_GIT_TOKEN_READ" to "glpat-read", "BO_GIT_TOKEN_PUSH" to "glpat-push")
        val docker = RecordingDocker()
        val props = ControllerProperties(docker = ControllerProperties.Docker(workDir = dir.toString()))
        val backend = DockerBackend(props, docker, JsonMapper.builder().build(), env::get)
        val spec = JsonMapper.builder().build().readTree(
            """{"repositories":[
                {"key":"docs","url":"https://docs.example.org/shop/docs.git","access":"READ","path":"docs"},
                {"key":"src","url":"https://gitlab.example.com/shop/api.git","access":"PUSH_MR","path":"src","pushBranch":"f"},
                {"key":"tools","url":"https://gitlab.example.com/shop/tools.git","access":"NONE","path":"tools"}]}""",
        )
        // what JobSupervisor does: plan per repository, then launch with the selected variables
        val plan = backend.gitCredentials.plan(spec)
        assertThat(plan.tokens).containsExactly(
            RepositoryToken("docs", GitAccess.READ, "BO_GIT_TOKEN_READ"),
            RepositoryToken("src", GitAccess.PUSH_MR, "BO_GIT_TOKEN_PUSH"),
        )
        val request = TestPolicies.launch(gitAccess = plan.access, gitCredential = plan.legacy.credential, repositoryTokens = plan.tokens)
        backend.launch(request)
        val gitDir = dir.resolve(request.jobId).resolve("git")
        assertThat(Files.readString(gitDir.resolve("docs/token"))).isEqualTo("glpat-read")
        assertThat(Files.readString(gitDir.resolve("src/token"))).isEqualTo("glpat-push")
        assertThat(gitDir.resolve("tools")).doesNotExist()
        assertThat(gitDir.resolve("token")).doesNotExist()
        val args = docker.calls.single { it.firstOrNull() == "run" && "bo-job-${request.jobId}" in it }
        assertThat(envOf(args)).containsEntry("RUNNER_GIT_TOKEN_DIR", "/var/run/backoffice/git")
        assertThat(args.joinToString(" ")).doesNotContain("glpat")
    }

    @Test
    fun `without a commit identity the runner default applies`() {
        val (args, tokenFile) = launch(ControllerProperties.Git(), emptyMap())
        assertThat(envOf(args).keys).doesNotContain("GIT_USER_NAME", "GIT_USER_EMAIL")
        assertThat(tokenFile).doesNotExist()
    }
}
