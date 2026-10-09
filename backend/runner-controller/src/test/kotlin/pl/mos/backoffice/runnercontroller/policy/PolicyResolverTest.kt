package pl.mos.backoffice.runnercontroller.policy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.protocol.BackendKind
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson

class PolicyResolverTest {
    private val json = ProtocolJson.mapper()
    private val resolver = PolicyResolver(PolicyDefaults(image = "ghcr.io/org/runner:2.0"), alwaysAllowHosts = listOf("minio.example.com"))
    private val gateway = listOf("https://bo.example.com/api/v1/llm/anthropic", "https://bo.example.com/api/v1/llm/openai/v1", "https://bo.example.com/api/v1")

    private fun policy(s: String) = SandboxPolicy.from(json.readTree(s))

    private fun resolve(requested: String, ceiling: String, backend: BackendKind = BackendKind.K8S_JOB, images: List<String> = listOf("ghcr.io/org/runner:*")) =
        resolver.resolve(policy(requested), policy(ceiling), images, backend, 1800, listOf("https://gitlab.example.com/group/app.git"), gateway)

    @Test
    fun `parses the contract shape`() {
        val p = policy(
            """{"isolation":"HIGH","resources":{"cpu":"2","memory":"4Gi","ephemeralStorage":"10Gi","pids":2048},"timeoutSeconds":3600,
               "network":{"allow":["registry.npmjs.org"]},"tools":{"allow":["git *"],"deny":["curl *"],"ask":["rm -rf *"]},
               "secrets":["npm-read-token"],"environment":{"image":"img:1","setupCommand":"x","caches":["gradle","npm"]},"unknown":1}""",
        )
        assertThat(p).isEqualTo(
            SandboxPolicy(Isolation.HIGH, "2", "4Gi", "10Gi", 2048, 3600, listOf("registry.npmjs.org"), listOf("git *"), listOf("curl *"),
                listOf("rm -rf *"), listOf("npm-read-token"), "img:1", "x", listOf("gradle", "npm")),
        )
    }

    @Test
    fun `clamps resources and timeout to the ceiling and fills defaults`() {
        val r = resolve(
            """{"resources":{"cpu":"8","memory":"2Gi"},"timeoutSeconds":7200}""",
            """{"resources":{"cpu":"4","memory":"8Gi","ephemeralStorage":"5Gi","pids":512},"timeoutSeconds":3600}""",
        )
        assertThat(r.cpu).isEqualTo("4")
        assertThat(r.memory).isEqualTo("2Gi")
        assertThat(r.ephemeralStorage).isEqualTo("5Gi")
        assertThat(r.pids).isEqualTo(512)
        assertThat(r.timeoutSeconds).isEqualTo(3600)
        assertThat(r.image).isEqualTo("ghcr.io/org/runner:2.0")
        assertThat(r.notes).anyMatch { it.startsWith("cpu 8 exceeds") }
    }

    @Test
    fun `spec timeout is used when the policy has none, and milli-cpu compares numerically`() {
        val r = resolve("""{"resources":{"cpu":"500m"}}""", """{"resources":{"cpu":"1"}}""")
        assertThat(r.timeoutSeconds).isEqualTo(1800)
        assertThat(r.cpu).isEqualTo("500m")
    }

    @Test
    fun `isolation only gets stricter and DEV is raised outside docker`() {
        assertThat(resolve("""{"isolation":"STANDARD"}""", """{"isolation":"HIGH"}""").isolation).isEqualTo(Isolation.HIGH)
        assertThat(resolve("""{"isolation":"HIGH"}""", """{"isolation":"STANDARD"}""").isolation).isEqualTo(Isolation.HIGH)
        val dev = resolve("""{"isolation":"DEV"}""", "{}")
        assertThat(dev.isolation).isEqualTo(Isolation.STANDARD)
        assertThat(resolve("""{"isolation":"DEV"}""", "{}", BackendKind.DOCKER).isolation).isEqualTo(Isolation.DEV)
    }

    @Test
    fun `image outside allowedImages is a policy violation`() {
        assertThatThrownBy { resolve("""{"environment":{"image":"evil/miner:latest"}}""", "{}") }
            .isInstanceOf(PolicyViolation::class.java).hasMessageContaining("allowedImages")
        assertThatThrownBy { PolicyResolver(PolicyDefaults()).resolve(SandboxPolicy.EMPTY, SandboxPolicy.EMPTY, emptyList(), BackendKind.K8S_JOB, null, emptyList(), emptyList()) }
            .isInstanceOf(PolicyViolation::class.java).hasMessageContaining("no image")
    }

    @Test
    fun `egress = gateway + git host + always-allow + requested hosts within the ceiling`() {
        val r = resolve(
            """{"network":{"allow":["registry.npmjs.org","https://repo.maven.apache.org/maven2","*.gradle.org:443","evil.example.net","api.*.bad"]}}""",
            """{"network":{"allow":["registry.npmjs.org","repo.maven.apache.org","*.gradle.org"]}}""",
        )
        assertThat(r.egressHosts).containsExactly(
            "bo.example.com", "gitlab.example.com", "minio.example.com", "registry.npmjs.org", "repo.maven.apache.org", "*.gradle.org",
        )
        assertThat(r.notes).anyMatch { it.contains("evil.example.net") && it.contains("outside the pool ceiling") }
        assertThat(r.notes).anyMatch { it.contains("api.*.bad") && it.contains("not a valid host pattern") }
    }

    @Test
    fun `every repository host of a multi-repository job is a mandatory egress host, even outside the ceiling list`() {
        val r = resolver.resolve(
            SandboxPolicy.EMPTY, policy("""{"network":{"allow":["registry.npmjs.org"]}}"""), listOf("ghcr.io/org/runner:*"), BackendKind.K8S_JOB, 1800,
            listOf("https://gitlab.example.com/shop/api.git", "https://docs.example.org/shop/docs.git", "git@gitlab.example.com:shop/web.git", null),
            gateway,
        )
        assertThat(r.egressHosts).containsExactly(
            "bo.example.com", "gitlab.example.com", "docs.example.org", "minio.example.com", "registry.npmjs.org",
        )
    }

    @Test
    fun `tool denies are united with the ceiling`() {
        val r = resolve("""{"tools":{"allow":["git *"],"deny":["curl *"],"ask":["rm -rf *"]}}""", """{"tools":{"deny":["sudo *","curl *"]}}""")
        assertThat(r.toolsDeny).containsExactly("sudo *", "curl *")
        assertThat(r.toolsAllow).containsExactly("git *")
        assertThat(r.toolsAsk).containsExactly("rm -rf *")
    }

    @Test
    fun `host pattern normalisation`() {
        assertThat(PolicyResolver.normaliseHostPattern("HTTPS://User@Registry.NPMJS.org:443/path?q")).isEqualTo("registry.npmjs.org")
        assertThat(PolicyResolver.normaliseHostPattern("*.gradle.org")).isEqualTo("*.gradle.org")
        assertThat(PolicyResolver.normaliseHostPattern("*")).isNull()
        assertThat(PolicyResolver.normaliseHostPattern("ex*ample.com")).isNull()
        assertThat(PolicyResolver.patternCovers("*.gradle.org", "services.gradle.org")).isTrue()
        assertThat(PolicyResolver.patternCovers("*.gradle.org", "gradle.org")).isFalse()
        assertThat(PolicyResolver.globMatches("ghcr.io/org/*:*", "ghcr.io/org/runner:2.0")).isTrue()
        assertThat(PolicyResolver.globMatches("ghcr.io/org/runner:*", "ghcr.io/org/runner-evil:2.0")).isFalse()
    }

    @Test
    fun `contract map for policy json`() {
        val m = resolve("{}", "{}").toContractMap()
        assertThat(m["isolation"]).isEqualTo("STANDARD")
        assertThat((m["tools"] as Map<*, *>).keys).containsExactly("allow", "deny", "ask")
        assertThat((m["network"] as Map<*, *>)["allow"] as List<*>).contains("bo.example.com")
    }
}
