package pl.mos.backoffice.evaluation.domain.model

import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import java.time.Duration
import java.time.Instant

enum class TaskCategory { CODING, ANALYSIS, SPECIFICATION, REVIEW, ARCHITECTURE }

enum class Difficulty { EASY, MEDIUM, HARD, EXPERT }

/** `PROMPT`: one LLM call judged by rubric. `CODING_JOB`: agent runner job judged by verification command and rubric. */
enum class TaskKind { PROMPT, CODING_JOB }

private const val MUST_NOT_BE_BLANK = "must not be blank"

/** Repository a `CODING_JOB` task is executed against. */
data class TaskRepository(
    val url: String,
    val baseRef: String = "main",
    val verifyCommand: String,
    val setupCommand: String? = null,
) {
    init {
        val errors = mutableListOf<ValidationException.FieldError>()
        if (url.isBlank()) errors += ValidationException.FieldError("repository.url", MUST_NOT_BE_BLANK)
        if (baseRef.isBlank()) errors += ValidationException.FieldError("repository.baseRef", MUST_NOT_BE_BLANK)
        if (verifyCommand.isBlank()) errors += ValidationException.FieldError("repository.verifyCommand", MUST_NOT_BE_BLANK)
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }
}

/**
 * How a coding attempt score is combined: `verification * (passed ? 100 : 0) + judge * judgeScore`.
 * Weights must sum to 1.
 */
data class CodingScoreWeights(val verification: Double = 0.6, val judge: Double = 0.4) {
    init {
        if (verification < 0 || judge < 0) throw ValidationException("weights must not be negative", "codingScoreWeights")
        if (kotlin.math.abs(verification + judge - 1.0) > 0.0001) throw ValidationException("weights must sum to 1", "codingScoreWeights")
    }

    companion object {
        val DEFAULT = CodingScoreWeights()
    }
}

/** One unit of work in a suite. Entity owned by [TaskSuite]. */
data class EvalTask(
    val id: EvalTaskId,
    val name: String,
    val category: TaskCategory,
    val difficulty: Difficulty,
    val kind: TaskKind,
    val instructions: String,
    val context: String? = null,
    val rubric: String,
    val referenceAnswer: String? = null,
    val repository: TaskRepository? = null,
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val weight: Double = 1.0,
) {
    init {
        val errors = mutableListOf<ValidationException.FieldError>()
        if (name.isBlank()) errors += ValidationException.FieldError("name", MUST_NOT_BE_BLANK)
        if (instructions.isBlank()) errors += ValidationException.FieldError("instructions", MUST_NOT_BE_BLANK)
        if (rubric.isBlank()) errors += ValidationException.FieldError("rubric", MUST_NOT_BE_BLANK)
        if (kind == TaskKind.CODING_JOB && repository == null) errors += ValidationException.FieldError("repository", "required for CODING_JOB tasks")
        if (timeout.isNegative || timeout.isZero) errors += ValidationException.FieldError("timeoutSeconds", "must be positive")
        if (weight <= 0) errors += ValidationException.FieldError("weight", "must be positive")
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    /** Instructions plus optional context, as sent to the candidate model or agent. */
    val prompt: String
        get() = if (context.isNullOrBlank()) instructions else "$instructions\n\n## Context\n\n$context"

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 900L
    }
}

/** Aggregate root: a named group of tasks that is benchmarked together. */
data class TaskSuite(
    val id: TaskSuiteId,
    val name: String,
    val description: String? = null,
    val codingScoreWeights: CodingScoreWeights = CodingScoreWeights.DEFAULT,
    val tasks: List<EvalTask> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Who changed the suite or one of its tasks last (ADR 0017); null for rows written before actor stamps. */
    val updatedBy: Actor? = null,
) {
    init {
        if (name.isBlank()) throw ValidationException(MUST_NOT_BE_BLANK, "name")
        val duplicate = tasks.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()
        if (duplicate != null) throw ValidationException("duplicate task id $duplicate", "tasks")
    }

    fun update(name: String, description: String?, codingScoreWeights: CodingScoreWeights, now: Instant, actor: Actor? = null): TaskSuite =
        copy(name = name, description = description, codingScoreWeights = codingScoreWeights, updatedAt = now, updatedBy = actor)

    fun addTask(task: EvalTask, now: Instant, actor: Actor? = null): TaskSuite = copy(tasks = tasks + task, updatedAt = now, updatedBy = actor)

    fun updateTask(task: EvalTask, now: Instant, actor: Actor? = null): TaskSuite {
        requireTask(task.id)
        return copy(tasks = tasks.map { if (it.id == task.id) task else it }, updatedAt = now, updatedBy = actor)
    }

    fun removeTask(taskId: EvalTaskId, now: Instant, actor: Actor? = null): TaskSuite {
        requireTask(taskId)
        return copy(tasks = tasks.filterNot { it.id == taskId }, updatedAt = now, updatedBy = actor)
    }

    fun task(taskId: EvalTaskId): EvalTask = tasks.firstOrNull { it.id == taskId } ?: throw NotFoundException("EvalTask", taskId)

    private fun requireTask(taskId: EvalTaskId) {
        if (tasks.none { it.id == taskId }) throw NotFoundException("EvalTask", taskId)
    }

    companion object {
        fun create(
            name: String,
            description: String? = null,
            codingScoreWeights: CodingScoreWeights = CodingScoreWeights.DEFAULT,
            tasks: List<EvalTask> = emptyList(),
            now: Instant,
            id: TaskSuiteId = TaskSuiteId.new(),
            actor: Actor? = null,
        ): TaskSuite = TaskSuite(id, name, description, codingScoreWeights, tasks, now, now, actor)
    }
}
