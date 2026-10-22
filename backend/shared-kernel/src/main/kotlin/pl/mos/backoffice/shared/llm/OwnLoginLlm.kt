package pl.mos.backoffice.shared.llm

import pl.mos.backoffice.shared.IntegrationException

/**
 * ADR 0050 step 4: one model call answered by Claude Code with the acting user's own login (`usageContext.actingUserId`)
 * on their personal runner: a short PROMPT job instead of a gateway call. Implemented by the runner fleet.
 */
fun interface OwnLoginLlm {
    fun chat(model: ModelRef, request: LlmRequest): LlmResult
}

/** ADR 0050 step 4: the gateway for API models, Claude Code on the user's own login for Claude Code login models. */
fun LlmGateway.chatAs(runtime: ModelRuntime, model: ModelRef, request: LlmRequest, ownLogin: OwnLoginLlm?): LlmResult =
    if (runtime == ModelRuntime.CLAUDE_CODE_LOGIN) {
        (ownLogin ?: throw IntegrationException("claude-code", "Claude Code login models cannot answer here: no runner fleet is connected", persistent = true))
            .chat(model, request)
    } else {
        chat(model, request)
    }
