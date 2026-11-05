package pl.mos.backoffice.tasks.application.port.`in`

import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.RuleId
import java.util.UUID

/**
 * Use case: maintain rules (ADR 0055). Uniqueness is `(key, scope)`; a project rule shadows a global one with the
 * same key. A new rule without a key gets one derived from its name. Deleting a rule any template lists in `rules` is
 * refused (`InvalidStateException`).
 */
interface ManageRules {
    fun create(command: Rule.Content): Rule
    fun update(id: RuleId, command: Rule.Content): Rule
    fun get(id: RuleId): Rule
    /** Global rules plus, when [projectId] is given, that project's own. */
    fun list(projectId: UUID? = null): List<Rule>
    fun delete(id: RuleId)
}
