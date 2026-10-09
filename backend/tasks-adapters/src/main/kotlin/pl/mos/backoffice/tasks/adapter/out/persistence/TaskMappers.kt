package pl.mos.backoffice.tasks.adapter.out.persistence

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.Slot
import pl.mos.backoffice.tasks.domain.model.SlotKind
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskInterfaceId
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Domain <-> entity mapping; JSONB payloads go through Jackson 3 as plain lists and maps. */
class TaskMappers(private val json: JsonMapper) {

    // ----- interfaces ---------------------------------------------------------------------------------

    fun toEntity(i: TaskInterface) = TaskInterfaceEntity(
        id = i.id.value,
        key = i.key,
        name = i.name,
        description = i.description,
        inputs = json.writeValueAsString(i.inputs.map(::slotMap)),
        outputs = json.writeValueAsString(i.outputs.map(::slotMap)),
        ports = json.writeValueAsString(i.ports),
        builtIn = i.builtIn,
        createdAt = i.createdAt,
        updatedAt = i.updatedAt,
        updatedByKind = i.updatedBy?.kind?.name,
        updatedById = i.updatedBy?.userId,
        updatedByName = i.updatedBy?.displayName,
    )

    fun toDomain(e: TaskInterfaceEntity) = TaskInterface(
        id = TaskInterfaceId(e.id),
        key = e.key,
        name = e.name,
        description = e.description,
        inputs = readList(e.inputs).map(::slot),
        outputs = readList(e.outputs).map(::slot),
        ports = readStrings(e.ports),
        builtIn = e.builtIn,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

    private fun slotMap(s: Slot) = mapOf("name" to s.name, "kind" to s.kind.name, "required" to s.required, "description" to s.description)

    private fun slot(raw: Any?): Slot {
        val m = raw as Map<*, *>
        return Slot(
            name = m["name"].toString(),
            kind = SlotKind.valueOf(m["kind"].toString()),
            required = (m["required"] as? Boolean) ?: true,
            description = m["description"] as? String,
        )
    }

    // ----- templates ----------------------------------------------------------------------------------

    fun toEntity(t: TaskTemplate) = TaskTemplateEntity(
        id = t.id.value,
        key = t.key,
        name = t.name,
        description = t.description,
        abstract = t.abstract,
        extendsKey = t.extendsKey,
        implements = json.writeValueAsString(t.implements),
        executor = t.executor,
        scopeType = scopeType(t.scope),
        projectId = t.scope.projectId,
        modelProfileId = t.modelProfileId?.value,
        promptSystem = t.prompt.system,
        promptUser = t.prompt.user,
        promptMode = t.prompt.mode,
        skills = json.writeValueAsString(t.skills),
        removeSkills = json.writeValueAsString(t.removeSkills),
        config = json.writeValueAsString(t.config),
        outputSchema = t.outputSchema,
        confidenceField = t.confidenceField,
        produces = json.writeValueAsString(t.produces),
        consumes = json.writeValueAsString(t.consumes),
        tags = json.writeValueAsString(t.tags),
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
        implements = readStrings(e.implements),
        executor = e.executor,
        scope = Scope.of(e.projectId),
        modelProfileId = e.modelProfileId?.let(::ModelProfileId),
        prompt = Prompt(e.promptSystem, e.promptUser, e.promptMode),
        skills = readStrings(e.skills),
        removeSkills = readStrings(e.removeSkills),
        config = readMap(e.config),
        outputSchema = e.outputSchema,
        confidenceField = e.confidenceField,
        produces = readStrings(e.produces),
        consumes = readStrings(e.consumes),
        tags = readStrings(e.tags),
        builtIn = e.builtIn,
        version = e.version,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        updatedBy = actorOf(e.updatedByKind, e.updatedById, e.updatedByName),
    )

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
