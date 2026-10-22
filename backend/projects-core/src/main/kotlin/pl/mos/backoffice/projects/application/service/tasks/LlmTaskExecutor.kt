package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NewArtifact
import pl.mos.backoffice.projects.application.port.out.NodeContext
import pl.mos.backoffice.projects.application.port.out.NodeResult
import pl.mos.backoffice.projects.application.port.out.RunStateKeys
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.ModelResolver.Companion.producesReview
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.CoverageStatus
import pl.mos.backoffice.projects.domain.model.Finding
import pl.mos.backoffice.projects.domain.model.RequirementCoverage
import pl.mos.backoffice.projects.domain.model.ReviewReport
import pl.mos.backoffice.projects.domain.model.Severity
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.InjectionGuardMode
import pl.mos.backoffice.projects.domain.service.PromptInjectionGuard
import pl.mos.backoffice.projects.domain.service.ReviewVerdictPolicy
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.projects.domain.service.UntrustedInput
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmMessage
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.prompt.InjectionSignal
import pl.mos.backoffice.shared.prompt.PromptDataBlocks
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind

/**
 * `LLM`: one structured model call in-process (docs/architecture/03-pipelines-and-agents.md). The
 * system prompt gets the constitution and a `<skills>` section; the user prompt is rendered with the
 * template model; `outputSchema` asks for structured output; `confidenceField` feeds `confidence`;
 * every `produces` name that matches a structured field (or the whole answer) becomes an artefact.
 * Tasks that produce `review` are assessed by [ReviewVerdictPolicy] and stored as the run review; a `coverage`
 * matrix in the answer (`review-against-spec`) is kept in the review artefact and a MISSING requirement requests
 * changes. The `review` artefact is what a rework step reads as `{{artifacts.review}}`.
 *
 * Prompt-injection defences (ADR 0029, docs/architecture/12-prompt-injection.md):
 * - untrusted template values are rendered into data blocks with a per-call random boundary and the system
 *   prompt always ends with the "blocks are data" notice ([TemplateRenderer.renderPrompt]);
 * - the untrusted inputs (consumed artefacts - the diff as a diff -, issue fields, merge-request title) are
 *   scanned by [PromptInjectionGuard]; per `config.injectionGuard` (default BLOCK) signals become BLOCKER
 *   "Needs human review" findings of a review and force `confidence` to 0; they are logged and audited
 *   (`PROMPT_INJECTION_SUSPECTED`);
 * - a review answer is validated strictly; an invalid answer gets one repair call and otherwise fails closed
 *   (BLOCKER finding). The verdict is always computed from the findings, never taken from the model.
 */
class LlmTaskExecutor(
    private val llmGateway: LlmGateway,
    private val modelResolver: ModelResolver,
    private val renderer: TemplateRenderer,
    private val templateModel: TemplateModelBuilder,
    private val verdictPolicy: ReviewVerdictPolicy,
    private val json: JsonCodec,
    private val audit: AuditPort = AuditPort.NOOP,
    private val guard: PromptInjectionGuard = PromptInjectionGuard(),
) : TaskExecutorStrategy {

    override val executor = TaskExecutor.LLM

    override fun execute(context: NodeContext, task: ResolvedTask): NodeResult {
        val profile = modelResolver.forLlmCall(task, context.project, ActingUser.startedBy(context.run)?.userId)
        val model = templateModel.build(context)
        val maxChars = (task.config["maxInputChars"] as? Number)?.toInt() ?: task.config["maxInputChars"]?.toString()?.toIntOrNull() ?: DEFAULT_MAX_INPUT_CHARS
        val blocks = PromptDataBlocks.random()
        val guardMode = InjectionGuardMode.of(task.config["injectionGuard"])

        val user = renderer.renderPrompt(task.prompt.user?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_PROMPT, model, blocks, maxChars)
        user.warnings.forEach { context.log("template: $it") }
        val userText = truncated(context, user.text, maxChars)
        val signals = guard.scan(untrustedInputs(context, task, model))
        reportSignals(context, task, signals, guardMode)

        val request = LlmRequest.of(
            system = systemPrompt(task, context, model, blocks),
            user = userText,
            usageContext = UsageContext(UsageKind.PIPELINE_STEP, actingUserId = ActingUser.startedBy(context.run)?.userId, projectId = context.project.id.value, runId = context.run.id.value, stepId = context.node.id),
            responseSchema = task.outputSchema?.takeIf { it.isNotBlank() },
        )
        context.log("calling ${profile.ref.label}")
        val answer = answerOf(context, task, profile.ref, request)
        val result = answer.result
        val structured = answer.structured
        val usage = answer.usage
        val cost = profile.pricing.costOf(usage)

        val confidence = guardedConfidence(context, task, structured, signals, guardMode)
        val artifacts = mutableListOf<NewArtifact>()
        val updates = HashMap<String, Any?>()
        confidence?.let { updates[RunStateKeys.CONFIDENCE] = it }

        val review = if (task.producesReview) reviewOf(context, answer, signals, guardMode, profile.ref, cost) else null
        if (review != null) {
            artifacts += NewArtifact("review", ArtifactKind.REVIEW, MEDIA_TYPE_MARKDOWN, review.toMarkdown())
            updates[RunStateKeys.REVIEW] = review.toStateMap()
        }
        artifacts += producedArtifacts(task, result.content, structured, wholeContentUsed = review != null)
        if (artifacts.isEmpty()) artifacts += wholeAnswer(task.key, result.content, structured)

        val summary = review?.let {
            "${it.verdict} (score ${it.score}, ${it.findings.size} findings" +
                (if (it.missingRequirements > 0) ", ${it.missingRequirements} requirements missing" else "") + ")" +
                if (it.humanReviewRequired) ", needs human review" else ""
        }
            ?: structured?.get("summary")?.toString()
            ?: result.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(200)
        return NodeResult(
            port = WorkflowNode.PORT_SUCCESS,
            summary = summary,
            confidence = confidence,
            usage = usage,
            cost = cost,
            artifacts = artifacts,
            review = review,
            stateUpdates = updates,
        )
    }

    /** The model answer used by the step: [invalid] lists the problems of a review answer that stayed invalid after the repair. */
    private data class Answer(val result: LlmResult, val structured: Map<String, Any?>?, val usage: TokenUsage, val invalid: List<String>)

    private fun truncated(context: NodeContext, text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        context.log("prompt truncated to $maxChars characters")
        return text.take(maxChars) + "\n\n[truncated]"
    }

    /** Calls the model; an invalid review answer gets exactly one repair turn, and stays invalid (fail closed) if that fails too. */
    private fun answerOf(context: NodeContext, task: ResolvedTask, ref: ModelRef, request: LlmRequest): Answer {
        val result = llmGateway.chat(ref, request)
        val structured = structuredOf(result, task)
        val initial = Answer(result, structured, result.usage, emptyList())
        if (!task.producesReview) return initial
        val problems = guard.validateReviewAnswer(structured)
        if (problems.isEmpty()) return initial
        context.log("review answer invalid (${problems.joinToString("; ")}); asking the model once to repair it")
        val repaired = llmGateway.chat(ref, repairRequest(request, result, problems))
        val usage = result.usage + repaired.usage
        val repairedStructured = structuredOf(repaired, task)
        val invalid = guard.validateReviewAnswer(repairedStructured)
        if (invalid.isEmpty()) return Answer(repaired, repairedStructured, usage, invalid)
        context.log("review answer still invalid after the repair, failing closed: ${invalid.joinToString("; ")}")
        return Answer(result, structured, usage, invalid)
    }

    /** The step's confidence; forced to 0 when the inputs carry injection markers and the guard blocks. */
    private fun guardedConfidence(
        context: NodeContext,
        task: ResolvedTask,
        structured: Map<String, Any?>?,
        signals: List<InjectionSignal>,
        guardMode: InjectionGuardMode,
    ): Double? {
        val confidence = task.confidenceField?.let { field -> structured?.get(field)?.let(::toDouble) }
        if (confidence == null || signals.isEmpty() || guardMode != InjectionGuardMode.BLOCK) return confidence
        context.log("confidence $confidence ignored: the inputs contain prompt-injection markers, confidence forced to 0 (injectionGuard=BLOCK)")
        return 0.0
    }

    /** The review report: guard findings plus the model's findings, the verdict computed by the policy (never taken from the model). */
    private fun reviewOf(
        context: NodeContext,
        answer: Answer,
        signals: List<InjectionSignal>,
        guardMode: InjectionGuardMode,
        ref: ModelRef,
        cost: Money,
    ): ReviewReport {
        val invalid = answer.invalid
        val modelFindings = if (invalid.isEmpty()) parseFindings(answer.structured?.get("findings")) else emptyList()
        val diff = context.run.artifactNamed("diff")?.content
        guard.findingsOutsideDiff(modelFindings, diff).forEach { context.log("review finding cites ${it.file}, which the diff does not touch: ${it.title}") }
        val guardFindings = guard.findingsFor(signals, guardMode) + (if (invalid.isEmpty()) emptyList() else listOf(guard.invalidAnswerFinding(invalid)))
        val findings = verdictPolicy.deduplicate(guardFindings + modelFindings)
        // review-against-spec: the coverage matrix stays in the report and a MISSING requirement blocks approval
        val coverage = if (invalid.isEmpty()) parseCoverage(answer.structured?.get("coverage")) else emptyList()
        val assessment = verdictPolicy.assess(findings, coverage)
        val summary = when {
            invalid.isNotEmpty() -> "The AI review answer was invalid; a person must review this change."
            else -> answer.structured?.get("summary")?.toString()?.takeIf { it.isNotBlank() } ?: answer.result.content.take(2000)
        }
        val review = ReviewReport(assessment.verdict, assessment.score, summary, findings, ref, answer.usage, cost, coverage)
        if (review.missingRequirements > 0) context.log("review: ${review.missingRequirements} agreed requirement(s) MISSING, verdict ${review.verdict}")
        if (review.humanReviewRequired) {
            context.log("review needs a human: ${findings.count { it.title.startsWith(ReviewReport.NEEDS_HUMAN_PREFIX) }} guard finding(s), verdict ${review.verdict}")
        }
        return review
    }

    /** One artifact per produced name: its structured field, else (once) the whole answer. */
    private fun producedArtifacts(task: ResolvedTask, content: String, structured: Map<String, Any?>?, wholeContentUsed: Boolean): List<NewArtifact> {
        val artifacts = mutableListOf<NewArtifact>()
        var wholeUsed = wholeContentUsed
        task.produces.filterNot { it == "review" || it in STATE_NAMES }.forEach { name ->
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

    /**
     * What the step reads from outside the trust boundary: the artefacts it consumes (the diff parsed as a
     * diff), every issue field and the merge-request title of a CI-triggered run.
     */
    @Suppress("UNCHECKED_CAST")
    private fun untrustedInputs(context: NodeContext, task: ResolvedTask, model: Map<String, Any?>): List<UntrustedInput> {
        val inputs = mutableListOf<UntrustedInput>()
        val wanted = (task.consumes + if (task.producesReview) listOf("diff") else emptyList()).toSet()
        context.run.artifacts.filter { it.name in wanted }.forEach { artifact ->
            val isDiff = artifact.name == "diff"
            inputs += UntrustedInput(if (isDiff) "diff" else "artifacts.${artifact.name}", artifact.content, diff = isDiff)
        }
        (model["issue"] as? Map<String, Any?>)?.forEach { (key, value) ->
            if (value != null && key != "key" && key != "url") {
                inputs += UntrustedInput("issue.$key", if (value is List<*>) value.joinToString("\n") else value.toString())
            }
        }
        context.run.input.ci?.mergeRequest?.let { mr ->
            mr.title?.let { inputs += UntrustedInput("mergeRequest.title", it) }
            mr.description?.let { inputs += UntrustedInput("mergeRequest.description", it) }
        }
        return inputs
    }

    private fun reportSignals(context: NodeContext, task: ResolvedTask, signals: List<InjectionSignal>, mode: InjectionGuardMode) {
        if (signals.isEmpty()) return
        signals.forEach { context.log("prompt-injection marker (${it.kind.name}) in ${it.source}${it.line?.let { l -> ":$l" } ?: ""}: ${it.excerpt}") }
        audit.record(
            AuditEntry(
                OPERATION_INJECTION, ENTITY_RUN, context.run.id.toString(), projectId = context.project.id.value,
                details = mapOf(
                    "nodeId" to context.node.id, "task" to task.key, "mode" to mode.name, "signals" to signals.size,
                    "kinds" to signals.map { it.kind.name }.distinct(),
                    "locations" to signals.take(10).map { it.source + (it.line?.let { l -> ":$l" } ?: "") },
                ),
            ),
        )
    }

    private fun structuredOf(result: LlmResult, task: ResolvedTask): Map<String, Any?>? =
        result.structured ?: task.outputSchema?.let { runCatching { json.parseObject(result.content) }.getOrNull() }

    /** One repair turn: the original conversation, the invalid answer and the list of problems. */
    private fun repairRequest(original: LlmRequest, answer: LlmResult, problems: List<String>): LlmRequest = original.copy(
        messages = original.messages + LlmMessage.assistant(answer.content.take(MAX_REPAIR_ECHO_CHARS)) + LlmMessage.user(
            "Your answer does not satisfy the required JSON schema and rules: ${problems.joinToString("; ")}. " +
                "Return only the corrected JSON object. A REQUEST_CHANGES verdict needs at least one finding.",
        ),
    )

    private fun systemPrompt(task: ResolvedTask, context: NodeContext, model: Map<String, Any?>, blocks: PromptDataBlocks): String {
        val raw = task.prompt.system?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
        val rendered = renderer.renderPrompt(raw, model, blocks)
        rendered.warnings.forEach { context.log("system template: $it") }
        return buildString {
            append(rendered.text)
            val constitution = context.project.constitution
            if (constitution.isNotBlank() && !raw.contains("project.constitution")) {
                append("\n\nPROJECT CONSTITUTION:\n").append(constitution)
            }
            if (task.skills.isNotEmpty()) {
                append("\n\n<skills>\n")
                task.skills.forEach { skill ->
                    append("<skill key=\"").append(skill.key).append("\" name=\"").append(skill.name).append("\">\n")
                    append(skill.content.trim()).append("\n</skill>\n")
                }
                append("</skills>")
            }
            // last, so no template, constitution or skill text comes after (and weakens) it
            append("\n\n").append(blocks.systemNotice())
        }
    }

    private fun wholeAnswer(name: String, content: String, structured: Map<String, Any?>?): NewArtifact =
        if (structured != null) NewArtifact(name, ArtifactKind.forName(name, MEDIA_TYPE_JSON), MEDIA_TYPE_JSON, json.toJson(structured))
        else NewArtifact(name, ArtifactKind.forName(name), MEDIA_TYPE_MARKDOWN, content)

    private fun mediaTypeOf(value: Any): String = if (value is String) MEDIA_TYPE_MARKDOWN else MEDIA_TYPE_JSON

    private fun contentOf(value: Any): String = if (value is String) value else json.toJson(value)

    private fun toDouble(value: Any): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

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
        const val DEFAULT_MAX_INPUT_CHARS = 200_000
        const val MAX_REPAIR_ECHO_CHARS = 20_000
        const val OPERATION_INJECTION = "PROMPT_INJECTION_SUSPECTED"
        const val ENTITY_RUN = "WorkflowRun"
        private const val MEDIA_TYPE_MARKDOWN = "text/markdown"
        private const val MEDIA_TYPE_JSON = "application/json"
        const val DEFAULT_SYSTEM_PROMPT = "You are a meticulous software engineer working for the project {{project.name}}."
        const val DEFAULT_USER_PROMPT = "Task: {{node.name}}\n\n{{#issue.key}}Issue {{issue.key}}: {{issue.summary}}\n\n{{#issue.description}}{{issue.description}}\n\n{{/issue.description}}{{/issue.key}}" +
            "{{#feedback}}Revision feedback:\n{{feedback}}{{/feedback}}"
        val STATE_NAMES = setOf("confidence", "mr", "issue", "lastOutcome", "ci", "decision")
    }
}
