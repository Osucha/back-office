package pl.mos.backoffice.tasks.domain.service

/**
 * Minimal JSON well-formedness checker (RFC 8259) so the core module can validate `outputSchema`
 * without a JSON library. It does not build a tree; it only answers "would a parser accept this?".
 */
object JsonSyntax {

    /** Null when [text] is a well-formed JSON document, otherwise a message with the offset of the first error. */
    fun problem(text: String): String? = try {
        val p = Parser(text)
        p.skipWhitespace()
        p.value()
        p.skipWhitespace()
        if (!p.atEnd()) p.fail("unexpected trailing content") else null
    } catch (e: SyntaxError) {
        e.message
    }

    fun isWellFormed(text: String): Boolean = problem(text) == null

    private class SyntaxError(message: String) : RuntimeException(message)

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(message: String): Nothing = throw SyntaxError("$message at offset $i")

        fun skipWhitespace() {
            while (!atEnd() && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value() {
            if (atEnd()) fail("unexpected end of input")
            when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> fail("unexpected character ${s[i]}")
            }
        }

        private fun obj() {
            i++
            skipWhitespace()
            if (peek() == '}') { i++; return }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected string key")
                string()
                skipWhitespace()
                if (peek() != ':') fail("expected colon")
                i++
                skipWhitespace()
                value()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return }
                    else -> fail("expected comma or closing brace")
                }
            }
        }

        private fun array() {
            i++
            skipWhitespace()
            if (peek() == ']') { i++; return }
            while (true) {
                skipWhitespace()
                value()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return }
                    else -> fail("expected comma or closing bracket")
                }
            }
        }

        private fun string() {
            i++
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' -> escape()
                    c < ' ' -> fail("control character in string")
                    else -> i++
                }
            }
        }

        /** The escape sequence starting at the backslash under the cursor. */
        private fun escape() {
            i++
            if (atEnd()) fail("unterminated escape")
            when (s[i]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                'u' -> {
                    i++
                    repeat(4) {
                        if (atEnd() || !s[i].isHexDigit()) fail("invalid unicode escape")
                        i++
                    }
                }
                else -> fail("invalid escape")
            }
        }

        private fun number() {
            if (peek() == '-') i++
            integerPart()
            if (peek() == '.') {
                i++
                digits("invalid fraction")
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                digits("invalid exponent")
            }
        }

        /** `0` or a digit sequence without a leading zero. */
        private fun integerPart() {
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> { while (peek() in '0'..'9') i++ }
                else -> fail("invalid number")
            }
        }

        /** At least one digit, else [message]. */
        private fun digits(message: String) {
            if (peek() !in '0'..'9') fail(message)
            while (peek() in '0'..'9') i++
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[i]

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
