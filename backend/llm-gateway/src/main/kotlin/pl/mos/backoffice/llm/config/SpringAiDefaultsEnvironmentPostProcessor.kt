package pl.mos.backoffice.llm.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Registered in `META-INF/spring.factories`. Spring AI starters auto-configure a model bean per model
 * type (chat, embedding, image, audio, moderation) for every provider on the classpath and fail when the
 * provider key is missing. The gateway builds chat models itself (ADR 0003), so every type defaults to
 * `none` here, with lowest precedence: `application.yml` or environment variables still win.
 */
class SpringAiDefaultsEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val defaults = MODEL_TYPE_PROPERTIES.associateWith { "none" as Any }
        environment.propertySources.addLast(MapPropertySource(SOURCE_NAME, defaults))
    }

    companion object {
        const val SOURCE_NAME = "backoffice-llm-gateway-defaults"
        val MODEL_TYPE_PROPERTIES = listOf(
            "spring.ai.model.chat",
            "spring.ai.model.embedding",
            "spring.ai.model.embedding.text",
            "spring.ai.model.embedding.multimodal",
            "spring.ai.model.image",
            "spring.ai.model.audio.transcription",
            "spring.ai.model.audio.speech",
            "spring.ai.model.moderation",
            "spring.ai.model.ocr",
        )
    }
}
