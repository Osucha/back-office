package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * The shared `TaskCatalog` port as seen by the Projects context. `resolve` is the resolver with the
 * executability check; `palette` lists every concrete template the project sees (project entries shadow
 * global ones with the same key), skipping templates whose chain cannot be resolved.
 */
class TaskCatalogService(
    private val templates: TaskTemplateRepository,
    private val lookup: RepositoryLookup,
) : TaskCatalog {

    @SystemOperation("TaskCatalog port for the Projects context: the workflow engine resolves tasks as the system; callers authorise their own use case")
    override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? =
        lookup.resolver.resolve(key, projectId, overrides)

    @SystemOperation("TaskCatalog port for the Projects context: the designer palette is authorised by the calling workflow use case")
    override fun palette(projectId: UUID?): List<TaskSummary> = templates.findVisible(projectId)
        .filter { !it.abstract }
        .groupBy { it.key }
        .values
        .map { candidates -> candidates.firstOrNull { it.scope.projectId != null } ?: candidates.first() }
        .sortedBy { it.key }
        .mapNotNull { summary(it, projectId) }

    private fun summary(template: TaskTemplate, projectId: UUID?): TaskSummary? {
        val resolution = try {
            lookup.resolver.preview(template.key, projectId)
        } catch (_: ValidationException) {
            null
        } ?: return null
        val executor = resolution.executor ?: return null
        return TaskSummary(
            key = template.key,
            name = template.name,
            description = template.description,
            executor = executor,
            interfaces = resolution.interfaces,
            abstract = false,
            projectId = template.scope.projectId,
            ports = resolution.ports,
        )
    }
}
