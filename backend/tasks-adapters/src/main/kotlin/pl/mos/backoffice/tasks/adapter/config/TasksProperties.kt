package pl.mos.backoffice.tasks.adapter.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `backoffice.tasks.*` settings. */
@ConfigurationProperties(prefix = "backoffice.tasks")
data class TasksProperties(
    /** Create the built-in interfaces, templates and skills that are missing on startup (never overwrites edits). */
    val seedBuiltin: Boolean = true,
)
