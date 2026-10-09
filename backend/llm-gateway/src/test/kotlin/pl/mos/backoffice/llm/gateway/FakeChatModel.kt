package pl.mos.backoffice.llm.gateway

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted `ChatModel`: records every prompt and replays answers (or throws) in order. */
class FakeChatModel : ChatModel {
    val prompts = CopyOnWriteArrayList<Prompt>()
    private val script = ArrayDeque<() -> ChatResponse>()

    fun reply(text: String, usage: Usage = DefaultUsage(10, 20), finishReason: String = "stop", id: String = "resp-1"): FakeChatModel {
        script.addLast { response(text, usage, finishReason, id) }
        return this
    }

    fun fail(error: Throwable): FakeChatModel {
        script.addLast { throw error }
        return this
    }

    override fun call(prompt: Prompt): ChatResponse {
        prompts += prompt
        val next = script.removeFirstOrNull() ?: error("no scripted reply for prompt #${prompts.size}")
        return next()
    }

    companion object {
        fun response(text: String, usage: Usage = DefaultUsage(10, 20), finishReason: String = "stop", id: String = "resp-1"): ChatResponse =
            ChatResponse(
                listOf(Generation(AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder().id(id).usage(usage).build(),
            )
    }
}
