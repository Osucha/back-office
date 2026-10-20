package pl.mos.backoffice.shared.usage

import pl.mos.backoffice.shared.ValidationException

/**
 * Column sorting of a server-paged list (query parameter `sort=field.asc|field.desc`, the same value the UI keeps in its
 * URL). Each list declares the fields it can sort by and maps them to its own columns; without a sort it keeps its
 * natural order (usually newest first).
 */
data class SortSpec(val field: String, val descending: Boolean) {

    companion object {
        /** Null for a blank value; a ValidationException for an unknown field or a malformed value. */
        fun parse(raw: String?, allowed: Collection<String>): SortSpec? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val dot = value.lastIndexOf('.')
            val field = if (dot > 0) value.substring(0, dot) else value
            val direction = if (dot > 0) value.substring(dot + 1).lowercase() else "asc"
            if (field !in allowed || direction !in setOf("asc", "desc")) {
                throw ValidationException("sort must be one of ${allowed.joinToString { "$it.asc|$it.desc" }}", "sort")
            }
            return SortSpec(field, direction == "desc")
        }
    }
}
