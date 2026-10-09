package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import pl.mos.backoffice.runner.adapter.`in`.web.AssignmentSigner
import pl.mos.backoffice.runner.adapter.`in`.web.CanonicalJson
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * The shared test vectors of the assignment digest (docs/api/fixtures/spec-digest-vectors.json). The
 * runner-controller verifies assignments with its own canonicaliser and runs the same vectors.
 */
class SpecDigestVectorsTest {
    private val json = JsonMapper.builder().build()

    @TestFactory
    fun `control plane reproduces every shared spec digest vector`(): List<DynamicTest> {
        val vectors = json.readTree(File("../../docs/api/fixtures/spec-digest-vectors.json"))["vectors"]
        return vectors.toList().map { v ->
            DynamicTest.dynamicTest(v["name"].stringValue()) {
                @Suppress("UNCHECKED_CAST")
                val input = json.readValue(v["inputJson"].stringValue(), Map::class.java) as Map<String, Any?>
                val canonical = CanonicalJson.write(mapOf("policy" to input["policy"], "spec" to input["spec"]))
                assertThat(canonical).isEqualTo(v["canonical"].stringValue())
                assertThat(AssignmentSigner.specDigest(input["spec"], input["policy"])).isEqualTo(v["specDigest"].stringValue())
            }
        }
    }
}
