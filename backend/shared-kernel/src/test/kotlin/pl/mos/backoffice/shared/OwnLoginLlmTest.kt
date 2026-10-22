package pl.mos.backoffice.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.OwnLoginLlm
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.llm.chatAs
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import java.time.Duration

/** ADR 0050 step 4: API models go to the gateway, Claude Code login models to the user's own login. */
class OwnLoginLlmTest {

    private val model = ModelRef(Provider.ANTHROPIC, "opus")
    private val request = LlmRequest.of(null, "hi", UsageContext(UsageKind.PIPELINE_STEP))
    private fun answer(text: String) = LlmResult(text, usage = TokenUsage.ZERO, latency = Duration.ZERO, modelRef = model)

    private val gateway = object : LlmGateway {
        override fun chat(model: ModelRef, request: LlmRequest) = answer("gateway")
        override fun isConfigured(provider: Provider) = true
    }

    @Test
    fun `the runtime decides the route and a login model without a runner fleet is an error`() {
        val ownLogin = OwnLoginLlm { _, _ -> answer("own login") }
        assertThat(gateway.chatAs(ModelRuntime.API, model, request, ownLogin).content).isEqualTo("gateway")
        assertThat(gateway.chatAs(ModelRuntime.CLAUDE_CODE_LOGIN, model, request, ownLogin).content).isEqualTo("own login")
        assertThatThrownBy { gateway.chatAs(ModelRuntime.CLAUDE_CODE_LOGIN, model, request, null) }
            .isInstanceOf(IntegrationException::class.java).hasMessageContaining("no runner fleet")
    }
}
