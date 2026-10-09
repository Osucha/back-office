package pl.mos.backoffice.tasks.domain.service

import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * Read access the pure domain services need. Implemented over the repositories by the application
 * layer and over maps in tests. Exact-scope lookups only; project shadowing is applied by the
 * `visible*` helpers so every caller uses the same rule.
 */
interface LibraryLookup {
    fun template(key: String, scope: Scope): TaskTemplate?
    fun taskInterface(key: String): TaskInterface?
    fun skill(key: String, scope: Scope): Skill?

    /** The template a project sees under `key`: its own `PROJECT` entry, else the `GLOBAL` one. */
    fun visibleTemplate(key: String, projectId: UUID?): TaskTemplate? =
        projectId?.let { template(key, Scope.Project(it)) } ?: template(key, Scope.Global)

    /** Same shadowing rule for skills. */
    fun visibleSkill(key: String, projectId: UUID?): Skill? =
        projectId?.let { skill(key, Scope.Project(it)) } ?: skill(key, Scope.Global)
}
