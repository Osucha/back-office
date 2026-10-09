package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/**
 * Visibility of a template or skill. A `Project` entry with the same key as a `Global` one shadows it
 * for that project only (docs/architecture/07-task-library.md, rule 1).
 */
sealed class Scope {
    abstract val projectId: UUID?

    data object Global : Scope() {
        override val projectId: UUID? get() = null
    }

    data class Project(override val projectId: UUID) : Scope()

    companion object {
        fun of(projectId: UUID?): Scope = if (projectId == null) Global else Project(projectId)
    }
}

/** Keys of interfaces, templates and skills: lower-case slug, 2..64 characters. */
object Keys {
    val PATTERN: Regex = Regex("^[a-z][a-z0-9-]{1,63}$")

    fun isValid(key: String): Boolean = PATTERN.matches(key)

    fun require(key: String, field: String = "key") {
        if (!isValid(key)) throw ValidationException("must match ${PATTERN.pattern}", field)
    }
}

/** What a slot of an interface refers to: a workflow input, a `state.*` value or a named artefact. */
enum class SlotKind { INPUT, STATE, ARTIFACT }

/** One input or output of a [TaskInterface]. */
data class Slot(
    val name: String,
    val kind: SlotKind,
    val required: Boolean = true,
    val description: String? = null,
) {
    init {
        if (name.isBlank()) throw ValidationException("slot name must not be blank", "name")
    }
}
