package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.tasks.AgentEnvironmentSnapshot
import pl.mos.backoffice.shared.tasks.AgentSnapshot
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

    @SystemOperation("TaskCatalog port for the Evaluation context (ADR 0059): benchmark candidates; the benchmark use case authorises the caller")
    override fun agent(key: String, projectId: UUID?): AgentSnapshot? = lookup.visibleAgent(key, projectId)?.snapshot

    @SystemOperation("TaskCatalog port for the Evaluation context (ADR 0059): a candidate's environment; the attempt runs as the system after an authorised start")
    override fun agentEnvironment(key: String, projectId: UUID?): AgentEnvironmentSnapshot? {
        val resolved = lookup.resolver.environment(key, projectId)
        if (!resolved.found) return null
        return AgentEnvironmentSnapshot(resolved.agent, resolved.rules, resolved.skills)
    }

    private fun summary(template: TaskTemplate, projectId: UUID?): TaskSummary? {
        val resolution = try {
            lookup.resolver.preview(template.key, projectId)
        } catch (_: ValidationException) {
            null
        } ?: return null
        return TaskSummary(
            key = template.key,
            name = template.name,
            description = template.description,
            agent = resolution.agent,
            category = resolution.category,
            abstract = false,
            projectId = template.scope.projectId,
            ports = resolution.ports,
            // ADR 0054: listed (steps that use it read their settings from the palette) but flagged, so no one picks it
            deprecated = BuiltInLibrary.DEPRECATED_TAG in template.tags,
            parameters = resolution.parameters.map { it.definition },
            parameterValues = resolution.templateParameterValues,
            needsParameters = resolution.needsParameters,
        )
    }
}
