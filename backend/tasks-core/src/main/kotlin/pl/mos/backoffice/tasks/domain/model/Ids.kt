package pl.mos.backoffice.tasks.domain.model

import java.util.UUID

/** Identifiers of the Task Library context. Templates and skills are addressed by `key` from outside; ids are for REST CRUD. */
@JvmInline
value class TaskInterfaceId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = TaskInterfaceId(UUID.randomUUID())
        fun of(value: String) = TaskInterfaceId(UUID.fromString(value))
    }
}

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
