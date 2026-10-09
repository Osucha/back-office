package pl.mos.backoffice.llm.proxy

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import pl.mos.backoffice.llm.PostgresTestSupport
import java.util.UUID

/** Benchmark jobs (BATCH) leave the interactive reserve of the provider limit to workflow runs (ADR 0020). */
@SpringBootTest(
    properties = [
        "spring.ai.model.chat=none", "spring.flyway.enabled=true", "spring.jpa.open-in-view=false",
        "backoffice.llm.rate-limits.anthropic.requests-per-minute=5",
        "backoffice.llm.rate-limits.anthropic.interactive-reserve=0.4",
        "backoffice.llm.rate-limits.anthropic.max-wait=0s",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class, ProxyTestBeans::class)
class LlmProxyRateLimitTest : ProxyTestBase() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: FakeJobTokens

    private fun call(token: String) = mvc.perform(
        MockMvcRequestBuilders.post("/api/v1/llm/anthropic/v1/messages").header("x-api-key", token).contentType(MediaType.APPLICATION_JSON)
            .content("""{"model":"claude-opus-5","max_tokens":10,"messages":[]}"""),
    )

    @Test
    fun `batch calls stop at the reserve while interactive calls continue`() {
        provider.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"usage":{"input_tokens":1,"output_tokens":1}}""")))
        val (batch, _) = tokens.issue(benchmarkId = UUID.randomUUID(), projectId = null)
        val (interactive, _) = tokens.issue()
        repeat(3) { call(batch).andExpect(status().isOk) }
        call(batch).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("rate-limited"))
        call(interactive).andExpect(status().isOk)
        call(interactive).andExpect(status().isOk)
        call(interactive).andExpect(status().isTooManyRequests)
    }
}
