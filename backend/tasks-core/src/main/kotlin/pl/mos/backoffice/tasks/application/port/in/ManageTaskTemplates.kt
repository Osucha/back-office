package pl.mos.backoffice.tasks.application.port.`in`

import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import pl.mos.backoffice.tasks.domain.service.TaskResolution
import java.util.UUID

/**
 * Use case: maintain templates and preview what an agent receives. Every save runs the
 * `TemplateValidator`; updates bump `version`. Built-in templates can be edited and shadowed per
 * project but not deleted (`InvalidStateException`), and neither can a template other templates extend.
 */
interface ManageTaskTemplates {
    /** Fails with `InvalidStateException` when `(key, scope)` already exists. */
    fun create(command: TaskTemplate.Content): TaskTemplate
    fun update(id: TaskTemplateId, command: TaskTemplate.Content): TaskTemplate
    fun get(id: TaskTemplateId): TaskTemplate
    /** Global templates plus, when [projectId] is given, that project's own; abstract ones only when [includeAbstract]. */
    fun list(projectId: UUID? = null, includeAbstract: Boolean = true): List<TaskTemplate>
    fun delete(id: TaskTemplateId)

    /**
     * Merged view of the template a project sees under [key], including abstract templates and ones whose
     * interface check fails (the caller reads `interfaceCheck` / `abstract`). `NotFoundException` when
     * unknown; `ValidationException` on inheritance cycles or missing parents.
     */
    fun resolve(key: String, projectId: UUID? = null): TaskResolution
}
