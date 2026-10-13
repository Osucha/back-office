package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects.ProjectCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects.ProjectStats
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.CiEntrypoint
import pl.mos.backoffice.projects.domain.model.CiMode
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.service.CiSnippet
import pl.mos.backoffice.projects.domain.service.CiSnippetRenderer
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import java.time.Duration
import pl.mos.backoffice.shared.platform.PlatformFeatures

/**
 * Implements [ManageProjects]. Creating a project makes the creator its OWNER (through the identity
 * context's [ProjectAccessProvisioner]). With at least one repository it seeds the SDLC workflows and registers
 * the "CI code review" workflow as the `code-review` entrypoint (BLOCKING); without one it seeds the starter "LLM
 * task with review" and seeds the SDLC workflows when the first repository is added (ADR 0034). Every mutation is
 * audited.
 */
class ProjectService(
    private val projects: ProjectRepository,
    private val workflows: WorkflowDefinitionRepository,
    private val runs: WorkflowRunRepository,
    private val approvals: ApprovalRequestRepository,
    private val ciExecutions: CiExecutionRepository,
    private val ciTokens: ProjectCiTokenRepository,
    private val usageLedger: UsageLedger,
    private val snippetRenderer: CiSnippetRenderer,
    private val publicUrl: String,
    private val gate: AuthorizationGate,
    private val provisioner: ProjectAccessProvisioner,
    private val audit: AuditPort,
    private val clock: Clock,
    /** ADR 0036: the platform switch for Spec Kit. */
    private val features: PlatformFeatures = PlatformFeatures.DEFAULTS,
) : ManageProjects {

    override fun create(command: ProjectCommand): Project {
        // ADR 0038: every member may create a private project; a team project needs projects.create (ADR 0028)
        val actor = if (command.personal && !gate.currentUser().can(ProjectsPermissions.PROJECTS_CREATE)) {
            gate.require(ProjectsPermissions.PROJECTS_CREATE_PERSONAL).actor
        } else {
            gate.require(ProjectsPermissions.PROJECTS_CREATE).actor
        }
        val requested = normalisedKey(command.key)
        if (requested != null && projects.findByKey(requested) != null) throw ValidationException("project key $requested already exists", "key")
        val key = requested ?: Project.suggestKey(command.name) { projects.findByKey(it) != null }
        val now = clock.now()
        val project = projects.save(
            Project(
                id = ProjectId.new(),
                key = key,
                name = command.name,
                description = command.description,
                repositories = command.repositories,
                issueTracker = command.issueTracker,
                constitution = command.constitution,
                defaults = command.defaults,
                createdAt = now,
                updatedAt = now,
                createdBy = actor,
                specKit = requireSpecKitAllowed(command.specKit ?: false, false),
                personal = command.personal,
            ),
        )
        provisioner.grantCreator(project.id.value, project.key, actor)
        val (saved, seeded) = if (project.repositories.isEmpty()) {
            // ADR 0034: no repository = LLM work only; the SDLC workflows come with the first repository
            project to listOf(workflows.save(DefaultWorkflows.llmTaskWithReview(project.id, now).copy(updatedBy = actor)))
        } else {
            seedSdlc(project, actor, now)
        }
        audit.record(
            AuditEntry(
                "PROJECT_CREATE", ENTITY, saved.id.toString(), projectId = saved.id.value,
                details = mapOf(
                    "key" to saved.key, "name" to saved.name, "repositories" to describe(saved.repositories),
                    "seededWorkflows" to seeded.map { it.name }, "specKit" to saved.specKit, "personal" to saved.personal,
                ),
            ),
        )
        return saved
    }

    /**
     * Seeds the SDLC workflows that do not exist yet (by name) and registers "CI code review" as the `code-review`
     * entrypoint unless one exists. Used at creation and when a project gets its first repository (ADR 0034).
     */
    private fun normalisedKey(key: String?): String? = key?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    private fun seedSdlc(project: Project, actor: Actor, now: java.time.Instant): Pair<Project, List<WorkflowDefinition>> {
        val existing = workflows.findByProjectId(project.id).map { it.name }.toSet()
        // ADR 0036: the Spec Kit workflows only when Spec Kit is on (offered later through "Add Spec Kit workflows")
        val seeded = DefaultWorkflows.sdlc(project.id, now, specKitOn(project)).filter { it.name !in existing }.map { workflows.save(it.copy(updatedBy = actor)) }
        val review = seeded.firstOrNull { it.name == DefaultWorkflows.CI_CODE_REVIEW }
        if (review == null || project.entrypoint(CiEntrypoint.CODE_REVIEW) != null) return project to seeded
        val saved = projects.save(
            project.withEntrypoints(
                project.ciEntrypoints + CiEntrypoint(CiEntrypoint.CODE_REVIEW, review.id, CiMode.BLOCKING, "AI review of the merge request diff", uploadDiff = true),
                now,
            ),
        )
        return saved to seeded
    }

    override fun update(id: ProjectId, command: ProjectCommand): Project {
        val actor = gate.require(ProjectsPermissions.PROJECT_MANAGE, id.value).actor
        val existing = load(id)
        val newKey = normalisedKey(command.key)?.takeIf { it != existing.key }
        if (newKey != null && projects.findByKey(newKey) != null) throw ValidationException("project key $newKey already exists", "key")
        val now = clock.now()
        val renamed = newKey?.let { existing.rename(it, now) } ?: existing
        val saved = projects.save(
            renamed.update(
                name = command.name,
                description = command.description,
                repositories = command.repositories,
                issueTracker = command.issueTracker,
                constitution = command.constitution,
                defaults = command.defaults,
                now = now,
                specKit = requireSpecKitAllowed(command.specKit ?: existing.specKit, existing.specKit),
            ),
        )
        if (newKey != null) provisioner.projectRenamed(id.value, newKey)
        val changes = AuditableChanges.changed(
            "key" to (existing.key to saved.key),
            "name" to (existing.name to saved.name),
            "description" to (existing.description to saved.description),
            "repositories" to (describe(existing.repositories) to describe(saved.repositories)),
            "issueTracker.jiraProjectKey" to (existing.issueTracker.jiraProjectKey to saved.issueTracker.jiraProjectKey),
            "issueTracker.jql" to (existing.issueTracker.jql to saved.issueTracker.jql),
            "defaults.modelProfileId" to (existing.defaults.modelProfileId?.toString() to saved.defaults.modelProfileId?.toString()),
            "defaults.reviewModelProfileId" to (existing.defaults.reviewModelProfileId?.toString() to saved.defaults.reviewModelProfileId?.toString()),
            "specKit" to (existing.specKit to saved.specKit),
        ).let { diff ->
            // the constitution can be long markdown: record that it changed and its size, not the text
            if (existing.constitution == saved.constitution) diff
            else diff + ("constitution" to mapOf("oldLength" to existing.constitution.length, "newLength" to saved.constitution.length))
        }
        if (changes.isNotEmpty()) audit.record(AuditEntry("PROJECT_UPDATE", ENTITY, id.toString(), projectId = id.value, details = changes))
        if (existing.repositories.isEmpty() && saved.repositories.isNotEmpty()) {
            val (withEntrypoint, seeded) = seedSdlc(saved, actor, clock.now())
            if (seeded.isNotEmpty()) {
                audit.record(AuditEntry("PROJECT_SDLC_SEEDED", ENTITY, id.toString(), projectId = id.value, details = mapOf("seededWorkflows" to seeded.map { it.name })))
            }
            return withEntrypoint
        }
        return saved
    }

    override fun specKitStatus(id: ProjectId): ManageProjects.SpecKitStatus {
        gate.require(ProjectsPermissions.PROJECT_VIEW, id.value)
        return specKitStatusOf(load(id))
    }

    override fun addSpecKitWorkflows(id: ProjectId): List<WorkflowDefinition> {
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, id.value).actor
        val project = load(id)
        if (!specKitOn(project)) {
            throw InvalidStateException("Spec Kit is ${pl.mos.backoffice.projects.application.service.tasks.specKitOff(project.specKit)}; turn it on first")
        }
        if (project.repositories.isEmpty()) throw InvalidStateException("the Spec Kit workflows need a repository; add one to the project first")
        val now = clock.now()
        val existing = workflows.findByProjectId(id).map { it.name }.toSet()
        val seeded = DefaultWorkflows.all(id, now).filter { DefaultWorkflows.usesSpecKit(it) && it.name !in existing }
            .map { workflows.save(it.copy(updatedBy = actor)) }
        if (seeded.isNotEmpty()) {
            audit.record(AuditEntry("PROJECT_SPEC_KIT_SEEDED", ENTITY, id.toString(), projectId = id.value, details = mapOf("seededWorkflows" to seeded.map { it.name })))
        }
        return seeded
    }

    private fun specKitOn(project: Project): Boolean = project.specKit && features.specKitAllowed()

    private fun specKitStatusOf(project: Project): ManageProjects.SpecKitStatus {
        val existing = workflows.findByProjectId(project.id).map { it.name }.toSet()
        val missing = if (project.repositories.isEmpty()) {
            emptyList()
        } else {
            DefaultWorkflows.all(project.id, clock.now()).filter { DefaultWorkflows.usesSpecKit(it) && it.name !in existing }.map { it.name }
        }
        return ManageProjects.SpecKitStatus(project.specKit, features.specKitAllowed(), missing)
    }

    /** ADR 0036: turning Spec Kit on is refused while the platform disallows it (an existing "on" may stay, it has no effect). */
    private fun requireSpecKitAllowed(wanted: Boolean, current: Boolean): Boolean {
        if (wanted && !current && !features.specKitAllowed()) {
            throw ValidationException("Spec Kit is disabled for the whole platform by an administrator (Platform settings)", "specKit")
        }
        return wanted
    }

    override fun delete(id: ProjectId) {
        // Irreversible (runs, approvals, CI history): owners only, i.e. project.manage AND project.members.manage,
        // the same rule the UI applies to the Delete button. A maintainer can edit but not delete.
        gate.require(ProjectsPermissions.PROJECT_MANAGE, id.value)
        gate.require(ProjectsPermissions.PROJECT_MEMBERS_MANAGE, id.value)
        val project = load(id)
        val active = runs.countByProjectAndStatus(id, ACTIVE)
        if (active > 0) throw InvalidStateException("project $id has $active active runs; cancel them first")
        approvals.deleteByProjectId(id)
        ciExecutions.deleteByProjectId(id)
        runs.deleteByProjectId(id)
        workflows.deleteByProjectId(id)
        ciTokens.deleteByProjectId(id)
        projects.delete(id)
        provisioner.projectDeleted(id.value)
        audit.record(AuditEntry("PROJECT_DELETE", ENTITY, id.toString(), projectId = id.value, details = mapOf("key" to project.key, "name" to project.name)))
    }

    override fun get(id: ProjectId): Project {
        gate.require(ProjectsPermissions.PROJECT_VIEW, id.value)
        return load(id)
    }

    @PublicOperation("lists only the projects the caller holds project.view on")
    override fun list(): List<Project> = projects.findAll(gate.projectFilter(ProjectsPermissions.PROJECT_VIEW)).sortedBy { it.key }

    override fun stats(id: ProjectId): ProjectStats {
        gate.require(ProjectsPermissions.PROJECT_VIEW, id.value)
        val now = clock.now()
        val cost = try {
            usageLedger.totalCost(UsageQuery(from = now.minus(Duration.ofDays(30)), to = now, projectId = id.value))
        } catch (e: RuntimeException) {
            Money.ZERO
        }
        return ProjectStats(
            workflows = workflows.countByProjectId(id),
            activeRuns = runs.countByProjectAndStatus(id, ACTIVE),
            openApprovals = approvals.countOpenByProject(id),
            ciExecutionsLast7Days = ciExecutions.countByProjectSince(id, now.minus(Duration.ofDays(7))),
            costLast30Days = cost,
        )
    }

    override fun entrypoints(id: ProjectId): List<CiEntrypoint> {
        gate.require(ProjectsPermissions.PROJECT_VIEW, id.value)
        return load(id).ciEntrypoints
    }

    override fun replaceEntrypoints(id: ProjectId, entrypoints: List<CiEntrypoint>): List<CiEntrypoint> {
        gate.require(ProjectsPermissions.PROJECT_MANAGE, id.value)
        val project = load(id)
        val own = workflows.findByProjectId(id).associateBy { it.id }
        entrypoints.forEach { entrypoint ->
            if (entrypoint.workflowId !in own) {
                throw ValidationException("workflow ${entrypoint.workflowId} does not belong to project ${project.key}", "ciEntrypoints.${entrypoint.key}.workflowId")
            }
        }
        val saved = projects.save(project.withEntrypoints(entrypoints, clock.now())).ciEntrypoints
        val describe = { list: List<CiEntrypoint> -> list.map { "${it.key}:${it.workflowId}:${it.mode}" + (it.repository?.let { r -> ":$r" } ?: "") } }
        val changes = AuditableChanges.changed("ciEntrypoints" to (describe(project.ciEntrypoints) to describe(saved)))
        if (changes.isNotEmpty()) audit.record(AuditEntry("CI_ENTRYPOINTS_UPDATE", ENTITY, id.toString(), projectId = id.value, details = changes))
        return saved
    }

    override fun ciSnippet(id: ProjectId): CiSnippet {
        gate.require(ProjectsPermissions.PROJECT_VIEW, id.value)
        val project = load(id)
        return snippetRenderer.render(project, workflows.findByProjectId(id), publicUrl)
    }

    private fun load(id: ProjectId): Project = projects.findById(id) ?: throw NotFoundException("Project", id)

    /** Audit form of the repositories: `key=url@branch (PURPOSE, primary)`. */
    private fun describe(repositories: List<GitRepository>): List<String> =
        repositories.map { "${it.key}=${it.url}@${it.defaultBranch} (${it.purpose}${if (it.primary) ", primary" else ""})" }

    companion object {
        const val ENTITY = "Project"
        val ACTIVE = setOf(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.WAITING)
    }
}
