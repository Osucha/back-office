package pl.mos.backoffice.tasks.support

import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.tasks.RunInputField
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.tasks.application.service.RepositoryLookup
import pl.mos.backoffice.tasks.domain.model.Prompt
import pl.mos.backoffice.tasks.domain.model.Scope
import pl.mos.backoffice.tasks.domain.model.Skill
import pl.mos.backoffice.tasks.domain.model.Slot
import pl.mos.backoffice.tasks.domain.model.SlotKind
import pl.mos.backoffice.tasks.domain.model.TaskInterface
import pl.mos.backoffice.tasks.domain.model.TaskTemplate
import java.time.Instant
import java.util.UUID

/** An in-memory library with helpers to populate it tersely. */
class Library {
    val now: Instant = Instant.parse("2026-09-26T10:00:00Z")
    val clock: Clock = Clock.fixed(now)
    val interfaces = InMemoryTaskInterfaceRepository()
    val templates = InMemoryTaskTemplateRepository()
    val skills = InMemorySkillRepository()
    val lookup = RepositoryLookup(templates, interfaces, skills)
    val projectId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    fun iface(key: String, inputs: List<Slot> = emptyList(), outputs: List<Slot> = emptyList(), ports: List<String> = TaskInterface.DEFAULT_PORTS): TaskInterface =
        interfaces.save(TaskInterface.create(key = key, name = key, inputs = inputs, outputs = outputs, ports = ports, now = now))

    fun skill(key: String, scope: Scope = Scope.Global, content: String = "# $key"): Skill =
        skills.save(Skill.create(Skill.Content(key = key, name = key, content = content, scope = scope), now))

    fun template(
        key: String,
        extendsKey: String? = null,
        abstract: Boolean = false,
        executor: TaskExecutor? = null,
        scope: Scope = Scope.Global,
        implements: List<String> = emptyList(),
        prompt: Prompt = Prompt.EMPTY,
        skills: List<String> = emptyList(),
        removeSkills: List<String> = emptyList(),
        config: Map<String, Any?> = emptyMap(),
        outputSchema: String? = null,
        confidenceField: String? = null,
        produces: List<String> = emptyList(),
        consumes: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        name: String = key,
        runInputs: List<RunInputField> = emptyList(),
    ): TaskTemplate = templates.save(
        TaskTemplate.create(
            TaskTemplate.Content(
                key = key, name = name, abstract = abstract, extendsKey = extendsKey, implements = implements, executor = executor,
                scope = scope, prompt = prompt, skills = skills, removeSkills = removeSkills, config = config, outputSchema = outputSchema,
                confidenceField = confidenceField, produces = produces, consumes = consumes, tags = tags, runInputs = runInputs,
            ),
            now,
        ),
    )

    companion object {
        fun artifact(name: String, required: Boolean = true) = Slot(name, SlotKind.ARTIFACT, required)
        fun state(name: String, required: Boolean = true) = Slot(name, SlotKind.STATE, required)
        fun input(name: String, required: Boolean = true) = Slot(name, SlotKind.INPUT, required)
    }
}
