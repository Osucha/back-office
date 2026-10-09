package pl.mos.backoffice.tasks.adapter.out.catalog

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.application.service.RepositoryLookup
import pl.mos.backoffice.tasks.application.service.TaskCatalogService
import java.util.UUID

/**
 * The one `TaskCatalog` bean other contexts (Projects) inject. Wraps the core `TaskCatalogService`
 * (repositories + resolver) in a read-only transaction so a resolution reads a consistent snapshot of
 * the library. The service is owned here, not exposed as a bean, so the port has a single implementation.
 */
@Component
@Transactional(readOnly = true)
class TaskCatalogAdapter(templates: TaskTemplateRepository, lookup: RepositoryLookup) : TaskCatalog {
    private val service = TaskCatalogService(templates, lookup)

    override fun resolve(key: String, projectId: UUID?, overrides: TaskOverrides): ResolvedTask? = service.resolve(key, projectId, overrides)
    override fun palette(projectId: UUID?): List<TaskSummary> = service.palette(projectId)
}
