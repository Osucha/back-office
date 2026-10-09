package pl.mos.backoffice.shared.prompt

/** What kind of injection marker was found. */
enum class InjectionKind(val description: String) {
    HIDDEN_CHARACTERS("bidirectional override/isolate, zero-width or tag characters that hide or reorder text (Trojan Source)"),
    IGNORE_INSTRUCTIONS("text asking to ignore or override previous instructions"),
    ROLE_HIJACK("text trying to change the model's role or rules (\"you are now\", \"system prompt\", \"as an AI\")"),
    ADDRESSED_TO_AI("text addressed to an AI reviewer, judge or assistant"),
    VERDICT_STEERING("text dictating a verdict, score or the absence of findings"),
    FAKE_ROLE_TAG("chat-template or role markers (<system>, <|im_start|>, [INST], Human:/Assistant:)"),
    BOUNDARY_SPOOF("an imitation of the data-block delimiters"),
}

/** One suspicious place: [source] is a file path for diffs or a field name (`issue.description`). */
data class InjectionSignal(
    val kind: InjectionKind,
    val source: String,
    val line: Int?,
    val excerpt: String,
)

/**
 * Deterministic, heuristic detector of prompt-injection attempts in untrusted text
 * (docs/architecture/12-prompt-injection.md). It never decides that content is safe; it only raises
 * signals, and callers fail closed (e.g. a BLOCKING code review then needs a human). False positives are
 * acceptable by design: a diff that really discusses "ignore previous instructions" is rare and costs a
 * human look, an undetected injection could cost a merged backdoor. Repositories that legitimately contain
 * prompt text (prompt engineering code) can lower the guard per review template (`config.injectionGuard`).
 */
object InjectionDetector {

    private const val MAX_SIGNALS = 20
    private const val EXCERPT_CHARS = 120

    private val patterns: List<Pair<InjectionKind, Regex>> = listOf(
        // the gaps allow words only (no brackets, colons, dots): `override fun previous(): Instruction` is code, not an attack
        InjectionKind.IGNORE_INSTRUCTIONS to Regex(
            "\\b(ignore|disregard|forget|override|bypass|skip)\\b[ \\t\\w,'\"-]{0,40}?\\b(all|any|the|previous|prior|above|earlier|preceding|your|system|these|those|other)\\b" +
                "[ \\t\\w,'\"-]{0,30}?\\b(instructions?|prompts?|rules|guidelines|directives|guardrails|context|policies)\\b",
            RegexOption.IGNORE_CASE,
        ),
        InjectionKind.ROLE_HIJACK to Regex(
            "\\byou are now\\b|\\bfrom now on,? you\\b|\\bnew (system )?instructions?\\s*:|\\bsystem prompt\\b|\\bas an ai( language model)?\\b|" +
                "\\bdeveloper mode\\b|\\bjailbreak|\\bpretend (to be|you are)\\b",
            RegexOption.IGNORE_CASE,
        ),
        InjectionKind.ADDRESSED_TO_AI to Regex(
            "\\b(ai|llm|gpt|claude|language model|assistant|ai reviewer|code reviewer|reviewer|review bot|bot|judge|grader|evaluator)\\s*[:,]\\s*(please\\s+)?" +
                "(ignore|approve|accept|return|output|respond|reply|answer|set|give|mark|rate|score|pass|skip|do not|don't)\\b|" +
                "\\b(note|message|instructions?|hint)s?\\s+(to|for)\\s+(the\\s+)?(ai|llm|assistant|ai reviewer|model|judge|grader|evaluator|agent)\\b",
            RegexOption.IGNORE_CASE,
        ),
        // Natural-language forms only: code such as `verdict = Verdict.APPROVE` or `score = 100` must not match.
        InjectionKind.VERDICT_STEERING to Regex(
            "\\bverdict\\s*:?\\s+\"?(approve|approved|pass|passed|lgtm)\\b|\\bverdict\\s+(must|should|shall|is to)\\s+be\\s+\"?(approve|approved|pass)|" +
                "\\b(set|return|give|output|use)\\s+(the\\s+|a\\s+)?verdict\\s+(to\\s+|as\\s+|of\\s+)?\"?(approve|approved|pass)\\b|" +
                "\\b(report|return|with|there are|must be|output)\\s+(no|zero)\\s+findings\\b|" +
                "\\b(give|rate|score|grade)\\b[^\\n]{0,30}?(\\b100\\s*(/\\s*100|%|\\s+points)|\\b10\\s*/\\s*10\\b|\\bfull marks\\b|\\bmaximum score\\b|\\bperfect score\\b)",
            RegexOption.IGNORE_CASE,
        ),
        InjectionKind.FAKE_ROLE_TAG to Regex(
            "<\\|?\\s*(im_start|im_end|system|assistant|endoftext)\\s*\\|?>|</?\\s*(system|assistant|instructions?|admin|developer|tool_call)\\s*>|" +
                "\\[/?(INST|SYS)]|<<\\s*/?SYS\\s*>>|(^|\\n)\\s*(Human|Assistant)\\s*:\\s",
            RegexOption.IGNORE_CASE,
        ),
        InjectionKind.BOUNDARY_SPOOF to Regex("<\\s*/?\\s*untrusted[-_ ]?data", RegexOption.IGNORE_CASE),
    )

    /** Scans free text (an issue field, an artefact, a model output). Lines are 1-based. */
    fun scan(text: String?, source: String): List<InjectionSignal> {
        if (text.isNullOrEmpty()) return emptyList()
        val signals = mutableListOf<InjectionSignal>()
        text.split('\n').forEachIndexed { index, line ->
            if (signals.size < MAX_SIGNALS) scanLine(line, source, index + 1, signals)
        }
        return signals.distinctBy { Triple(it.kind, it.source, it.line) }.take(MAX_SIGNALS)
    }

    /**
     * Scans a unified diff and reports the file (`+++ b/<path>`) and the line in the new file for added and
     * context lines (the old file's line for removed lines).
     */
    fun scanDiff(diff: String?, fallbackSource: String = "diff"): List<InjectionSignal> {
        if (diff.isNullOrEmpty()) return emptyList()
        val signals = mutableListOf<InjectionSignal>()
        var file = fallbackSource
        var oldLine = 0
        var newLine = 0
        for (raw in diff.split('\n')) {
            if (signals.size >= MAX_SIGNALS) break
            when {
                raw.startsWith("+++ ") -> { file = pathOf(raw.substring(4)) ?: file; continue }
                raw.startsWith("--- ") -> { pathOf(raw.substring(4))?.let { if (file == fallbackSource) file = it }; continue }
                raw.startsWith("diff --git ") -> { file = raw.substringAfterLast(" b/", file); continue }
                raw.startsWith("@@") -> {
                    HUNK.find(raw)?.let { m ->
                        oldLine = m.groupValues[1].toInt()
                        newLine = m.groupValues[2].toInt()
                    }
                    scanLine(raw.substringAfter("@@", "").substringAfter("@@", ""), file, newLine.takeIf { it > 0 }, signals)
                    continue
                }
            }
            when {
                raw.startsWith("+") -> { scanLine(raw.substring(1), file, newLine, signals); newLine++ }
                raw.startsWith("-") -> { scanLine(raw.substring(1), file, oldLine, signals); oldLine++ }
                raw.startsWith(" ") -> { scanLine(raw.substring(1), file, newLine, signals); oldLine++; newLine++ }
                else -> scanLine(raw, file, null, signals)
            }
        }
        return signals.distinctBy { Triple(it.kind, it.source, it.line) }.take(MAX_SIGNALS)
    }

    /** Files named in the `+++ b/` / `diff --git` headers of a unified diff (to check that findings cite the diff). */
    fun filesOf(diff: String?): Set<String> {
        if (diff.isNullOrEmpty()) return emptySet()
        val files = linkedSetOf<String>()
        diff.lineSequence().forEach { line ->
            when {
                line.startsWith("+++ ") -> pathOf(line.substring(4))?.let(files::add)
                line.startsWith("--- ") -> pathOf(line.substring(4))?.let(files::add)
                line.startsWith("diff --git ") -> files += line.substringAfterLast(" b/", "").ifEmpty { return@forEach }
            }
        }
        return files
    }

    private fun pathOf(header: String): String? {
        val path = header.trim().substringBefore('\t')
        if (path == "/dev/null" || path.isEmpty()) return null
        return if (path.startsWith("a/") || path.startsWith("b/")) path.substring(2) else path
    }

    private fun scanLine(line: String, source: String, lineNo: Int?, into: MutableList<InjectionSignal>) {
        if (line.isEmpty() || into.size >= MAX_SIGNALS) return
        if (UntrustedText.containsHiddenControl(line)) {
            val visible = line.codePoints().toArray().joinToString("") { cp ->
                if (UntrustedText.isHiddenControl(cp)) "<U+%04X>".format(cp) else String(Character.toChars(cp))
            }
            into += InjectionSignal(InjectionKind.HIDDEN_CHARACTERS, source, lineNo, excerpt(visible))
        }
        val normalised = UntrustedText.sanitize(line, 10_000)
        for ((kind, regex) in patterns) {
            val match = regex.find(normalised) ?: continue
            into += InjectionSignal(kind, source, lineNo, excerpt(normalised, match.range.first))
        }
    }

    private fun excerpt(text: String, from: Int = 0): String {
        val start = (from - 20).coerceAtLeast(0)
        val raw = text.substring(start).trim()
        return if (raw.length > EXCERPT_CHARS) raw.take(EXCERPT_CHARS) + "..." else raw
    }

    private val HUNK = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
}
