package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.IntegrationException

/**
 * ADR 0050 step 4: one model call answered by Claude Code with the acting user's own login (`usageContext.actingUserId`)
 * on their personal runner: a short PROMPT job instead of a gateway call. Implemented by the runner fleet. ADR 0069: a
 * [Provider.GITHUB_COPILOT] model is answered the same way by OpenCode with the user's Copilot login.
 */
fun interface OwnLoginLlm {
    fun chat(model: ModelRef, request: LlmRequest): LlmResult
}

/** ADR 0050 step 4 / 0069: the gateway for API models, the user's own login on their personal runner for login models. */
fun LlmGateway.chatAs(runtime: ModelRuntime, model: ModelRef, request: LlmRequest, ownLogin: OwnLoginLlm?): LlmResult =
    if (runtime.ownLogin) {
        (ownLogin ?: throw IntegrationException(loginSystem(runtime), "login models cannot answer here: no runner fleet is connected", persistent = true))
            .chat(model, request)
    } else {
        chat(model, request)
    }

private fun loginSystem(runtime: ModelRuntime): String = if (runtime == ModelRuntime.COPILOT_LOGIN) "github-copilot" else "claude-code"
