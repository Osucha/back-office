package pl.mos.backoffice.runnercontroller.engine

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.mos.backoffice.runnercontroller.sandbox.JobBundle
import java.nio.file.Files
import java.nio.file.Path

/** RUNNER_EXTRA_CA_FILE: a corporate proxy's CA reaches every sandbox as `extra-ca.pem`; a broken setting stops the controller. */
class ExtraCaTest {

    private val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"

    @Test
    fun `a configured PEM file is read and travels in the bundle before ready`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("ca.pem"), pem)
        assertThat(loadExtraCa("")).isNull()
        val ca = loadExtraCa(file.toString())
        assertThat(ca).isEqualTo(pem)
        val files = JobBundle("{}", "{}", "job-1", "bo_job_x", ca).files("http://proxy").keys.toList()
        assertThat(files).containsSubsequence(JobBundle.EXTRA_CA_FILE, "ready")
        assertThat(files.last()).isEqualTo("ready")
        assertThat(JobBundle("{}", "{}", "job-1", "bo_job_x").files("http://proxy")).doesNotContainKey(JobBundle.EXTRA_CA_FILE)
        assertThat(JobBundle("{}", "{}", "job-1", "bo_job_x", ca).toString()).doesNotContain("bo_job_x")
    }

    @Test
    fun `a missing file, no certificate or a private key is refused`(@TempDir dir: Path) {
        assertThatThrownBy { loadExtraCa(dir.resolve("nope.pem").toString()) }.hasMessageContaining("not a readable file")
        assertThatThrownBy { loadExtraCa(Files.writeString(dir.resolve("x.txt"), "hello").toString()) }.hasMessageContaining("no PEM certificate")
        val withKey = Files.writeString(dir.resolve("k.pem"), pem + "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----\n")
        assertThatThrownBy { loadExtraCa(withKey.toString()) }.hasMessageContaining("private key")
    }
}
