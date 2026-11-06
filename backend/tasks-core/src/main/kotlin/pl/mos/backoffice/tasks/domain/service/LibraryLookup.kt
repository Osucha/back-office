package pl.mos.backoffice.tasks.domain.service

import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * Read access the pure domain services need. Implemented over the repositories by the application
 * layer and over maps in tests. Exact-scope lookups only; project shadowing is applied by the
 * `visible*` helpers so every caller uses the same rule.
 */
interface LibraryLookup {
    fun template(key: String, scope: Scope): TaskTemplate?
    fun skill(key: String, scope: Scope): Skill?
    fun rule(key: String, scope: Scope): Rule?
    fun agent(key: String, scope: Scope): Agent?

    /** Automatic rules stored in exactly [scope] (ADR 0055). */
    fun automaticRules(scope: Scope): List<Rule>

    /** The template a project sees under `key`: its own `PROJECT` entry, else the `GLOBAL` one. */
    fun visibleTemplate(key: String, projectId: UUID?): TaskTemplate? =
        projectId?.let { template(key, Scope.Project(it)) } ?: template(key, Scope.Global)

    /** Same shadowing rule for skills. */
    fun visibleSkill(key: String, projectId: UUID?): Skill? =
        projectId?.let { skill(key, Scope.Project(it)) } ?: skill(key, Scope.Global)

    /** Same shadowing rule for rules. */
    fun visibleRule(key: String, projectId: UUID?): Rule? =
        projectId?.let { rule(key, Scope.Project(it)) } ?: rule(key, Scope.Global)

    /** Same shadowing rule for agents (ADR 0056). */
    fun visibleAgent(key: String, projectId: UUID?): Agent? =
        projectId?.let { agent(key, Scope.Project(it)) } ?: agent(key, Scope.Global)

    /**
     * Automatic rules a project's steps get: global ones, then the project's own; a project rule with the key of a
     * global one replaces it in place, and a global automatic rule the project shadows with a non-automatic one is off.
     */
    fun visibleAutomaticRules(projectId: UUID?): List<Rule> {
        val global = automaticRules(Scope.Global)
        if (projectId == null) return global
        val project = Scope.Project(projectId)
        val own = automaticRules(project).associateBy { it.key }
        // a global rule the project shadows: its automatic project copy takes its place, a non-automatic copy turns it off
        val inherited = global.mapNotNull { g -> own[g.key] ?: g.takeIf { rule(g.key, project) == null } }
        return inherited + own.values.filter { o -> global.none { it.key == o.key } }
    }
}
