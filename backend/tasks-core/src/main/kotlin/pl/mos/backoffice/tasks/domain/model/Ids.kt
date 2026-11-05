package pl.mos.backoffice.tasks.domain.model

import java.util.UUID

/** Identifiers of the Task Library context. Templates, skills and rules are addressed by `key` from outside; ids are for REST CRUD. */
@JvmInline
value class TaskTemplateId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = TaskTemplateId(UUID.randomUUID())
        fun of(value: String) = TaskTemplateId(UUID.fromString(value))
    }
}

@JvmInline
value class SkillId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = SkillId(UUID.randomUUID())
        fun of(value: String) = SkillId(UUID.fromString(value))
    }
}

@JvmInline
value class RuleId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = RuleId(UUID.randomUUID())
        fun of(value: String) = RuleId(UUID.fromString(value))
    }
}
