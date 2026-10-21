package pl.mos.backoffice.runner.domain.policy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.mos.backoffice.shared.runner.SandboxEnvironment
import pl.mos.backoffice.shared.runner.SandboxIsolation
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.runner.SandboxResources
import pl.mos.backoffice.shared.runner.SandboxTools
import java.math.BigDecimal

class SandboxPolicyResolverTest {

    private val resolver = SandboxPolicyResolver()

    private val platform = SandboxPolicy(
        isolation = SandboxIsolation.STANDARD,
        resources = SandboxResources("2", "4Gi", "10Gi", 2048),
        timeoutSeconds = 3600,
        networkAllow = listOf("registry.npmjs.org", "repo.maven.apache.org", "*.gradle.org"),
        tools = SandboxTools(deny = listOf("curl *", "sudo *"), ask = listOf("rm -rf *")),
    )

    private fun layers(vararg pairs: Pair<PolicyLevel, SandboxPolicy>) = pairs.map { PolicyLayer(it.first, it.second) }

    private fun resolve(vararg pairs: Pair<PolicyLevel, SandboxPolicy>, images: List<String>? = null, hosts: List<String> = emptyList()) =
        resolver.resolve(layers(*pairs), images, hosts)

    @Nested
    inner class Isolation {
        @ParameterizedTest(name = "platform {0}, pool {1}, requested {2} -> {3}")
        @CsvSource(
            "STANDARD, , , STANDARD",
            "STANDARD, HIGH, , HIGH",
            "STANDARD, , DEV, STANDARD",
            "STANDARD, HIGH, STANDARD, HIGH",
            "DEV, , STANDARD, STANDARD",
            "DEV, DEV, , DEV",
            "STANDARD, , HIGH, HIGH",
        )
        fun `strongest isolation wins`(platformIsolation: SandboxIsolation, pool: SandboxIsolation?, requested: SandboxIsolation?, expected: SandboxIsolation) {
            val result = resolve(
                PolicyLevel.PLATFORM to platform.copy(isolation = platformIsolation),
                PolicyLevel.POOL to SandboxPolicy(isolation = pool),
                PolicyLevel.TEMPLATE to SandboxPolicy(isolation = requested),
            )
            assertThat(result.policy.isolation).isEqualTo(expected)
        }

        @Test
        fun `source is the level that set the winning value`() {
            val result = resolve(PolicyLevel.PLATFORM to platform, PolicyLevel.POOL to SandboxPolicy(isolation = SandboxIsolation.HIGH), PolicyLevel.NODE to SandboxPolicy(isolation = SandboxIsolation.HIGH))
            assertThat(result.sources["isolation"]).isEqualTo("POOL")
        }
    }

    @Nested
    inner class Resources {
        @Test
        fun `every dimension is the minimum across levels, keeping the original notation`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform,
                PolicyLevel.POOL to SandboxPolicy(resources = SandboxResources(cpu = "1500m", memory = "8Gi")),
                PolicyLevel.PROJECT to SandboxPolicy(resources = SandboxResources(memory = "3000Mi", pids = 4096)),
                PolicyLevel.TEMPLATE to SandboxPolicy(resources = SandboxResources(cpu = "4", ephemeralStorage = "5G")),
            )
            assertThat(result.policy.resources).isEqualTo(SandboxResources(cpu = "1500m", memory = "3000Mi", ephemeralStorage = "5G", pids = 2048))
            assertThat(result.sources).containsEntry("resources.cpu", "POOL").containsEntry("resources.memory", "PROJECT")
                .containsEntry("resources.ephemeralStorage", "TEMPLATE").containsEntry("resources.pids", "PLATFORM")
        }

        @Test
        fun `a lower level cannot raise the timeout or the caps`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform.copy(maxCostUsd = BigDecimal("20"), maxTokens = 2_000_000),
                PolicyLevel.TEMPLATE to SandboxPolicy(timeoutSeconds = 7200, maxCostUsd = BigDecimal("5")),
                PolicyLevel.NODE to SandboxPolicy(maxTokens = 5_000_000),
            )
            assertThat(result.policy.timeoutSeconds).isEqualTo(3600)
            assertThat(result.policy.maxCostUsd).isEqualByComparingTo("5")
            assertThat(result.policy.maxTokens).isEqualTo(2_000_000)
        }

        @Test
        fun `unparseable quantities are errors`() {
            val result = resolve(PolicyLevel.PLATFORM to platform, PolicyLevel.PROJECT to SandboxPolicy(resources = SandboxResources(cpu = "lots", memory = "4 GB")))
            assertThat(result.ok).isFalse()
            assertThat(result.errors).anyMatch { it.startsWith("resources.cpu") }.anyMatch { it.startsWith("resources.memory") }
        }
    }

    @Nested
    inner class Network {
        @Test
        fun `lower levels intersect with the ceiling patterns`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform,
                PolicyLevel.PROJECT to SandboxPolicy(networkAllow = listOf("plugins.gradle.org", "registry.npmjs.org", "evil.example.com")),
            )
            assertThat(result.policy.networkAllow).containsExactly("plugins.gradle.org", "registry.npmjs.org")
            assertThat(result.sources["network.allow"]).isEqualTo("PROJECT")
        }

        @Test
        fun `a wildcard below narrows a list above to its covered hosts`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform.copy(networkAllow = listOf("plugins.gradle.org", "registry.npmjs.org")),
                PolicyLevel.TEMPLATE to SandboxPolicy(networkAllow = listOf("*.gradle.org")),
            )
            assertThat(result.policy.networkAllow).containsExactly("plugins.gradle.org")
        }

        @Test
        fun `an empty list inherits and mandatory hosts are always added`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform.copy(networkAllow = listOf("registry.npmjs.org")),
                PolicyLevel.TEMPLATE to SandboxPolicy(networkAllow = listOf("pypi.org")),
                hosts = listOf("api.backoffice.example.com", "gitlab.example.com"),
            )
            assertThat(result.policy.networkAllow).containsExactly("api.backoffice.example.com", "gitlab.example.com")
            val inherited = resolve(PolicyLevel.PLATFORM to platform, PolicyLevel.PROJECT to SandboxPolicy(), hosts = listOf("gitlab.example.com"))
            assertThat(inherited.policy.networkAllow).containsExactly("registry.npmjs.org", "repo.maven.apache.org", "*.gradle.org", "gitlab.example.com")
        }

        @Test
        fun `ports narrow too`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform.copy(networkAllow = listOf("nexus.internal:8443", "registry.npmjs.org")),
                PolicyLevel.PROJECT to SandboxPolicy(networkAllow = listOf("nexus.internal", "registry.npmjs.org:443")),
            )
            assertThat(result.policy.networkAllow).containsExactlyInAnyOrder("registry.npmjs.org:443", "nexus.internal:8443")
        }

        @Test
        fun `invalid patterns are errors`() {
            val result = resolve(PolicyLevel.PLATFORM to platform, PolicyLevel.PROJECT to SandboxPolicy(networkAllow = listOf("https://x.org/path", "a.*b.org")))
            assertThat(result.errors).hasSize(2)
        }
    }

    @Nested
    inner class Tools {
        @Test
        fun `deny and ask are unions, allow is an intersection`() {
            val result = resolve(
                PolicyLevel.PLATFORM to platform.copy(tools = SandboxTools(allow = listOf("git *", "./gradlew *", "npm *"), deny = listOf("curl *"), ask = listOf("rm -rf *"))),
                PolicyLevel.POOL to SandboxPolicy(tools = SandboxTools(deny = listOf("docker *"))),
                PolicyLevel.TEMPLATE to SandboxPolicy(tools = SandboxTools(allow = listOf("git status", "npm *", "make *"), deny = listOf("npm publish *"), ask = listOf("git push *", "curl *"))),
            )
            assertThat(result.policy.tools.deny).containsExactly("curl *", "docker *", "npm publish *")
            assertThat(result.policy.tools.ask).containsExactly("rm -rf *", "git push *")
            assertThat(result.policy.tools.allow).containsExactlyInAnyOrder("git status", "npm *")
        }
    }

    @Nested
    inner class Secrets {
        private val pool = SandboxPolicy(secrets = listOf("npm-read-token", "maven-settings"))

        @Test
        fun `requested secrets must be allowed by the pool`() {
            val result = resolve(PolicyLevel.PLATFORM to platform, PolicyLevel.POOL to pool, PolicyLevel.TEMPLATE to SandboxPolicy(secrets = listOf("npm-read-token", "prod-db-password")))
            assertThat(result.policy.secrets).containsExactly("npm-read-token")
            assertThat(result.warnings).singleElement().asString().contains("prod-db-password")
            assertThat(result.ok).isTrue()
        }

        @Test
        fun `the pool list is an allowlist, not a default`() {
            val result = resolve(PolicyLevel.PLATFORM to platform, PolicyLevel.POOL to pool)
            assertThat(result.policy.secrets).isEmpty()
        }

        @Test
        fun `project and template requests intersect`() {
            val result = resolve(
                PolicyLevel.POOL to pool,
                PolicyLevel.PROJECT to SandboxPolicy(secrets = listOf("npm-read-token", "maven-settings")),
                PolicyLevel.NODE to SandboxPolicy(secrets = listOf("maven-settings")),
            )
            assertThat(result.policy.secrets).containsExactly("maven-settings")
            assertThat(result.sources["secrets"]).isEqualTo("NODE")
        }
    }

    @Nested
    inner class Images {
        private val images = listOf("ghcr.io/org/back-office-runner:*")

        @Test
        fun `the most specific image wins and must match the pool patterns`() {
            val ok = resolve(
                PolicyLevel.POOL to SandboxPolicy(environment = SandboxEnvironment(image = "ghcr.io/org/back-office-runner:2.0")),
                PolicyLevel.TEMPLATE to SandboxPolicy(environment = SandboxEnvironment(image = "ghcr.io/org/back-office-runner:2.0-jdk21-node22", setupCommand = "make setup")),
                images = images,
            )
            assertThat(ok.ok).isTrue()
            assertThat(ok.policy.environment.image).isEqualTo("ghcr.io/org/back-office-runner:2.0-jdk21-node22")
            assertThat(ok.sources["environment.image"]).isEqualTo("TEMPLATE")

            val rejected = resolve(PolicyLevel.TEMPLATE to SandboxPolicy(environment = SandboxEnvironment(image = "docker.io/evil/miner:latest")), images = images)
            assertThat(rejected.ok).isFalse()
            assertThat(rejected.errors.single()).contains("docker.io/evil/miner:latest")
        }

        @Test
        fun `without pool patterns only the pool or platform image is allowed`() {
            val poolImage = SandboxPolicy(environment = SandboxEnvironment(image = "registry/runner:1"))
            assertThat(resolve(PolicyLevel.POOL to poolImage, images = emptyList()).ok).isTrue()
            assertThat(resolve(PolicyLevel.POOL to poolImage, PolicyLevel.PROJECT to SandboxPolicy(environment = SandboxEnvironment(image = "registry/other:1")), images = emptyList()).ok).isFalse()
            // no pool known (effective view): the image is not checked
            assertThat(resolve(PolicyLevel.PROJECT to SandboxPolicy(environment = SandboxEnvironment(image = "registry/other:1"))).ok).isTrue()
        }

        @Test
        fun `caches intersect with the levels above`() {
            val result = resolve(
                PolicyLevel.POOL to SandboxPolicy(environment = SandboxEnvironment(caches = listOf("gradle", "npm"))),
                PolicyLevel.TEMPLATE to SandboxPolicy(environment = SandboxEnvironment(caches = listOf("npm", "pip"))),
            )
            assertThat(result.policy.environment.caches).containsExactly("npm")
        }
    }

    @Nested
    inner class PoolSelector {
        @Test
        fun `selectors are merged and conflicts are errors`() {
            val merged = resolve(PolicyLevel.PROJECT to SandboxPolicy(poolSelector = mapOf("region" to "eu")), PolicyLevel.TEMPLATE to SandboxPolicy(poolSelector = mapOf("gpu" to "true")))
            assertThat(merged.policy.poolSelector).isEqualTo(mapOf("region" to "eu", "gpu" to "true"))
            val conflict = resolve(PolicyLevel.PROJECT to SandboxPolicy(poolSelector = mapOf("region" to "eu")), PolicyLevel.NODE to SandboxPolicy(poolSelector = mapOf("region" to "us")))
            assertThat(conflict.ok).isFalse()
        }
    }

    @Test
    fun `layer order does not depend on list order`() {
        val a = resolver.resolve(listOf(PolicyLayer(PolicyLevel.TEMPLATE, SandboxPolicy(timeoutSeconds = 100)), PolicyLayer(PolicyLevel.PLATFORM, platform)))
        val b = resolver.resolve(listOf(PolicyLayer(PolicyLevel.PLATFORM, platform), PolicyLayer(PolicyLevel.TEMPLATE, SandboxPolicy(timeoutSeconds = 100))))
        assertThat(a).isEqualTo(b)
        assertThat(a.policy.timeoutSeconds).isEqualTo(100)
    }
}

class PatternsTest {
    @ParameterizedTest(name = "{0} covers {1} = {2}")
    @CsvSource(
        "registry.npmjs.org, registry.npmjs.org, true",
        "*.gradle.org, plugins.gradle.org, true",
        "*.gradle.org, a.b.gradle.org, false",
        "*.gradle.org, gradle.org, false",
        "plugins.gradle.org, *.gradle.org, false",
        "*.gradle.org, *.gradle.org, true",
        "nexus.internal, nexus.internal:8443, true",
        "nexus.internal:8443, nexus.internal, false",
        "nexus.internal:8443, nexus.internal:8443, true",
        "REGISTRY.npmjs.org, registry.NPMJS.org, true",
    )
    fun `host pattern coverage`(outer: String, inner: String, expected: Boolean) {
        assertThat(HostPatterns.covers(outer, inner)).isEqualTo(expected)
    }

    @Test
    fun `git hosts are taken from https and scp-style urls`() {
        assertThat(HostPatterns.hostOf("https://gitlab.example.com/group/shop.git")).isEqualTo("gitlab.example.com")
        assertThat(HostPatterns.hostOf("https://oauth2:token@gitlab.example.com:8443/g/p.git")).isEqualTo("gitlab.example.com:8443")  // trufflehog:ignore (test fixture)
        assertThat(HostPatterns.hostOf("git@github.com:org/repo.git")).isEqualTo("github.com")
        assertThat(HostPatterns.hostOf(null)).isNull()
    }

    @ParameterizedTest(name = "cpu {0} = {1}m")
    @CsvSource("500m, 500", "2, 2000", "1.5, 1500", "0.25, 250")
    fun `cpu quantities`(value: String, millis: Long) {
        assertThat(Quantities.cpuMillis(value)).isEqualTo(millis)
    }

    @ParameterizedTest(name = "{0} = {1} bytes")
    @CsvSource("4Gi, 4294967296", "512Mi, 536870912", "1G, 1000000000", "1024, 1024", "1.5Gi, 1610612736")
    fun `byte quantities`(value: String, bytes: Long) {
        assertThat(Quantities.bytes(value)).isEqualTo(bytes)
    }

    @Test
    fun `command globs`() {
        assertThat(GlobPatterns.covers("git *", "git status")).isTrue()
        assertThat(GlobPatterns.covers("git *", "gitk")).isFalse()
        assertThat(GlobPatterns.matches("ghcr.io/org/back-office-runner:*", "ghcr.io/org/back-office-runner:2.0-jdk21")).isTrue()
        assertThat(GlobPatterns.matches("ghcr.io/org/back-office-runner:*", "ghcr.io/org/back-office-runner-evil:1")).isFalse()
    }
}
