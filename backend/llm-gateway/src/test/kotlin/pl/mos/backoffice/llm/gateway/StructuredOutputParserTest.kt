package pl.mos.backoffice.llm.gateway

import com.anthropic.errors.UnauthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import tools.jackson.databind.json.JsonMapper
import java.net.ConnectException

class StructuredOutputParserTest {

    private val parser = StructuredOutputParser(JsonMapper.builder().build())

    @Test
    fun `parses plain, fenced and prose wrapped json objects`() {
        assertThat(parser.parse("""{"a":1,"b":[1,2],"c":{"d":"e"}}""")).isEqualTo(mapOf("a" to 1, "b" to listOf(1, 2), "c" to mapOf("d" to "e")))
        assertThat(parser.parse("```json\n{\"a\": 1}\n```")).isEqualTo(mapOf("a" to 1))
        assertThat(parser.parse("```\n{\"a\": 1}\n```")).isEqualTo(mapOf("a" to 1))
        assertThat(parser.parse("Here is the result: {\"a\": \"x}y\"} hope it helps")).isEqualTo(mapOf("a" to "x}y"))
    }

    @Test
    fun `rejects answers without a json object or with invalid json`() {
        assertThatThrownBy { parser.parse("no json here") }.isInstanceOf(StructuredOutputParser.ParseFailure::class.java)
        assertThatThrownBy { parser.parse("{\"a\": }") }.isInstanceOf(StructuredOutputParser.ParseFailure::class.java)
        assertThatThrownBy { parser.parse("[1,2,3]") }.isInstanceOf(StructuredOutputParser.ParseFailure::class.java)
    }

    @Test
    fun `classifier maps status codes, transport failures and wrapped causes`() {
        val tooMany = ProviderErrorClassifier.classify(HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))
        assertThat(tooMany.retryable).isTrue()
        assertThat(tooMany.status).isEqualTo(429)
        assertThat(tooMany.message).contains("rate limited")

        val forbidden = ProviderErrorClassifier.classify(RuntimeException("wrapped", HttpClientErrorException(HttpStatus.FORBIDDEN)))
        assertThat(forbidden.retryable).isFalse()
        assertThat(forbidden.message).contains("403")

        val notFound = ProviderErrorClassifier.classify(HttpClientErrorException(HttpStatus.NOT_FOUND))
        assertThat(notFound.message).contains("model id")

        val unreachable = ProviderErrorClassifier.classify(ResourceAccessException("I/O error on POST", ConnectException("Connection refused")))
        assertThat(unreachable.retryable).isTrue()

        val nonTransient = ProviderErrorClassifier.classify(NonTransientAiException("bad request"))
        assertThat(nonTransient.retryable).isFalse()

        val unknown = ProviderErrorClassifier.classify(IllegalStateException("boom"))
        assertThat(unknown.retryable).isFalse()
        assertThat(unknown.message).isEqualTo("boom")

        assertThat(UnauthorizedException::class.java.superclass.simpleName).isEqualTo("AnthropicServiceException")
    }
}
