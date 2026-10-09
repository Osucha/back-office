package pl.mos.backoffice.runnercontroller.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson
import java.io.File

/**
 * The shared test vectors of the assignment digest (docs/api/fixtures/spec-digest-vectors.json). The control
 * plane's `AssignmentSigner` runs the same vectors, so a change on either side that breaks verification
 * fails a build instead of every agent job in production.
 */
class SpecDigestVectorsTest {
    private val json = ProtocolJson.mapper()

    @TestFactory
    fun `runner-controller reproduces every shared spec digest vector`(): List<DynamicTest> {
        val vectors = json.readTree(File("../../docs/api/fixtures/spec-digest-vectors.json"))["vectors"]
        return vectors.toList().map { v ->
            DynamicTest.dynamicTest(v["name"].stringValue()) {
                val input = json.readTree(v["inputJson"].stringValue())
                val canonical = CanonicalJson.write(CanonicalJson.specDigestInput(input["spec"], input["policy"]))
                assertThat(canonical).isEqualTo(v["canonical"].stringValue())
                assertThat(CanonicalJson.specDigest(input["spec"], input["policy"])).isEqualTo(v["specDigest"].stringValue())
            }
        }
    }
}
