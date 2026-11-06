package pl.mos.backoffice.tasks.adapter.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.tasks.application.TasksPermissions
import pl.mos.backoffice.tasks.adapter.out.persistence.TaskMappers
import pl.mos.backoffice.tasks.application.port.`in`.ManageAgents
import pl.mos.backoffice.tasks.application.port.`in`.ManageRules
import pl.mos.backoffice.tasks.application.port.`in`.ManageSkills
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskTemplates
import pl.mos.backoffice.tasks.application.port.`in`.SeedTaskLibrary
import pl.mos.backoffice.tasks.application.port.out.AgentRepository
import pl.mos.backoffice.tasks.application.port.out.RuleRepository
import pl.mos.backoffice.tasks.application.port.out.SkillRepository
import pl.mos.backoffice.tasks.application.port.out.TaskTemplateRepository
import pl.mos.backoffice.tasks.application.service.AgentService
import pl.mos.backoffice.tasks.application.service.RepositoryLookup
import pl.mos.backoffice.tasks.application.service.RuleService
import pl.mos.backoffice.tasks.application.service.SeedTaskLibraryService
import pl.mos.backoffice.tasks.application.service.SkillService
import pl.mos.backoffice.tasks.application.service.TaskTemplateService
import tools.jackson.databind.json.JsonMapper

/**
 * Instantiates the Task Library use cases from their ports. Beans provided to other modules:
 * `ManageTaskTemplates`, `ManageSkills`, `ManageRules`, `ManageAgents`, `SeedTaskLibrary`, the tasks
 * `PermissionCatalogContribution` and, through `TaskCatalogAdapter`, the shared `TaskCatalog` port.
 * Requires `Clock`, a Jackson 3 `JsonMapper`, `AuthorizationGate` and `AuditPort` from the application.
 */
@Configuration
@EnableConfigurationProperties(TasksProperties::class)
class TasksConfiguration {

    @Bean
    fun taskMappers(jsonMapper: JsonMapper) = TaskMappers(jsonMapper)

    @Bean
    fun taskLibraryLookup(templates: TaskTemplateRepository, skills: SkillRepository, rules: RuleRepository, agents: AgentRepository) =
        RepositoryLookup(templates, skills, rules, agents)

    /** The Task Library permissions for the identity context's catalogue (ADR 0017). */
    @Bean
    fun tasksPermissionCatalog(): PermissionCatalogContribution = PermissionCatalogContribution { TasksPermissions.definitions }

    @Bean
    fun manageTaskTemplates(
        templates: TaskTemplateRepository, lookup: RepositoryLookup, clock: Clock, gate: AuthorizationGate, audit: AuditPort,
    ): ManageTaskTemplates = TransactionalManageTaskTemplates(TaskTemplateService(templates, lookup, clock, gate, audit))

    @Bean
    fun manageSkills(
        skills: SkillRepository, templates: TaskTemplateRepository, clock: Clock, gate: AuthorizationGate, audit: AuditPort,
    ): ManageSkills = TransactionalManageSkills(SkillService(skills, templates, clock, gate, audit))

    @Bean
    fun manageRules(
        rules: RuleRepository, templates: TaskTemplateRepository, clock: Clock, gate: AuthorizationGate, audit: AuditPort,
    ): ManageRules = TransactionalManageRules(RuleService(rules, templates, clock, gate, audit))

    @Bean
    fun manageAgents(
        agents: AgentRepository, templates: TaskTemplateRepository, clock: Clock, gate: AuthorizationGate, audit: AuditPort,
    ): ManageAgents = TransactionalManageAgents(AgentService(agents, templates, clock, gate, audit))

    @Bean
    fun seedTaskLibrary(
        templates: TaskTemplateRepository, skills: SkillRepository, rules: RuleRepository, agents: AgentRepository, clock: Clock,
    ): SeedTaskLibrary = SeedTaskLibraryService(templates, skills, rules, agents, clock)
}

/** On startup: create the missing built-in library entries when `backoffice.tasks.seed-builtin` is on. */
@Component
class TasksStartupListener(
    private val properties: TasksProperties,
    private val seed: SeedTaskLibrary,
    private val lock: org.springframework.beans.factory.ObjectProvider<pl.mos.backoffice.shared.platform.ClusterLock>,
) {
    private val log = LoggerFactory.getLogger(TasksStartupListener::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!properties.seedBuiltin) return
        // one replica at a time (ShedLock `tasks-seed`): replicas starting together do not race on the built-in rows
        lock.getIfAvailable { pl.mos.backoffice.shared.platform.ClusterLock.LOCAL }.runExclusively("tasks-seed", java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(10)) { seedNow() }
    }

    private fun seedNow() {
        val result = seed.seed()
        if (result.total > 0) {
            log.info(
                "seeded task library templates={} skills={} rules={} agents={} upgraded templates={} skills={} rules={} agents={} removed templates={}",
                result.templatesCreated, result.skillsCreated, result.rulesCreated, result.agentsCreated, result.templatesUpgraded,
                result.skillsUpgraded, result.rulesUpgraded, result.agentsUpgraded, result.templatesRemoved,
            )
        }
    }
}
