package pl.mos.backoffice.runnercontroller.sandbox.docker

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** ADR 0065: the CLI wrapper reads both pipes on threads of its own and gives `inspect` a shorter time limit. */
class DockerCliTest {

    /** The JVM running the tests stands in for `docker`: it writes to stdout and stderr and exits. */
    private val java = ProcessHandle.current().info().command().orElse("java")

    @Test
    fun `inspect gets the shorter time limit, never more than the general one`() {
        val cli = DockerCli(timeoutSeconds = 120, inspectTimeoutSeconds = 30)
        assertThat(cli.timeoutFor(listOf("inspect", "--format", "x", "bo-job"))).isEqualTo(30)
        assertThat(cli.timeoutFor(listOf("logs", "bo-job"))).isEqualTo(120)
        assertThat(cli.timeoutFor(emptyList())).isEqualTo(120)
        assertThat(DockerCli(timeoutSeconds = 10, inspectTimeoutSeconds = 30).timeoutFor(listOf("inspect"))).isEqualTo(10)
    }

    @Test
    fun `stdout and stderr of many concurrent calls are read completely`() {
        val cli = DockerCli(executable = java)
        val results = (1..8).map { Thread.ofPlatform().start { cli.run(listOf("-version"), check = false) } }
        results.forEach { it.join(60_000) }
        val result = cli.run(listOf("-version"), check = false)
        assertThat(result.exitCode).isZero()
        // java -version writes to stderr
        assertThat(result.stderr).containsIgnoringCase("version")
    }

    @Test
    fun `a failing call is reported with its stderr`() {
        val cli = DockerCli(executable = java)
        assertThatThrownBy { cli.run(listOf("--no-such-option")) }
            .isInstanceOf(DockerException::class.java)
            .hasMessageContaining("exited")
    }
}
