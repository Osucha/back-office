package pl.mos.backoffice.tasks.application.service

import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.SystemOperation
import pl.mos.backoffice.shared.tasks.AgentEnvironmentSnapshot
import pl.mos.backoffice.shared.tasks.AgentProfile
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.RequiredSkill
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.SkillInfo
import pl.mos.backoffice.shared.tasks.StepNeeds
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskOverrides
import pl.mos.backoffice.shared.tasks.TaskSummary
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.AgentId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.util.UUID

/**
 * The shared `TaskCatalog` port as seen by the Projects context. `resolve` is the resolver with the
 * executability check; `palette` lists every concrete template the project sees (project entries shadow
 * global ones with the same key), skipping templates whose chain cannot be resolved. ADR 0061: [stepNeeds] and
 * [agentProfile] feed the agent audit and the coherence check; [agents] (null in tests) finds agents by id.
 */
class TaskCatalogService(
    private val templates: TaskTemplateRepository,
    private val lookup: RepositoryLookup,
    private val agents: AgentRepository? = null,
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

    @SystemOperation("TaskCatalog port for the Projects context (ADR 0061): the agent audit authorises the caller on the project")
    override fun stepNeeds(key: String, projectId: UUID?, overrides: TaskOverrides): StepNeeds? {
        val resolution = previewOrNull(key, projectId, overrides) ?: return null
        return StepNeeds(
            agentKey = resolution.agent.key,
            requiredSkills = resolution.requiresSkills.map { RequiredSkill(it, lookup.visibleSkill(it, projectId)?.name) },
            needsCodingAgent = resolution.needsCodingAgent,
            pushes = resolution.pushes,
        )
    }

    @SystemOperation("TaskCatalog port for the Projects context (ADR 0061): the agent audit authorises the caller on the project")
    override fun agentProfile(key: String, projectId: UUID?): AgentProfile? {
        val stored = lookup.visibleAgent(key, projectId)
        if (stored == null && key != AgentSnapshot.MODEL_ANSWER) return null
        return profile(key, stored, projectId)
    }

    @SystemOperation("TaskCatalog port for the Projects context (ADR 0061): the coherence check authorises the caller on the agent and the project")
    override fun agentProfileById(id: UUID, projectId: UUID?): AgentProfile? {
        val stored = agents?.findById(AgentId(id)) ?: return null
        val seenFrom = stored.scope.projectId ?: projectId
        return profile(stored.key, stored, seenFrom)
    }

    private fun profile(key: String, stored: Agent?, projectId: UUID?): AgentProfile {
        val environment = lookup.resolver.environment(key, projectId)
        return AgentProfile(
            id = stored?.id?.value,
            agent = environment.agent,
            projectId = stored?.scope?.projectId,
            builtIn = stored?.builtIn ?: true,
            rules = environment.rules,
            skills = environment.skills.map { SkillInfo(it.key, it.name, environment.skillDescriptions[it.key].orEmpty()) },
            unknownRules = environment.unknownRules,
            unknownSkills = environment.unknownSkills,
        )
    }

    private fun previewOrNull(key: String, projectId: UUID?, overrides: TaskOverrides = TaskOverrides.NONE) = try {
        lookup.resolver.preview(key, projectId, overrides)
    } catch (_: ValidationException) {
        null
    }

    private fun summary(template: TaskTemplate, projectId: UUID?): TaskSummary? {
        val resolution = previewOrNull(template.key, projectId) ?: return null
        return TaskSummary(
            key = template.key,
            name = template.name,
            description = template.description,
            category = resolution.category,
            abstract = false,
            projectId = template.scope.projectId,
            ports = resolution.ports,
            // ADR 0054: listed (steps that use it read their settings from the palette) but flagged, so no one picks it
            deprecated = BuiltInLibrary.DEPRECATED_TAG in template.tags,
            parameters = resolution.parameters.map { it.definition },
            parameterValues = resolution.templateParameterValues,
            needsParameters = resolution.needsParameters,
            needsCodingAgent = resolution.needsCodingAgent,
            requiresSkills = resolution.requiresSkills,
        )
    }
}
