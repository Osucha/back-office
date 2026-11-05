package pl.mos.backoffice.tasks.domain.model

import pl.mos.backoffice.shared.ValidationException
import java.text.Normalizer
import java.util.UUID

/**
 * Visibility of a template, skill or rule. A `Project` entry with the same key as a `Global` one shadows it
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

/**
 * Keys of templates, skills and rules: lower-case slug, 2..64 characters. People do not type them (ADR 0055): a new
 * entry gets one derived from its name by [fromName], made unique by [unique].
 */
object Keys {
    val PATTERN: Regex = Regex("^[a-z][a-z0-9-]{1,63}$")

    /** Room left for a `-<n>` suffix added by [unique]. */
    private const val MAX_BASE = 58

    fun isValid(key: String): Boolean = PATTERN.matches(key)

    fun require(key: String, field: String = "key") {
        if (!isValid(key)) throw ValidationException("must match ${PATTERN.pattern}", field)
    }

    /**
     * A valid key from a display name: diacritics dropped (`ł` and `ø` mapped by hand, NFD does not decompose them),
     * everything but letters and digits becomes `-`. [prefix] goes first when the name yields nothing usable or
     * starts with a digit, e.g. `"2FA check"` -> `"task-2fa-check"`.
     */
    fun fromName(name: String, prefix: String): String {
        val ascii = Normalizer.normalize(name.replace('ł', 'l').replace('Ł', 'L').replace('ø', 'o').replace('Ø', 'O'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        var slug = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isEmpty() || !slug.first().isLetter() || slug.length < 2) slug = if (slug.isEmpty()) prefix else "$prefix-$slug"
        return slug.take(MAX_BASE).trimEnd('-')
    }

    /** [base] when [taken] says it is free, else the first free `base-2`, `base-3`, ... */
    fun unique(base: String, taken: (String) -> Boolean): String {
        if (!taken(base)) return base
        var n = 2
        while (taken("$base-$n")) n++
        return "$base-$n"
    }
}
