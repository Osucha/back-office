package pl.mos.backoffice.shared

import java.util.UUID

/**
 * Identifiers shared by more than one bounded context. Context-local identifiers live in their own
 * context (e.g. `ProjectId` in projects-core). All identifiers follow the same shape: a value class
 * over a UUID with `new()` and `of(String)` factories.
 */
@JvmInline
value class ModelProfileId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new(): ModelProfileId = ModelProfileId(UUID.randomUUID())
        fun of(value: String): ModelProfileId = ModelProfileId(UUID.fromString(value))
    }
}

@JvmInline
value class RunnerJobId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new(): RunnerJobId = RunnerJobId(UUID.randomUUID())
        fun of(value: String): RunnerJobId = RunnerJobId(UUID.fromString(value))
    }
}
