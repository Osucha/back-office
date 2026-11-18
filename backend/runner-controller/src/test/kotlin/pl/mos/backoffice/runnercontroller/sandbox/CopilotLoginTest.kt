package pl.mos.backoffice.runnercontroller.sandbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0069: the owner's GitHub Copilot login is the github-copilot entry of OpenCode's auth file, nothing else. */
class CopilotLoginTest {

    private val json = JsonMapper.builder().build()

    // obviously fake values: never a real token
    private val auth = """
        {"github-copilot": {"type": "oauth", "refresh": "gho_FAKE_REFRESH", "access": "gho_FAKE_REFRESH", "expires": 0},
         "openai": {"type": "api", "key": "sk-FAKE-OTHER-PROVIDER"}}
    """.trimIndent()

    @Test
    fun `only the github-copilot entry is kept and the default host is the Copilot API`(@TempDir dir: Path) {
        val file = dir.resolve("auth.json").also { Files.writeString(it, auth) }
        val login = CopilotLogin.read(file.toString(), json)!!
        val doc = json.readTree(login.authJson)
        assertThat(doc.propertyNames().asSequence().toList()).containsExactly("github-copilot")
        assertThat(doc.get("github-copilot").get("refresh").asString()).isEqualTo("gho_FAKE_REFRESH")
        assertThat(login.authJson).doesNotContain("sk-FAKE-OTHER-PROVIDER")
        assertThat(login.hosts).containsExactly("api.githubcopilot.com")
        assertThat(login.toString()).doesNotContain("gho_FAKE")
    }

    @Test
    fun `a GitHub Enterprise login reaches its own Copilot API host`() {
        val doc = json.readTree("""{"github-copilot": {"type": "oauth", "refresh": "x", "enterpriseUrl": "https://company.ghe.com/"}}""")
        assertThat(CopilotLogin.of(doc)!!.hosts).containsExactly("copilot-api.company.ghe.com")
    }

    @Test
    fun `no login without the file, without a Copilot OAuth entry or with broken JSON`(@TempDir dir: Path) {
        assertThat(CopilotLogin.read(dir.resolve("missing.json").toString(), json)).isNull()
        assertThat(CopilotLogin.read("", json)).isNull()
        val broken = dir.resolve("broken.json").also { Files.writeString(it, "{not json") }
        assertThat(CopilotLogin.read(broken.toString(), json)).isNull()
        assertThat(CopilotLogin.of(json.readTree("""{"openai": {"type": "api", "key": "x"}}"""))).isNull()
        assertThat(CopilotLogin.of(json.readTree("""{"github-copilot": {"type": "api", "key": "x"}}"""))).isNull()
        assertThat(CopilotLogin.of(json.readTree("""{"github-copilot": {"type": "oauth", "refresh": ""}}"""))).isNull()
    }
}
