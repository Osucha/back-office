package pl.mos.backoffice.runnercontroller.sandbox.docker

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DockerException(message: String) : RuntimeException(message)

data class DockerResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs the `docker` CLI (arguments passed as a list, never through a shell). */
open class DockerCli(private val executable: String = "docker", private val timeoutSeconds: Long = 120) {

    open fun run(args: List<String>, check: Boolean = true): DockerResult {
        val process = ProcessBuilder(listOf(executable) + args).redirectInput(ProcessBuilder.Redirect.PIPE).start()
        process.outputStream.close()
        val out = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8) }
        val err = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw DockerException("docker ${args.firstOrNull()} timed out after ${timeoutSeconds}s")
        }
        val result = DockerResult(process.exitValue(), out.get(10, TimeUnit.SECONDS), err.get(10, TimeUnit.SECONDS))
        if (check && result.exitCode != 0) {
            throw DockerException("docker ${args.take(2).joinToString(" ")} exited ${result.exitCode}: ${result.stderr.trim().take(1000)}")
        }
        return result
    }

    fun available(): Boolean = runCatching { run(listOf("version", "--format", "{{.Server.Version}}"), check = false).exitCode == 0 }.getOrDefault(false)
}
