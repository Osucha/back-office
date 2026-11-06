package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CoverageStatus
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.RequirementCoverage
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.service.PromptInjectionGuard
import pl.mos.backoffice.projects.domain.service.ReviewVerdictPolicy
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.tasks.ResolvedTask

/**
 * What a structured answer (a JSON object for the task's `outputSchema`) becomes, the same for a model answer and,
 * ADR 0057, for a coding agent that wrote `structured-answer.json`: the confidence (`confidenceField`), one artefact per
 * produced name (its field, else the whole answer once), and for tasks that produce `review` the review report with the
 * verdict computed by [ReviewVerdictPolicy] (never taken from the model) and a requirement coverage matrix.
 */
class StructuredAnswers(
    private val json: JsonCodec,
    private val verdictPolicy: ReviewVerdictPolicy,
    private val guard: PromptInjectionGuard = PromptInjectionGuard(),
) {

    /** A JSON object from [text] (surrounding whitespace and a markdown code fence tolerated); null when it is none. */
    fun parse(text: String): Map<String, Any?>? {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.parseObject(trimmed) }.getOrNull()
    }

    /** The raw confidence of the answer (`confidenceField`), null when the task has none or the field is not a number. */
    fun confidence(task: ResolvedTask, structured: Map<String, Any?>?): Double? =
        task.confidenceField?.let { field -> structured?.get(field)?.let(::toDouble) }

    /**
     * The review report: [guardFindings] plus the model's findings; [invalid] = problems of an answer that stayed invalid
     * (its findings and coverage are ignored, a person must review). [fallbackSummary] when the answer has no summary.
     */
    fun review(
        context: NodeContext,
        structured: Map<String, Any?>?,
        fallbackSummary: String,
        guardFindings: List<Finding>,
        invalid: List<String>,
        ref: ModelRef,
        usage: TokenUsage,
        cost: Money,
    ): ReviewReport {
        val modelFindings = if (invalid.isEmpty()) parseFindings(structured?.get("findings")) else emptyList()
        val diff = context.run.artifactNamed("diff")?.content
        guard.findingsOutsideDiff(modelFindings, diff).forEach { context.log("review finding cites ${it.file}, which the diff does not touch: ${it.title}") }
        val findings = verdictPolicy.deduplicate(guardFindings + (if (invalid.isEmpty()) emptyList() else listOf(guard.invalidAnswerFinding(invalid))) + modelFindings)
        // review-against-spec: the coverage matrix stays in the report and a MISSING requirement blocks approval
        val coverage = if (invalid.isEmpty()) parseCoverage(structured?.get("coverage")) else emptyList()
        val assessment = verdictPolicy.assess(findings, coverage)
        val summary = when {
            invalid.isNotEmpty() -> "The AI review answer was invalid; a person must review this change."
            else -> structured?.get("summary")?.toString()?.takeIf { it.isNotBlank() } ?: fallbackSummary.take(MAX_SUMMARY)
        }
        val review = ReviewReport(assessment.verdict, assessment.score, summary, findings, ref, usage, cost, coverage)
        if (review.missingRequirements > 0) context.log("review: ${review.missingRequirements} agreed requirement(s) MISSING, verdict ${review.verdict}")
        if (review.humanReviewRequired) {
            context.log("review needs a human: ${findings.count { it.title.startsWith(ReviewReport.NEEDS_HUMAN_PREFIX) }} guard finding(s), verdict ${review.verdict}")
        }
        return review
    }

    /** The `review` artefact of [review]. */
    fun reviewArtifact(review: ReviewReport): NewArtifact = NewArtifact("review", ArtifactKind.REVIEW, MEDIA_TYPE_MARKDOWN, review.toMarkdown())

    /**
     * One artifact per produced name: its structured field, else (once) the whole answer. Names in [skip] (produced
     * otherwise, e.g. by the runner job) and `review` / state names are left out.
     */
    fun producedArtifacts(
        task: ResolvedTask,
        content: String,
        structured: Map<String, Any?>?,
        wholeContentUsed: Boolean,
        skip: Set<String> = emptySet(),
    ): List<NewArtifact> {
        val artifacts = mutableListOf<NewArtifact>()
        var wholeUsed = wholeContentUsed
        task.produces.filterNot { it == "review" || it in STATE_NAMES || it in skip }.forEach { name ->
            val field = structured?.get(name)
            if (field != null) {
                artifacts += NewArtifact(name, ArtifactKind.forName(name, mediaTypeOf(field)), mediaTypeOf(field), contentOf(field))
            } else if (!wholeUsed) {
                artifacts += wholeAnswer(name, content, structured)
                wholeUsed = true
            }
        }
        return artifacts
    }

    /** The whole answer as artefact [name]: the JSON object when there is one, else the text. */
    fun wholeAnswer(name: String, content: String, structured: Map<String, Any?>?): NewArtifact =
        if (structured != null) NewArtifact(name, ArtifactKind.forName(name, MEDIA_TYPE_JSON), MEDIA_TYPE_JSON, json.toJson(structured))
        else NewArtifact(name, ArtifactKind.forName(name), MEDIA_TYPE_MARKDOWN, content)

    /**
     * ADR 0057: why a coding agent's `structured-answer.json` does not satisfy [schema]: not a JSON object, a required
     * top-level property missing or a top-level property of the wrong JSON type; for a review also the review rules.
     * The structured output of model answers is enforced by the providers; agents write a file, so it is checked here.
     */
    fun problems(task: ResolvedTask, structured: Map<String, Any?>?, schema: String): List<String> {
        if (structured == null) return listOf("the answer is not a JSON object")
        val parsed = runCatching { json.parseObject(schema) }.getOrNull() ?: return reviewProblems(task, structured)
        val problems = mutableListOf<String>()
        (parsed["required"] as? List<*>).orEmpty().mapNotNull { it?.toString() }.forEach { name ->
            if (structured[name] == null) problems += "required property $name is missing"
        }
        (parsed["properties"] as? Map<*, *>).orEmpty().forEach { (name, definition) ->
            val value = structured[name.toString()] ?: return@forEach
            val types = when (val t = (definition as? Map<*, *>)?.get("type")) {
                is String -> listOf(t)
                is List<*> -> t.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            if (types.isNotEmpty() && types.none { typeMatches(it, value) }) problems += "property $name must be ${types.joinToString(" or ")}"
        }
        return problems + reviewProblems(task, structured)
    }

    private fun reviewProblems(task: ResolvedTask, structured: Map<String, Any?>): List<String> =
        if (task.produces.contains("review")) guard.validateReviewAnswer(structured) else emptyList()

    private fun typeMatches(type: String, value: Any): Boolean = when (type) {
        "string" -> value is String
        "number" -> value is Number
        "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
        "boolean" -> value is Boolean
        "array" -> value is List<*>
        "object" -> value is Map<*, *>
        else -> true
    }

    private fun mediaTypeOf(value: Any): String = if (value is String) MEDIA_TYPE_MARKDOWN else MEDIA_TYPE_JSON

    private fun contentOf(value: Any): String = if (value is String) value else json.toJson(value)

    @Suppress("UNCHECKED_CAST")
    private fun parseFindings(raw: Any?): List<Finding> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            val severity = m["severity"]?.toString()?.uppercase()?.let { s -> Severity.entries.firstOrNull { it.name == s } } ?: Severity.MINOR
            val title = m["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Finding(
                severity = severity,
                file = m["file"]?.toString() ?: "",
                line = (m["line"] as? Number)?.toInt() ?: m["line"]?.toString()?.toIntOrNull(),
                title = title,
                detail = m["detail"]?.toString() ?: "",
                suggestion = m["suggestion"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** The coverage matrix of a review against the specification; rows without a requirement are dropped, unknown statuses read as PARTIAL. */
    private fun parseCoverage(raw: Any?): List<RequirementCoverage> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val requirement = m["requirement"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = m["status"]?.toString()?.uppercase()?.let { s -> CoverageStatus.entries.firstOrNull { it.name == s } } ?: CoverageStatus.PARTIAL
            RequirementCoverage(requirement, status, m["evidence"]?.toString().orEmpty())
        }
    }

    companion object {
        const val MEDIA_TYPE_MARKDOWN = "text/markdown"
        const val MEDIA_TYPE_JSON = "application/json"
        private const val MAX_SUMMARY = 2000

        /** Produced names that are run state, not artefacts. */
        val STATE_NAMES = setOf("confidence", "mr", "issue", "lastOutcome", "ci", "decision")

        fun toDouble(value: Any): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
