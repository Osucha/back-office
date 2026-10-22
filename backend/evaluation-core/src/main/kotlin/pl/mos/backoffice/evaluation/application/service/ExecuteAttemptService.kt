package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.port.`in`.ExecuteAttempt
import pl.mos.backoffice.evaluation.application.port.`in`.ExecuteAttempt.AttemptExecutionReport
import pl.mos.backoffice.evaluation.application.port.out.AttemptWork
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.ModelProfileRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.domain.event.BenchmarkFinished
import pl.mos.backoffice.evaluation.domain.event.BenchmarkProgress
import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.AttemptStatus
import pl.mos.backoffice.evaluation.domain.model.AttemptVerification
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.BenchmarkProgressCounts
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.JudgeVerdict
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.service.JudgePrompts
import pl.mos.backoffice.evaluation.domain.service.ScoreCombiner
import pl.mos.backoffice.shared.prompt.InjectionDetector
import pl.mos.backoffice.shared.prompt.PromptDataBlocks
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.LlmResult
import pl.mos.backoffice.shared.llm.OwnLoginLlm
import pl.mos.backoffice.shared.llm.chatAs
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.runner.GitAccess
import pl.mos.backoffice.shared.runner.RunnerJobSpec
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerRepository
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import pl.mos.backoffice.shared.runner.RunnerTarget
import pl.mos.backoffice.shared.runner.RunnerAcceptance

/**
 * Runs one attempt synchronously on the caller's thread (docs/architecture/04-model-evaluation.md):
 *
 * - `PROMPT`: candidate call through the gateway, then a structured judge call with the rubric.
 * - `CODING_JOB`: agent runner job (`EVAL_CODING`), `passed` from the verification exit code, judge on
 *   the summary, diff statistics and verification output; score combined with the suite weights.
 *
 * Any exception marks the attempt `FAILED`; a `TIMED_OUT` runner result marks it `TIMED_OUT`. When the
 * last attempt of a benchmark becomes terminal the benchmark is finished (`COMPLETED`, or `FAILED` when
 * nothing succeeded). Attempts of a benchmark that is no longer `RUNNING` are skipped.
 */
class ExecuteAttemptService(
    private val benchmarks: BenchmarkRepository,
    private val suites: TaskSuiteRepository,
    private val profiles: ModelProfileRepository,
    private val gateway: LlmGateway,
    private val runner: AgentRunner,
    private val clock: Clock,
    /** ADR 0050 step 4: answers of Claude Code login models (candidates and judges) on the creator's runner; null = none. */
    private val ownLogin: OwnLoginLlm? = null,
) : ExecuteAttempt {

    @SystemOperation("runs on the benchmark executor's threads after an authorised start; never exposed through a controller")
    override fun execute(attemptId: AttemptId, allowRetry: Boolean): AttemptExecutionReport {
        val pending = benchmarks.findAttempt(attemptId) ?: return AttemptExecutionReport.SKIPPED
        // RUNNING / JUDGING: a previous holder of the work item died mid-attempt; the queue lease makes this call the only one
        if (pending.status.terminal) return AttemptExecutionReport.SKIPPED
        val benchmark = benchmarks.findById(pending.benchmarkId) ?: return AttemptExecutionReport.SKIPPED
        if (benchmark.status != BenchmarkStatus.RUNNING) return AttemptExecutionReport.SKIPPED
        val waitingJob = pending.runnerJobId?.takeIf { pending.kind == TaskKind.CODING_JOB }
        if (waitingJob != null && runner.result(waitingJob) == null) {
            // the coding job is still running; its continuation (BENCHMARK_ATTEMPT) calls again when it finishes
            return AttemptExecutionReport(pending, null, null, waiting = true)
        }

        var attempt = benchmarks.saveAttempt(if (pending.status == AttemptStatus.RUNNING) pending else pending.start(clock.now()))
        val finalAttempt: Attempt? = try {
            val suite = suites.findById(benchmark.suiteId) ?: throw NotFoundException("TaskSuite", benchmark.suiteId)
            val task = suite.task(attempt.taskId)
            val candidate = profiles.findById(attempt.modelProfileId) ?: throw NotFoundException("ModelProfile", attempt.modelProfileId)
            val judge = profiles.findById(benchmark.judgeModelProfileId) ?: throw NotFoundException("ModelProfile", benchmark.judgeModelProfileId)
            // ADR 0050 step 2b: the benchmark is its creator's work (their personal connections may serve it)
            val context = UsageContext(
                kind = UsageKind.BENCHMARK_ATTEMPT, benchmarkId = benchmark.id.value, attemptId = attempt.id.value, actingUserId = benchmark.createdBy?.userId,
            )

            when (task.kind) {
                TaskKind.PROMPT -> runPrompt(attempt, task, candidate, judge, context)
                TaskKind.CODING_JOB -> runCodingJob(attempt, benchmark, task, candidate, judge, suite.codingScoreWeights, context) { attempt = it }
            }
        } catch (e: IntegrationException) {
            // the stored state: JUDGING with the candidate's answer when only the judge failed (ADR 0046)
            val current = benchmarks.findAttempt(attempt.id)?.takeIf { !it.status.terminal } ?: attempt
            if (allowRetry && e.retryable) {
                // Rate limited or a transient provider error (ADR 0020): back to the queue, not a failed attempt; the
                // candidate's answer stays, so the retry asks only the judge again.
                val requeued = benchmarks.saveAttempt(current.requeue())
                return AttemptExecutionReport(requeued, null, null, retry = true, retryReason = e.message)
            }
            val judgeFailed = current.status == AttemptStatus.JUDGING
            if (e.persistent) skipSiblings(benchmark, current, judgeFailed, e.message ?: e.javaClass.simpleName)
            current.fail((if (judgeFailed) "judge: " else "") + (e.message ?: e.javaClass.simpleName), clock.now())
        } catch (e: Exception) {
            attempt.fail(e.message ?: e.javaClass.simpleName, clock.now())
        }
        if (finalAttempt == null) return AttemptExecutionReport(attempt, null, null, waiting = true)
        val saved = benchmarks.saveAttempt(finalAttempt)
        return afterTerminal(benchmark, saved)
    }

    private fun runPrompt(attempt: Attempt, task: EvalTask, candidate: ModelProfile, judge: ModelProfile, context: UsageContext): Attempt {
        // a retry after the judge failed keeps the answer it already has (ADR 0046): the candidate is not asked twice
        val judging = attempt.output?.let { benchmarks.saveAttempt(attempt.judging(it, null, attempt.usage, attempt.cost)) } ?: run {
            val candidateResult = gateway.chatAs(candidate.runtime, candidate.ref, LlmRequest.of(JudgePrompts.CANDIDATE_SYSTEM_PROMPT, task.prompt, context), ownLogin)
            benchmarks.saveAttempt(
                attempt.judging(
                    output = candidateResult.content,
                    verification = null,
                    usage = candidateResult.usage,
                    cost = candidate.pricing.costOf(candidateResult.usage),
                    candidateLatencyMs = candidateResult.latency.toMillis(),
                ),
            )
        }
        val answer = judging.output.orEmpty()
        val blocks = PromptDataBlocks.random()
        val signals = InjectionDetector.scan(answer, "candidate_output")
        val (judged, judgeCost) = judge(judge, JudgePrompts.promptTaskJudgeMessage(task, answer, blocks), blocks, context)
        val verdict = JudgePrompts.guard(judged, signals)
        val combined = ScoreCombiner.prompt(verdict.score)
        return judging.succeed(combined.score, combined.passed, verdict, judgeCost, clock.now())
    }

    private fun runCodingJob(
        attempt: Attempt,
        benchmark: Benchmark,
        task: EvalTask,
        candidate: ModelProfile,
        judge: ModelProfile,
        weights: CodingScoreWeights,
        context: UsageContext,
        onProgress: (Attempt) -> Unit,
    ): Attempt? {
        val repository = task.repository ?: throw IllegalStateException("CODING_JOB task ${task.id} has no repository")
        val spec = RunnerJobSpec(
            mode = RunnerMode.EVAL_CODING,
            agent = benchmark.codingHarness,
            model = candidate.ref,
            prompt = task.prompt,
            repository = RunnerRepository(
                url = repository.url,
                baseRef = repository.baseRef,
                branch = "eval/${attempt.id.short}",
                push = false,
            ),
            setupCommand = repository.setupCommand,
            verifyCommand = repository.verifyCommand,
            timeout = task.timeout,
            // ADR 0047: the judge reads the diff; collecting *.md only brought the repository's README
            collectArtifacts = emptyList(),
            acceptance = repository.acceptance?.let { RunnerAcceptance(it.files, it.protectedPaths, it.requireChanges) },
            usageContext = context,
            jobLabel = "eval-${attempt.id.short}-${attempt.repetition}",
            // benchmarks only read the repository: the runner cluster hands out its read-only token (ADR 0031)
            gitAccess = GitAccess.READ,
            // ADR 0046: the creator's personal runner when the benchmark asks for it, else the shared fleet
            personalRunnerOf = benchmark.createdBy?.userId?.takeIf { benchmark.runnerTarget == RunnerTarget.PERSONAL },
        )
        val existing = attempt.runnerJobId
        if (existing == null) {
            // non-blocking (ADR 0023/0024): the job's end enqueues BENCHMARK_ATTEMPT for this attempt again
            val jobId = runner.submit(spec, AttemptWork.request(attempt.id))
            benchmarks.saveAttempt(attempt.withRunnerJob(jobId)).also(onProgress)
            return null
        }
        val withJob = attempt
        val result = runner.result(existing) ?: return null
        val usage = result.usage
        // ADR 0040/0048: with its user's own login Claude Code's figure is an estimate; nothing is billed
        val cost = if (result.subscription) Money.ZERO else result.reportedCostUsd?.let { Money(it).rounded() } ?: candidate.pricing.costOf(usage)
        val verification = result.verification?.let { AttemptVerification(it.command, it.exitCode, it.outputTail) }
        val output = renderCodingOutput(result)

        if (result.status == RunnerJobStatus.TIMED_OUT) {
            return withJob.judging(output, verification, usage, cost).timeOut(result.error ?: "runner job timed out after ${task.timeout}", clock.now())
        }
        if (result.status != RunnerJobStatus.SUCCEEDED && verification == null) {
            return withJob.judging(output, verification, usage, cost).fail(result.error ?: "runner job ${result.status}", clock.now())
        }

        // the agent's time: from submitting the job (the attempt's start) to its result, queueing for a runner included
        val agentMs = withJob.startedAt?.let { java.time.Duration.between(it, result.finishedAt).toMillis() }?.takeIf { it >= 0 }
        val judging = benchmarks.saveAttempt(withJob.judging(output, verification, usage, cost, agentMs)).also(onProgress)
        val diffStat = result.diffStat?.let { "${it.files} files changed, ${it.insertions} insertions(+), ${it.deletions} deletions(-)" }
        val artifacts = result.artifacts.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { a ->
            "### ${a.name}\n${a.content.take(MAX_ARTIFACT_CHARS)}"
        }
        val blocks = PromptDataBlocks.random()
        val signals = InjectionDetector.scan(result.summary, "agent_summary") + InjectionDetector.scan(artifacts, "artifacts") +
            InjectionDetector.scan(verification?.outputTail, "verification_output")
        val (judged, judgeCost) = judge(judge, JudgePrompts.codingTaskJudgeMessage(task, result.summary, diffStat, verification, artifacts, blocks, result.diff), blocks, context)
        val verdict = JudgePrompts.guard(judged, signals)
        val passed = verification?.passed == true
        val combined = ScoreCombiner.coding(passed, verdict.score, weights)
        return judging.succeed(combined.score, combined.passed, verdict, judgeCost, clock.now())
    }

    private fun judge(judge: ModelProfile, message: String, blocks: PromptDataBlocks, context: UsageContext): Pair<JudgeVerdict, Money> {
        // the verdict is a short JSON; a cap keeps a talkative (or reasoning) judge from writing pages and timing out
        val cap = judge.ref.options.maxOutputTokens?.coerceAtMost(JUDGE_MAX_OUTPUT_TOKENS) ?: JUDGE_MAX_OUTPUT_TOKENS
        val result: LlmResult = gateway.chatAs(
            judge.runtime,
            judge.ref,
            LlmRequest.of(JudgePrompts.systemPrompt(blocks), message, context, responseSchema = JudgePrompts.RESPONSE_SCHEMA)
                .copy(optionsOverride = judge.ref.options.copy(maxOutputTokens = cap)),
            ownLogin,
        )
        return JudgePrompts.parseVerdict(result.structured, result.content) to judge.pricing.costOf(result.usage)
    }

    private fun renderCodingOutput(result: RunnerResult): String = buildString {
        appendLine("Status: ${result.status}")
        result.branch?.let { appendLine("Branch: $it") }
        result.commitSha?.let { appendLine("Commit: $it") }
        result.diffStat?.let { appendLine("Diff: ${it.files} files, +${it.insertions} -${it.deletions}") }
        result.error?.let { appendLine("Error: $it") }
        appendLine()
        appendLine(result.summary ?: "(no summary)")
    }.trim()

    /**
     * ADR 0046: a failure that every further call repeats (no access to the model, a daily quota used up, a bad key) ends
     * the attempts still waiting for the same model, or all of them when the judge failed, instead of sending each one
     * to the provider. Attempts already handed to a runner finish on their own.
     */
    private fun skipSiblings(benchmark: Benchmark, failed: Attempt, judgeFailed: Boolean, reason: String) {
        val now = clock.now()
        val note = if (judgeFailed) "skipped, the judge failed: $reason" else "skipped, ${failed.modelDisplayName} failed: $reason"
        benchmarks.findAttempts(benchmark.id)
            .filter { it.id != failed.id && it.status == AttemptStatus.PENDING && it.runnerJobId == null }
            .filter { judgeFailed || it.modelProfileId == failed.modelProfileId }
            .forEach { benchmarks.saveAttempt(it.fail(note, now)) }
    }

    private fun afterTerminal(benchmark: Benchmark, attempt: Attempt): AttemptExecutionReport {
        val now = clock.now()
        val remaining = benchmarks.countAttemptsNotIn(benchmark.id, setOf(AttemptStatus.SUCCEEDED, AttemptStatus.FAILED, AttemptStatus.TIMED_OUT))
        val all = benchmarks.findAttempts(benchmark.id)
        val counts = BenchmarkProgressCounts.of(all)
        val progress = BenchmarkProgress(benchmark.id, counts, attempt.id, attempt.status, now)
        var finished: BenchmarkFinished? = null
        if (remaining == 0) {
            val current = benchmarks.findById(benchmark.id) ?: benchmark
            if (current.status == BenchmarkStatus.RUNNING) {
                val done = current.finish(anySucceeded = all.any { it.status == AttemptStatus.SUCCEEDED }, now = now)
                // compare-and-set: when two workers finish the last attempts together only one announces the end
                if (benchmarks.finishIfRunning(done.id, done.status, now)) finished = BenchmarkFinished(done.id, done.status, counts, now)
            }
        }
        return AttemptExecutionReport(attempt, progress, finished)
    }

    companion object {
        const val MAX_ARTIFACT_CHARS = 8_000

        /** ADR 0046: enough for criteria and a short rationale, also for models that reason before answering. */
        const val JUDGE_MAX_OUTPUT_TOKENS = 3_000
    }
}
