package pl.mos.backoffice.runnercontroller.sandbox.docker

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DockerException(message: String) : RuntimeException(message)

data class DockerResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs the `docker` CLI (arguments passed as a list, never through a shell).
 *
 * ADR 0065: stdout and stderr are read on dedicated daemon threads per call, not on the common fork-join pool, whose
 * few threads concurrent calls (monitor, watchdog, lease loop) could exhaust, leaving a process blocked on a full pipe
 * until the timeout; `inspect`, called for every running job on every monitor pass, has a shorter timeout.
 */
open class DockerCli(
    private val executable: String = "docker",
    private val timeoutSeconds: Long = 120,
    private val inspectTimeoutSeconds: Long = 30,
) {

    open fun run(args: List<String>, check: Boolean = true): DockerResult {
        val timeout = timeoutFor(args)
        val process = ProcessBuilder(listOf(executable) + args).redirectInput(ProcessBuilder.Redirect.PIPE).start()
        process.outputStream.close()
        val command = args.firstOrNull() ?: "docker"
        val out = drain(process.inputStream, "docker-$command-out")
        val err = drain(process.errorStream, "docker-$command-err")
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw DockerException("docker $command timed out after ${timeout}s")
        }
        val result = DockerResult(process.exitValue(), out.await(), err.await())
        if (check && result.exitCode != 0) {
            throw DockerException("docker ${args.take(2).joinToString(" ")} exited ${result.exitCode}: ${result.stderr.trim().take(1000)}")
        }
        return result
    }

    /** The time limit of one call: [inspectTimeoutSeconds] for `inspect` (never more than [timeoutSeconds]), else [timeoutSeconds]. */
    fun timeoutFor(args: List<String>): Long =
        if (args.firstOrNull() == "inspect") minOf(inspectTimeoutSeconds, timeoutSeconds) else timeoutSeconds

    fun available(): Boolean = runCatching { run(listOf("version", "--format", "{{.Server.Version}}"), check = false).exitCode == 0 }.getOrDefault(false)

    /** A stream read to its end on a daemon thread of its own. */
    private class Drain(private val thread: Thread, private val text: AtomicReference<String>) {
        fun await(): String {
            thread.join(TimeUnit.SECONDS.toMillis(DRAIN_WAIT_SECONDS))
            return text.get()
        }
    }

    private fun drain(stream: InputStream, name: String): Drain {
        val text = AtomicReference("")
        val thread = Thread.ofPlatform().name(name).daemon(true).start {
            try {
                stream.use { text.set(it.readAllBytes().toString(StandardCharsets.UTF_8)) }
            } catch (_: IOException) {
                // the process was destroyed (timeout): what was read so far does not matter
            }
        }
        return Drain(thread, text)
    }

    private companion object {
        /** After the process exited its pipes close; the readers then finish at once. */
        const val DRAIN_WAIT_SECONDS = 10L
    }
}
