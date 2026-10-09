package pl.mos.backoffice.tasks.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class JsonSyntaxTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}", "[]", "null", "true", "-12.5e+3", "0", "-0.25", "1E-2", "[0, 10, 3.0e2]", "\"esc \\\" \\n \\u00e9\"",
            """{"a": [1, 2, {"b": null}], "c": "d", "e": {"f": [true, false]}}""",
            " \n {\"type\":\"object\",\"properties\":{\"confidence\":{\"type\":\"number\"}}} \n",
        ],
    )
    fun `accepts well formed documents`(text: String) {
        assertThat(JsonSyntax.problem(text)).isNull()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "{", "}", "{\"a\":}", "{\"a\" 1}", "{a: 1}", "[1,]", "[1 2]", "{\"a\":1,}", "01", "1.", "\"unterminated",
            "\"bad \\x escape\"", "nul", "{} {}", "{\"a\":1}]", "\"tab\tinside\"", "-", "1e", "1.e5", "--1", "1e+", "\"\\u12G4\"", "\"\\u00", "\"\\",
        ],
    )
    fun `rejects malformed documents`(text: String) {
        assertThat(JsonSyntax.problem(text)).describedAs(text).isNotNull().contains("offset")
    }
}
