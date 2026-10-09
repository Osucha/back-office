package pl.mos.backoffice.evaluation.domain.model

import java.util.UUID

/**
 * Identifiers of the Model Evaluation context. `ModelProfileId` lives in the shared kernel because the
 * Projects context refers to it; everything else is local to this context.
 */
@JvmInline
value class TaskSuiteId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = TaskSuiteId(UUID.randomUUID())
        fun of(value: String) = TaskSuiteId(UUID.fromString(value))
    }
}

@JvmInline
value class EvalTaskId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = EvalTaskId(UUID.randomUUID())
        fun of(value: String) = EvalTaskId(UUID.fromString(value))
    }
}

@JvmInline
value class BenchmarkId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = BenchmarkId(UUID.randomUUID())
        fun of(value: String) = BenchmarkId(UUID.fromString(value))
    }
}

@JvmInline
value class AttemptId(val value: UUID) {
    override fun toString(): String = value.toString()
    /** First 8 characters, used in runner job labels and eval branch names. */
    val short: String get() = value.toString().substring(0, 8)
    companion object {
        fun new() = AttemptId(UUID.randomUUID())
        fun of(value: String) = AttemptId(UUID.fromString(value))
    }
}
