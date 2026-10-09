package pl.mos.backoffice.runnercontroller.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runnercontroller.protocol.ProtocolJson

/**
 * The multi-repository spec (`spec.repositories`) is covered by the unchanged digest algorithm: arrays keep their
 * order, objects inside them are sorted, null members (`pushBranch`, `description`) are dropped, non-ASCII stays
 * literal. Expected values were computed independently (Python: json.dumps(sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False) after removing null members, then SHA-256). The shared vector file gets a two-repository
 * vector from the control plane; [SpecDigestVectorsTest] runs every vector in it.
 */
class MultiRepositoryDigestTest {
    private val json = ProtocolJson.mapper()

    private val input = """{"policy":{"isolation":"STANDARD","timeoutSeconds":3600,"network":{"allow":["registry.npmjs.org"]}},""" +
        """"spec":{"mode":"IMPLEMENT","agent":"CLAUDE_CODE","model":{"provider":"ANTHROPIC","modelId":"claude-opus-5"},""" +
        """"prompt":"Implement SHOP-1","timeoutSeconds":3600,"runId":"0b8b1c2e-0000-4000-8000-000000000002","repositories":[""" +
        """{"key":"src","url":"https://gitlab.example.com/shop/api.git","baseBranch":"main","access":"PUSH_MR","path":"src",""" +
        """"pushBranch":"feature/shop-1","mergeRequest":{"create":true,"title":"SHOP-1: cart","description":null,"draft":false},"primary":true},""" +
        """{"key":"docs","url":"https://gitlab.example.com/shop/docs.git","baseBranch":"main","access":"READ","path":"docs",""" +
        """"pushBranch":null,"purpose":"Product docs – read only","primary":false}]}}"""

    private val expectedCanonical = """{"policy":{"isolation":"STANDARD","network":{"allow":["registry.npmjs.org"]},"timeoutSeconds":3600},""" +
        """"spec":{"agent":"CLAUDE_CODE","mode":"IMPLEMENT","model":{"modelId":"claude-opus-5","provider":"ANTHROPIC"},""" +
        """"prompt":"Implement SHOP-1","repositories":[""" +
        """{"access":"PUSH_MR","baseBranch":"main","key":"src","mergeRequest":{"create":true,"draft":false,"title":"SHOP-1: cart"},""" +
        """"path":"src","primary":true,"pushBranch":"feature/shop-1","url":"https://gitlab.example.com/shop/api.git"},""" +
        """{"access":"READ","baseBranch":"main","key":"docs","path":"docs","primary":false,"purpose":"Product docs ${"–"} read only",""" +
        """"url":"https://gitlab.example.com/shop/docs.git"}],"runId":"0b8b1c2e-0000-4000-8000-000000000002","timeoutSeconds":3600}}"""

    @Test
    fun `two-repository spec canonicalises and hashes as computed independently`() {
        val tree = json.readTree(input)
        val canonical = CanonicalJson.write(CanonicalJson.specDigestInput(tree["spec"], tree["policy"]))
        assertThat(canonical).isEqualTo(expectedCanonical)
        assertThat(CanonicalJson.specDigest(tree["spec"], tree["policy"]))
            .isEqualTo("180809d0a7f4bc27aa94cc54a2d610ad7802437e96f731763d062b569d803d57")
    }

    @Test
    fun `repository order is significant, member order is not`() {
        val tree = json.readTree(input)
        val reordered = json.readTree(
            input.replace(""""key":"src","url":"https://gitlab.example.com/shop/api.git"""", """"url":"https://gitlab.example.com/shop/api.git","key":"src""""),
        )
        assertThat(CanonicalJson.specDigest(reordered["spec"], reordered["policy"])).isEqualTo(CanonicalJson.specDigest(tree["spec"], tree["policy"]))
        val spec = tree["spec"].deepCopy() as tools.jackson.databind.node.ObjectNode
        val repos = spec["repositories"] as tools.jackson.databind.node.ArrayNode
        val first = repos.remove(0)
        repos.add(first)
        assertThat(CanonicalJson.specDigest(spec, tree["policy"])).isNotEqualTo(CanonicalJson.specDigest(tree["spec"], tree["policy"]))
    }
}
