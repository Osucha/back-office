package pl.mos.backoffice.runnercontroller.e2e

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import pl.mos.backoffice.runnercontroller.config.BackendChoice
import pl.mos.backoffice.runnercontroller.config.ControllerProperties
import pl.mos.backoffice.runnercontroller.egress.EgressRegistry
import pl.mos.backoffice.runnercontroller.engine.JobSupervisor
import pl.mos.backoffice.runnercontroller.engine.RunnerRegistrar
import pl.mos.backoffice.runnercontroller.engine.RunnerTokenHolder
import pl.mos.backoffice.runnercontroller.fake.FakeBackOffice
import pl.mos.backoffice.runnercontroller.fake.strings
import pl.mos.backoffice.runnercontroller.policy.PolicyDefaults
import pl.mos.backoffice.runnercontroller.policy.PolicyResolver
import pl.mos.backoffice.runnercontroller.protocol.BackOfficeClient
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.protocol.FileCredentialStore
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson
import pl.mos.backoffice.runnercontroller.protocol.RunnerCapabilities
import pl.mos.backoffice.runnercontroller.sandbox.BackendSelector
import pl.mos.backoffice.runnercontroller.sandbox.CapabilityDetector
import pl.mos.backoffice.runnercontroller.sandbox.docker.DockerBackend
import pl.mos.backoffice.runnercontroller.sandbox.docker.DockerCli
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Local end-to-end run of the DOCKER backend against a fake Back Office:
 * register -> config -> lease a signed assignment -> per-job internal network + egress proxy + runner
 * container (real `back-office/runner:dev` image, STUB agent) -> the stub calls the fake LLM gateway through
 * the proxy with the job token, tries a non-allowlisted host (refused by the proxy) and a direct connection
 * (no route) -> events/result/upload posted with the job token -> completion with egress evidence.
 *
 * Needs `docker build -t back-office/runner:dev runner/` and
 * `docker build -t back-office/egress-proxy:dev backend/runner-controller/egress-proxy`; skipped otherwise
 * (fails instead with `-Pe2e`).
 */
@Tag("e2e")
class DockerEndToEndTest {
    private val log = LoggerFactory.getLogger(javaClass)
    private val required = System.getProperty("runnerController.e2e.required") == "true"
    private val runnerImage = System.getProperty("runnerController.e2e.runnerImage") ?: "back-office/runner:dev"
    private val proxyImage = System.getProperty("runnerController.e2e.egressProxyImage") ?: "back-office/egress-proxy:dev"
    private val docker = DockerCli()

    private fun precondition(ok: Boolean, what: String) {
        if (required) check(ok) { what } else assumeTrue(ok, what)
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun `docker backend runs a sandboxed job end to end`() {
        precondition(docker.available(), "docker is not available")
        precondition(docker.run(listOf("image", "inspect", runnerImage), check = false).exitCode == 0, "image $runnerImage missing")
        precondition(docker.run(listOf("image", "inspect", proxyImage), check = false).exitCode == 0, "image $proxyImage missing")

        // The address sandboxes use for host.docker.internal: the proxy must be allowed to reach this private IP.
        val hostIp = docker.run(
            listOf("run", "--rm", "--add-host", "host.docker.internal:host-gateway", "--entrypoint", "getent", runnerImage, "ahostsv4", "host.docker.internal"),
        ).stdout.trim().lineSequence().first().substringBefore(' ')
        log.info("host.docker.internal = {}", hostIp)

        FakeBackOffice().use { bo ->
            bo.publicHost = "host.docker.internal"
            val json = ProtocolJson.mapper()
            val workDir = Files.createTempDirectory("bo-rc-e2e")
            val props = ControllerProperties(
                backend = BackendChoice.DOCKER,
                maxConcurrent = 1,
                heartbeatSeconds = 2,
                monitorIntervalMillis = 500,
                lease = ControllerProperties.Lease(waitSeconds = 1),
                egress = ControllerProperties.Egress(proxyImage = proxyImage, allowRanges = listOf("$hostIp/32")),
                docker = ControllerProperties.Docker(workDir = workDir.resolve("jobs").toString(), extraEnv = mapOf("RUNNER_ENABLE_STUB_AGENT" to "1")),
                defaults = ControllerProperties.Defaults(image = runnerImage),
            )
            val caps = CapabilityDetector(null, docker::available).detect(null)
            assertThat(BackendSelector.select(BackendChoice.AUTO, caps).kind).isEqualTo(BackendKind.DOCKER)

            val holder = RunnerTokenHolder()
            val client = BackOfficeClient(bo.url, json, holder::token)
            RunnerRegistrar(client, FileCredentialStore(workDir.resolve("credentials.json"), json), holder)
                .ensureRegistered(bo.registrationToken, "e2e", "dev", "docker-desktop", RunnerCapabilities(BackendSelector.offered(caps), emptyList(), 1))
            val meters = SimpleMeterRegistry()
            val supervisor = JobSupervisor(
                props, client, DockerBackend(props, docker, json), EgressRegistry(), PolicyResolver(PolicyDefaults(image = runnerImage)), json, meters,
            )

            val llm = "${bo.publicUrl}/api/v1/llm/anthropic/v1/messages"
            val stub = """
                echo "stub agent started as uid ${'$'}(id -u)"
                code=${'$'}(curl -sS -o /tmp/llm.json -w '%{http_code}' -X POST "$llm" -H "x-api-key: ${'$'}BACKOFFICE_JOB_TOKEN" -H 'content-type: application/json' -d '{"model":"fake","max_tokens":16,"messages":[{"role":"user","content":"hi"}]}')
                echo "LLM_STATUS=${'$'}code ${'$'}(cat /tmp/llm.json)"
                curl -sS -o /dev/null --max-time 20 https://example.com/ ; echo "DENIED_HTTPS_EXIT=${'$'}?"
                echo "DENIED_HTTP_CODE=${'$'}(curl -s -o /dev/null -w '%{http_code}' --max-time 20 http://example.com/)"
                curl -sS -o /dev/null --noproxy '*' --max-time 5 https://1.1.1.1/ ; echo "DIRECT_EXIT=${'$'}?"
                touch /should-fail 2>/dev/null; echo "ROOTFS_WRITE_EXIT=${'$'}?"
                echo "PROVIDER_KEYS=${'$'}{ANTHROPIC_API_KEY:-none}"
                sleep 2
                echo done > stub.txt
            """.trimIndent()
            val spec = mapOf(
                "mode" to "EVAL_CODING",
                "agent" to "STUB",
                "model" to mapOf("provider" to "ANTHROPIC", "modelId" to "claude-opus-5"),
                "prompt" to stub,
                "timeoutSeconds" to 600,
                "collectArtifacts" to listOf("stub.txt"),
                "gateway" to bo.gateway(),
            )
            val policy = mapOf("isolation" to "DEV", "resources" to mapOf("cpu" to "1", "memory" to "1Gi", "ephemeralStorage" to "1Gi", "pids" to 512))
            val jobId = bo.enqueue(spec, policy)

            supervisor.start()
            val completion = try {
                bo.awaitCompletion(jobId, Duration.ofMinutes(6).toMillis())
            } finally {
                supervisor.shutdown(Duration.ofSeconds(5))
            }
            val messages = bo.events.filter { it.first == jobId }.map { it.second.get("message").asString() }
            log.info("runner events:\n{}", messages.joinToString("\n"))
            assertThat(completion).describedAs("completion of $jobId; events: $messages").isNotNull()

            // sandbox started and finished normally
            assertThat(completion!!.get("status").asString()).isEqualTo("SUCCEEDED")
            assertThat(completion.get("exitCode").asInt()).isEqualTo(0)
            assertThat(completion.get("imageDigest").asString()).startsWith("sha256:")
            assertThat(meters.find("backoffice.sandbox.start").timer()!!.count()).isEqualTo(1)

            // callbacks with the job token (FakeBackOffice rejects anything else)
            val token = bo.jobTokens.getValue(jobId)
            assertThat(bo.calls.filter { it.path.contains("/internal/runner/jobs/$jobId/") }).isNotEmpty.allMatch { it.authorization == "Bearer $token" }
            val result = bo.results.single { it.first == jobId }.second
            assertThat(result.get("status").asString()).isEqualTo("SUCCEEDED")
            assertThat(result.get("artifacts").strings("name")).contains("stub.txt", "runner.log")
            assertThat(result.get("digests").get("logSha256").asString()).hasSize(64)
            assertThat(bo.uploads.map { it.first }).contains("jobs/$jobId/runner.log")

            // LLM through the gateway with the job token, via the proxy
            assertThat(messages).anyMatch { it.startsWith("LLM_STATUS=200") && it.contains("hello from the fake gateway") }
            assertThat(bo.llmCalls).isNotEmpty.allMatch { it.headers["x-api-key"] == token }

            // egress: non-allowlisted host refused by the proxy, no direct route, read-only rootfs, no provider keys
            assertThat(messages).anyMatch { it.startsWith("DENIED_HTTPS_EXIT=") && it != "DENIED_HTTPS_EXIT=0" }
            assertThat(messages).anyMatch { it == "DENIED_HTTP_CODE=403" || it == "DENIED_HTTP_CODE=407" } // Smokescreen refuses with 407
            assertThat(messages).anyMatch { it.startsWith("DIRECT_EXIT=") && it != "DIRECT_EXIT=0" }
            assertThat(messages).anyMatch { it.startsWith("ROOTFS_WRITE_EXIT=") && it != "ROOTFS_WRITE_EXIT=0" }
            assertThat(messages).contains("PROVIDER_KEYS=none")
            assertThat(completion.get("egressDenied").strings()).contains("example.com")
            assertThat(completion.get("egressLogDigest").asString()).startsWith("sha256:")

            // everything cleaned up
            val leftovers = docker.run(listOf("ps", "-a", "--filter", "label=backoffice.io/job-id=$jobId", "--format", "{{.Names}}"), check = false).stdout.trim()
            assertThat(leftovers).isEmpty()
            assertThat(docker.run(listOf("network", "inspect", "bo-job-$jobId"), check = false).exitCode).isNotEqualTo(0)
        }
    }
}
