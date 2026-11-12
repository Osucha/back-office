package pl.mos.backoffice.tasks.adapter.out.persistence

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TemplateParameter
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.runner.AgentMcpServer
import pl.mos.backoffice.tasks.domain.model.Agent
import pl.mos.backoffice.tasks.domain.model.AgentId
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Rule
import pl.mos.backoffice.tasks.domain.model.RuleId
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Domain <-> entity mapping; JSONB payloads go through Jackson 3 as plain lists and maps. */
class TaskMappers(private val json: JsonMapper) {

    // ----- templates ----------------------------------------------------------------------------------

    fun toEntity(t: TaskTemplate) = TaskTemplateEntity(
        id = t.id.value,
        key = t.key,
        name = t.name,
        description = t.description,
        abstract = t.abstract,
        extendsKey = t.extendsKey,
        category = t.category,
        scopeType = scopeType(t.scope),
        projectId = t.scope.projectId,
        promptSystem = t.prompt.system,
        promptUser = t.prompt.user,
        promptMode = t.prompt.mode,
        config = json.writeValueAsString(t.config),
        outputSchema = t.outputSchema,
        confidenceField = t.confidenceField,
        produces = json.writeValueAsString(t.produces),
        consumes = json.writeValueAsString(t.consumes),
        tags = json.writeValueAsString(t.tags),
        runInputs = json.writeValueAsString(t.runInputs.map(::runInputMap)),
        parameters = json.writeValueAsString(t.parameters.map(::parameterMap)),
        parameterValues = json.writeValueAsString(t.parameterValues),
        requiresSkills = json.writeValueAsString(t.requiresSkills),
        builtIn = t.builtIn,
        version = t.version,
        createdAt = t.createdAt,
        updatedAt = t.updatedAt,
        updatedByKind = t.updatedBy?.kind?.name,
        updatedById = t.updatedBy?.userId,
        updatedByName = t.updatedBy?.displayName,
    )

    fun toDomain(e: TaskTemplateEntity) = TaskTemplate(
        id = TaskTemplateId(e.id),
        key = e.key,
        name = e.name,
        description = e.description,
        abstract = e.abstract,
        extendsKey = e.extendsKey,
        category = e.category,
        scope = Scope.of(e.projectId),
        prompt = Prompt(e.promptSystem, e.promptUser, e.promptMode),
        config = readMap(e.config),
        outputSchema = e.outputSchema,
        confidenceField = e.confidenceField,
        produces = readStrings(e.produces),
        consumes = readStrings(e.consumes),
        tags = readStrings(e.tags),
        runInputs = readList(e.runInputs).mapNotNull(::runInput),
        builtIn = e.builtIn,
        version = e.version,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
        parameters = readList(e.parameters).mapNotNull(::parameter),
        parameterValues = readMap(e.parameterValues),
        requiresSkills = readStrings(e.requiresSkills),
    )

    private fun runInputMap(f: RunInputField) =
        mapOf("name" to f.name, "label" to f.label, "type" to f.type.name, "required" to f.required, "help" to f.help)

    /**
     * One stored run input, or null for an entry that is not an object or no longer forms a valid field (no name, a
     * name or label the domain refuses): one bad entry must not break every workflow that resolves the template. An
     * unknown type reads as TEXT.
     */
    private fun runInput(raw: Any?): RunInputField? {
        val m = raw as? Map<*, *> ?: return null
        val name = m["name"] as? String ?: return null
        val label = m["label"] as? String ?: return null
        return try {
            RunInputField(
                name = name,
                label = label,
                type = RunInputType.entries.firstOrNull { it.name == m["type"]?.toString() } ?: RunInputType.TEXT,
                required = (m["required"] as? Boolean) ?: false,
                help = m["help"] as? String,
            )
        } catch (_: ValidationException) {
            null
        }
    }

    private fun parameterMap(p: TemplateParameter) = mapOf(
        "name" to p.name, "label" to p.label, "type" to p.type.name, "required" to p.required,
        "default" to p.default, "options" to p.options, "help" to p.help,
    )

    /** ADR 0058: one stored parameter, or null for an entry that no longer forms a valid one (like [runInput]). */
    private fun parameter(raw: Any?): TemplateParameter? {
        val m = raw as? Map<*, *> ?: return null
        val name = m["name"] as? String ?: return null
        return try {
            TemplateParameter(
                name = name,
                label = m["label"] as? String ?: name,
                type = TemplateParameterType.entries.firstOrNull { it.name == m["type"]?.toString() } ?: TemplateParameterType.TEXT,
                required = (m["required"] as? Boolean) ?: false,
                default = m["default"],
                options = (m["options"] as? List<*>).orEmpty().mapNotNull { it?.toString() },
                help = m["help"] as? String,
            )
        } catch (_: ValidationException) {
            null
        }
    }

    /** ADR 0057: one stored MCP server, or null for an entry the domain refuses now. */
    private fun mcpServer(raw: Any?): AgentMcpServer? {
        val m = raw as? Map<*, *> ?: return null
        return try {
            AgentMcpServer(m["name"]?.toString() ?: return null, m["url"]?.toString() ?: return null)
        } catch (_: ValidationException) {
            null
        }
    }

    // ----- skills -------------------------------------------------------------------------------------

    fun toEntity(s: Skill) = SkillEntity(
        id = s.id.value,
        key = s.key,
        name = s.name,
        description = s.description,
        content = s.content,
        scopeType = scopeType(s.scope),
        projectId = s.scope.projectId,
        tags = json.writeValueAsString(s.tags),
        builtIn = s.builtIn,
        createdAt = s.createdAt,
        updatedAt = s.updatedAt,
        updatedByKind = s.updatedBy?.kind?.name,
        updatedById = s.updatedBy?.userId,
        updatedByName = s.updatedBy?.displayName,
    )

    fun toDomain(e: SkillEntity) = Skill(
        id = SkillId(e.id),
        key = e.key,
        name = e.name,
        description = e.description,
        content = e.content,
        scope = Scope.of(e.projectId),
        tags = readStrings(e.tags),
        builtIn = e.builtIn,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

    // ----- rules (ADR 0055) --------------------------------------------------------------------------

    fun toEntity(r: Rule) = RuleEntity(
        id = r.id.value,
        key = r.key,
        name = r.name,
        description = r.description,
        content = r.content,
        scopeType = scopeType(r.scope),
        projectId = r.scope.projectId,
        automatic = r.automatic,
        tags = json.writeValueAsString(r.tags),
        builtIn = r.builtIn,
        createdAt = r.createdAt,
        updatedAt = r.updatedAt,
        updatedByKind = r.updatedBy?.kind?.name,
        updatedById = r.updatedBy?.userId,
        updatedByName = r.updatedBy?.displayName,
    )

    fun toDomain(e: RuleEntity) = Rule(
        id = RuleId(e.id),
        key = e.key,
        name = e.name,
        description = e.description,
        content = e.content,
        scope = Scope.of(e.projectId),
        automatic = e.automatic,
        tags = readStrings(e.tags),
        builtIn = e.builtIn,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

    // ----- agents (ADR 0056) -------------------------------------------------------------------------

    fun toEntity(a: Agent) = AgentEntity(
        id = a.id.value,
        key = a.key,
        name = a.name,
        description = a.description,
        scopeType = scopeType(a.scope),
        projectId = a.scope.projectId,
        harness = a.harness,
        instructions = a.instructions,
        rules = json.writeValueAsString(a.rules),
        skills = json.writeValueAsString(a.skills),
        mcpServers = json.writeValueAsString(a.mcpServers.map { mapOf("name" to it.name, "url" to it.url) }),
        access = json.writeValueAsString(mapOf("mode" to a.access.mode.name, "paths" to a.access.paths)),
        modelProfileId = a.modelProfileId?.value,
        tags = json.writeValueAsString(a.tags),
        builtIn = a.builtIn,
        createdAt = a.createdAt,
        updatedAt = a.updatedAt,
        updatedByKind = a.updatedBy?.kind?.name,
        updatedById = a.updatedBy?.userId,
        updatedByName = a.updatedBy?.displayName,
    )

    fun toDomain(e: AgentEntity) = Agent(
        id = AgentId(e.id),
        key = e.key,
        name = e.name,
        description = e.description,
        scope = Scope.of(e.projectId),
        harness = e.harness,
        instructions = e.instructions,
        rules = readStrings(e.rules),
        skills = readStrings(e.skills),
        mcpServers = readList(e.mcpServers).mapNotNull(::mcpServer),
        access = access(e.access),
        modelProfileId = e.modelProfileId?.let(::ModelProfileId),
        tags = readStrings(e.tags),
        builtIn = e.builtIn,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

    /**
     * ADR 0060: the stored access of an agent; anything unreadable (unknown mode, PATHS without valid paths) reads as
     * READ_ONLY, never as more than was stored.
     */
    private fun access(text: String): AgentAccess {
        val map = runCatching { readMap(text) }.getOrNull() ?: return AgentAccess.FULL
        val mode = AgentAccessMode.entries.firstOrNull { it.name == map["mode"]?.toString() } ?: return AgentAccess.READ_ONLY
        val paths = (map["paths"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        return runCatching { AgentAccess(mode, if (mode == AgentAccessMode.PATHS) paths else emptyList()) }.getOrElse { AgentAccess.READ_ONLY }
    }

    // ----- helpers ------------------------------------------------------------------------------------

    fun scopeType(scope: Scope): ScopeType = if (scope is Scope.Project) ScopeType.PROJECT else ScopeType.GLOBAL

    /** `updated_by_*` columns back to an [Actor]; rows from before V301 have none. Unknown kinds read as SYSTEM. */
    private fun actorOf(kind: String?, id: UUID?, name: String?): Actor? = kind?.let {
        Actor(PrincipalKind.entries.firstOrNull { k -> k.name == it } ?: PrincipalKind.SYSTEM, id, name ?: "")
    }

    @Suppress("UNCHECKED_CAST")
    private fun readMap(text: String): Map<String, Any?> = json.readValue(text, LinkedHashMap::class.java) as Map<String, Any?>

    private fun readList(text: String): List<Any?> = json.readValue(text, List::class.java)

    private fun readStrings(text: String): List<String> = readList(text).map { it.toString() }
}
