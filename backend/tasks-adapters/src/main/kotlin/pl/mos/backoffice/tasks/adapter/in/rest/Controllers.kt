package pl.mos.backoffice.tasks.adapter.`in`.rest

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.tasks.application.port.`in`.ManageAgents
import pl.mos.backoffice.tasks.application.port.`in`.ManageRules
import pl.mos.backoffice.tasks.application.port.`in`.ManageSkills
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskTemplates
import pl.mos.backoffice.tasks.domain.model.AgentId
import pl.mos.backoffice.tasks.domain.model.RuleId
import pl.mos.backoffice.tasks.domain.model.SkillId
import pl.mos.backoffice.tasks.domain.model.TaskTemplateId
import java.net.URI
import java.util.UUID

/** `/api/v1/task-templates` including `/resolve/{key}` (openapi tag `task-library`). */
@RestController
@RequestMapping("/api/v1/task-templates")
class TaskTemplateController(private val templates: ManageTaskTemplates) {

    @GetMapping
    fun list(
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false, defaultValue = "true") includeAbstract: Boolean,
    ): List<TaskTemplateDto> = templates.list(projectId, includeAbstract).map(RestMappers::template)

    @PostMapping
    fun create(@Valid @RequestBody body: TaskTemplateInputDto): ResponseEntity<TaskTemplateDto> {
        val created = templates.create(RestMappers.templateContent(body))
        return ResponseEntity.created(URI.create("/api/v1/task-templates/${created.id}")).body(RestMappers.template(created))
    }

    /** Declared before `/{templateId}` so the literal segment wins over the UUID path variable. */
    @GetMapping("/resolve/{key}")
    fun resolve(@PathVariable key: String, @RequestParam(required = false) projectId: UUID?): ResolvedTaskDto =
        RestMappers.resolved(templates.resolve(key, projectId))

    /** ADR 0054: what a workflow step gets, the template with the step's overrides applied. */
    @PostMapping("/resolve/{key}")
    fun resolveStep(@PathVariable key: String, @RequestParam(required = false) projectId: UUID?, @RequestBody body: TaskOverridesDto): ResolvedTaskDto =
        RestMappers.resolved(templates.resolveStep(key, projectId, RestMappers.overrides(body)))

    @GetMapping("/{templateId}")
    fun get(@PathVariable templateId: UUID): TaskTemplateDto = RestMappers.template(templates.get(TaskTemplateId(templateId)))

    @PutMapping("/{templateId}")
    fun update(@PathVariable templateId: UUID, @Valid @RequestBody body: TaskTemplateInputDto): TaskTemplateDto =
        RestMappers.template(templates.update(TaskTemplateId(templateId), RestMappers.templateContent(body)))

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable templateId: UUID) = templates.delete(TaskTemplateId(templateId))
}

/** `/api/v1/skills` (openapi tag `task-library`). */
@RestController
@RequestMapping("/api/v1/skills")
class SkillController(private val skills: ManageSkills) {

    @GetMapping
    fun list(@RequestParam(required = false) projectId: UUID?): List<SkillDto> = skills.list(projectId).map(RestMappers::skill)

    @PostMapping
    fun create(@Valid @RequestBody body: SkillInputDto): ResponseEntity<SkillDto> {
        val created = skills.create(RestMappers.skillContent(body))
        return ResponseEntity.created(URI.create("/api/v1/skills/${created.id}")).body(RestMappers.skill(created))
    }

    @GetMapping("/{skillId}")
    fun get(@PathVariable skillId: UUID): SkillDto = RestMappers.skill(skills.get(SkillId(skillId)))

    @PutMapping("/{skillId}")
    fun update(@PathVariable skillId: UUID, @Valid @RequestBody body: SkillInputDto): SkillDto =
        RestMappers.skill(skills.update(SkillId(skillId), RestMappers.skillContent(body)))

    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable skillId: UUID) = skills.delete(SkillId(skillId))
}

/** `/api/v1/rules` (openapi tag `task-library`, ADR 0055). */
@RestController
@RequestMapping("/api/v1/rules")
class RuleController(private val rules: ManageRules) {

    @GetMapping
    fun list(@RequestParam(required = false) projectId: UUID?): List<RuleDto> = rules.list(projectId).map(RestMappers::rule)

    @PostMapping
    fun create(@Valid @RequestBody body: RuleInputDto): ResponseEntity<RuleDto> {
        val created = rules.create(RestMappers.ruleContent(body))
        return ResponseEntity.created(URI.create("/api/v1/rules/${created.id}")).body(RestMappers.rule(created))
    }

    @GetMapping("/{ruleId}")
    fun get(@PathVariable ruleId: UUID): RuleDto = RestMappers.rule(rules.get(RuleId(ruleId)))

    @PutMapping("/{ruleId}")
    fun update(@PathVariable ruleId: UUID, @Valid @RequestBody body: RuleInputDto): RuleDto =
        RestMappers.rule(rules.update(RuleId(ruleId), RestMappers.ruleContent(body)))

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable ruleId: UUID) = rules.delete(RuleId(ruleId))
}

/** `/api/v1/agents` (openapi tag `task-library`, ADR 0056). */
@RestController
@RequestMapping("/api/v1/agents")
class AgentController(private val agents: ManageAgents) {

    @GetMapping
    fun list(@RequestParam(required = false) projectId: UUID?): List<AgentDto> = agents.list(projectId).map(RestMappers::agent)

    @PostMapping
    fun create(@Valid @RequestBody body: AgentInputDto): ResponseEntity<AgentDto> {
        val created = agents.create(RestMappers.agentContent(body))
        return ResponseEntity.created(URI.create("/api/v1/agents/${created.id}")).body(RestMappers.agent(created))
    }

    @GetMapping("/{agentId}")
    fun get(@PathVariable agentId: UUID): AgentDto = RestMappers.agent(agents.get(AgentId(agentId)))

    @PutMapping("/{agentId}")
    fun update(@PathVariable agentId: UUID, @Valid @RequestBody body: AgentInputDto): AgentDto =
        RestMappers.agent(agents.update(AgentId(agentId), RestMappers.agentContent(body)))

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable agentId: UUID) = agents.delete(AgentId(agentId))
}
