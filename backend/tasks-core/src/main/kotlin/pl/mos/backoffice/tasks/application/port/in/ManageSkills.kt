package pl.mos.backoffice.tasks.application.port.`in`

import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import java.util.UUID

/**
 * Use case: maintain Agent Skills. Uniqueness is `(key, scope)`; a project skill shadows a global one
 * with the same key. Deleting a skill any template lists in `skills` is refused (`InvalidStateException`).
 */
interface ManageSkills {
    fun create(command: Skill.Content): Skill
    fun update(id: SkillId, command: Skill.Content): Skill
    fun get(id: SkillId): Skill
    /** Global skills plus, when [projectId] is given, that project's own. */
    fun list(projectId: UUID? = null): List<Skill>
    fun delete(id: SkillId)
}
