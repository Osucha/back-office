package pl.mos.backoffice.projects.adapter.out.support

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.mos.backoffice.projects.application.port.out.BackgroundExecutor
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.Executors

/** [JsonCodec] backed by the application's Jackson 3 [JsonMapper]. */
@Component
class JacksonJsonCodec(private val mapper: JsonMapper) : JsonCodec {
    override fun toJson(value: Any?): String = mapper.writeValueAsString(value)

    @Suppress("UNCHECKED_CAST")
    override fun parseObject(json: String): Map<String, Any?> {
        val trimmed = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        val candidate = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
        return mapper.readValue(candidate, Map::class.java) as Map<String, Any?>
    }
}

/** [BackgroundExecutor] on virtual threads; exceptions are logged, never propagated to the caller. */
@Component
class VirtualThreadBackgroundExecutor : BackgroundExecutor {
    private val log = LoggerFactory.getLogger(VirtualThreadBackgroundExecutor::class.java)
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bg-", 0).factory())

    override fun submit(name: String, task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (e: Throwable) {
                log.error("background task failed name={} error={}", name, e.message, e)
            }
        }
    }
}
