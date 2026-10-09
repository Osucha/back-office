package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.Adoption
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.LibraryDetailsCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.LibraryVersionView
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.LibraryWorkflowView
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.LinkStatus
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflowLibrary.PublishCommand
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows.WorkflowCommand
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.domain.model.LibraryLink
import pl.mos.backoffice.projects.domain.model.LibraryWorkflow
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowId
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowSource
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowVersion
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.LibraryPortability
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.tasks.TaskCatalog

/**
 * Implements [ManageWorkflowLibrary] (ADR 0037). Publishing strips project-local settings ([LibraryPortability]) and
 * requires global task templates; adopting and updating validate the graph in the target project exactly like a save
 * in the designer ([WorkflowService.requireValidFor]). Adoption lists only show projects the caller may view.
 */
class WorkflowLibraryService(
    private val library: LibraryWorkflowRepository,
    private val workflows: WorkflowDefinitionRepository,
    private val projects: ProjectRepository,
    private val workflowService: WorkflowService,
    private val taskCatalog: TaskCatalog,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManageWorkflowLibrary {

    override fun list(includeDeprecated: Boolean): List<LibraryWorkflowView> {
        gate.require(ProjectsPermissions.LIBRARY_VIEW)
        val adoptions = visibleAdoptions(null)
        return library.findAll()
            .filter { includeDeprecated || !it.deprecated }
            .sortedBy { it.name.lowercase() }
            .map { LibraryWorkflowView(it, adoptions[it.id].orEmpty()) }
    }

    override fun get(id: LibraryWorkflowId): LibraryWorkflowView {
        gate.require(ProjectsPermissions.LIBRARY_VIEW)
        return view(load(id))
    }

    override fun version(id: LibraryWorkflowId, version: Int): LibraryVersionView {
        gate.require(ProjectsPermissions.LIBRARY_VIEW)
        val entry = load(id)
        return LibraryVersionView(entry, entry.version(version))
    }

    override fun update(id: LibraryWorkflowId, command: LibraryDetailsCommand): LibraryWorkflowView {
        val actor = gate.require(ProjectsPermissions.LIBRARY_MANAGE).actor
        val existing = load(id)
        val saved = library.save(existing.withDetails(command.name.trim(), command.description.trim(), command.tags, command.deprecated, clock.now(), actor))
        audit.record(
            AuditEntry(
                "LIBRARY_WORKFLOW_UPDATE", ENTITY, id.toString(),
                details = AuditableChanges.changed(
                    "name" to (existing.name to saved.name),
                    "description" to (existing.description to saved.description),
                    "tags" to (existing.tags to saved.tags),
                    "deprecated" to (existing.deprecated to saved.deprecated),
                ),
            ),
        )
        return view(saved)
    }

    override fun delete(id: LibraryWorkflowId) {
        gate.require(ProjectsPermissions.LIBRARY_MANAGE)
        val existing = load(id)
        val linked = workflows.findLinkedToLibrary(id)
        if (linked.isNotEmpty()) {
            throw InvalidStateException("library workflow ${existing.key} is used by ${linked.size} project workflow(s); deprecate it, or detach them first")
        }
        library.delete(id)
        audit.record(AuditEntry("LIBRARY_WORKFLOW_DELETE", ENTITY, id.toString(), details = mapOf("key" to existing.key, "versions" to existing.versions.size)))
    }

    override fun publish(workflowId: WorkflowDefinitionId, command: PublishCommand): LibraryWorkflowView {
        val source = workflows.findById(workflowId) ?: throw NotFoundException("Workflow", workflowId)
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, source.projectId.value).actor
        gate.require(ProjectsPermissions.LIBRARY_MANAGE)
        val project = projects.findById(source.projectId) ?: throw NotFoundException("Project", source.projectId)
        requireGlobalTemplates(source)
        val note = command.changeNote?.trim().orEmpty()
        if (note.length > LibraryWorkflow.MAX_CHANGE_NOTE) throw ValidationException("must be at most ${LibraryWorkflow.MAX_CHANGE_NOTE} characters", "changeNote")
        val now = clock.now()
        val draft = LibraryWorkflowVersion(
            version = 1,
            changeNote = note,
            nodes = LibraryPortability.portable(source.nodes),
            edges = source.edges,
            triggers = source.triggers,
            canvas = source.canvas,
            publishedAt = now,
            publishedBy = actor,
            source = LibraryWorkflowSource(project.id, project.key, source.id, source.version),
        )
        val target = command.libraryWorkflowId?.let(::load)
        val saved = if (target == null) {
            val key = command.key?.trim()?.lowercase().orEmpty()
            if (library.findByKey(key) != null) throw ValidationException("a library workflow with key $key exists; publish a new version of it instead", "key")
            library.save(
                LibraryWorkflow(
                    id = LibraryWorkflowId.new(),
                    key = key,
                    name = command.name?.trim()?.ifEmpty { null } ?: source.name,
                    description = command.description?.trim() ?: source.description,
                    tags = LibraryWorkflow.normalizeTags(command.tags.orEmpty()),
                    versions = listOf(draft),
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = actor,
                ),
            )
        } else {
            val latest = target.latest
            if (LibraryPortability.sameGraph(source.nodes, source.edges, latest.nodes, latest.edges)) {
                throw ValidationException("the workflow does not differ from version ${latest.version} of ${target.key}", "workflow")
            }
            val published = target.publish(draft, now, actor)
            library.save(
                published.copy(
                    name = command.name?.trim()?.ifEmpty { null } ?: published.name,
                    description = command.description?.trim() ?: published.description,
                    tags = command.tags?.let(LibraryWorkflow::normalizeTags) ?: published.tags,
                ),
            )
        }
        val version = saved.latest
        workflows.save(source.withLibrary(LibraryLink(saved.id, saved.key, version.version, modified = false)))
        audit.record(
            AuditEntry(
                "LIBRARY_WORKFLOW_PUBLISH", ENTITY, saved.id.toString(), projectId = source.projectId.value,
                details = mapOf("key" to saved.key, "version" to version.version, "workflowId" to source.id.toString(), "workflowVersion" to source.version, "nodes" to version.nodeCount),
            ),
        )
        return view(saved)
    }

    override fun adopt(projectId: ProjectId, libraryWorkflowId: LibraryWorkflowId, version: Int?, name: String?): WorkflowDefinition {
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, projectId.value).actor
        gate.require(ProjectsPermissions.LIBRARY_VIEW)
        projects.findById(projectId) ?: throw NotFoundException("Project", projectId)
        val entry = load(libraryWorkflowId)
        if (entry.deprecated) throw InvalidStateException("library workflow ${entry.key} is deprecated")
        val chosen = version?.let(entry::version) ?: entry.latest
        val taken = workflows.findByProjectId(projectId).map { it.name }.toSet()
        val command = WorkflowCommand(
            name = uniqueName(name?.trim()?.ifEmpty { null } ?: entry.name, taken),
            description = entry.description,
            nodes = chosen.nodes,
            edges = chosen.edges,
            triggers = chosen.triggers,
            enabled = true,
            canvas = chosen.canvas,
        )
        workflowService.requireValidFor(projectId, command)
        val now = clock.now()
        val saved = workflows.save(
            WorkflowDefinition(
                id = WorkflowDefinitionId.new(),
                projectId = projectId,
                name = command.name,
                description = command.description,
                version = 1,
                nodes = command.nodes,
                edges = command.edges,
                triggers = command.triggers,
                enabled = command.enabled,
                canvas = command.canvas,
                createdAt = now,
                updatedAt = now,
                updatedBy = actor,
                library = LibraryLink(entry.id, entry.key, chosen.version),
            ),
        )
        audit.record(
            AuditEntry(
                "WORKFLOW_ADOPT", WorkflowService.ENTITY, saved.id.toString(), projectId = projectId.value,
                details = mapOf("name" to saved.name, "libraryKey" to entry.key, "libraryVersion" to chosen.version),
            ),
        )
        return saved
    }

    override fun updateFromLibrary(workflowId: WorkflowDefinitionId, version: Int?, discardLocalChanges: Boolean): WorkflowDefinition {
        val existing = workflows.findById(workflowId) ?: throw NotFoundException("Workflow", workflowId)
        val actor = gate.require(ProjectsPermissions.WORKFLOW_EDIT, existing.projectId.value).actor
        val link = existing.library ?: throw InvalidStateException("workflow ${existing.name} is not linked to the library")
        val entry = load(link.libraryWorkflowId)
        val target = version?.let(entry::version) ?: entry.latest
        val followed = entry.versions.firstOrNull { it.version == link.version }
        val modified = followed == null || !LibraryPortability.sameGraph(existing.nodes, existing.edges, followed.nodes, followed.edges)
        if (modified && !discardLocalChanges) {
            throw InvalidStateException("workflow ${existing.name} was changed since library version ${link.version}; updating discards those changes")
        }
        if (!modified && target.version == link.version) return existing
        val nodes = LibraryPortability.withLocalSettings(target.nodes, existing.nodes)
        val command = WorkflowCommand(existing.name, existing.description, nodes, target.edges, existing.triggers, existing.enabled, existing.canvas)
        workflowService.requireValidFor(existing.projectId, command)
        val saved = workflows.save(
            existing.newVersion(command.name, command.description, command.nodes, command.edges, command.triggers, command.enabled, command.canvas, clock.now(), actor)
                .withLibrary(link.copy(version = target.version, modified = false)),
        )
        audit.record(
            AuditEntry(
                "WORKFLOW_LIBRARY_UPDATE", WorkflowService.ENTITY, workflowId.toString(), projectId = existing.projectId.value,
                details = mapOf(
                    "libraryKey" to entry.key, "fromVersion" to link.version, "toVersion" to target.version,
                    "discardedLocalChanges" to modified, "workflowVersion" to saved.version,
                ),
            ),
        )
        return saved
    }

    override fun detach(workflowId: WorkflowDefinitionId): WorkflowDefinition {
        val existing = workflows.findById(workflowId) ?: throw NotFoundException("Workflow", workflowId)
        gate.require(ProjectsPermissions.WORKFLOW_EDIT, existing.projectId.value)
        val link = existing.library ?: return existing
        val saved = workflows.save(existing.withLibrary(null))
        audit.record(
            AuditEntry(
                "WORKFLOW_LIBRARY_DETACH", WorkflowService.ENTITY, workflowId.toString(), projectId = existing.projectId.value,
                details = mapOf("libraryKey" to link.key, "libraryVersion" to link.version),
            ),
        )
        return saved
    }

    @PublicOperation("latest version, name and deprecation of library entries linked from workflows the caller has already loaded")
    override fun linkStatus(ids: Collection<LibraryWorkflowId>): Map<LibraryWorkflowId, LinkStatus> =
        if (ids.isEmpty()) emptyMap() else library.findHeaders(ids.distinct()).associate { it.id to LinkStatus(it.name, it.latestVersion, it.deprecated) }

    /**
     * A published workflow must run anywhere: every TASK node uses a template that exists globally and is not shadowed
     * by a project-scoped template of the source project.
     */
    private fun requireGlobalTemplates(source: WorkflowDefinition) {
        val shadowed = runCatching { taskCatalog.palette(source.projectId.value) }.getOrDefault(emptyList())
            .filter { it.projectId != null }.map { it.key }.toSet()
        val errors = source.nodes.filterIsInstance<WorkflowNode.Task>().mapNotNull { node ->
            val key = node.task.templateKey
            val global = try {
                taskCatalog.resolve(key, null)
            } catch (e: ValidationException) {
                null
            }
            when {
                key in shadowed -> ValidationException.FieldError("${node.id}.task.templateKey", "step ${node.name} uses the project template $key; publish the template globally first")
                global == null -> ValidationException.FieldError("${node.id}.task.templateKey", "step ${node.name} uses $key, which is not a concrete global template")
                else -> null
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    private fun view(entry: LibraryWorkflow): LibraryWorkflowView = LibraryWorkflowView(entry, visibleAdoptions(entry.id)[entry.id].orEmpty())

    /** Linked project workflows per entry, only in projects the caller may view. */
    private fun visibleAdoptions(id: LibraryWorkflowId?): Map<LibraryWorkflowId, List<Adoption>> {
        val filter = gate.projectFilter(ProjectsPermissions.PROJECT_VIEW)
        val linked = workflows.findLinkedToLibrary(id).filter { filter.allows(it.projectId.value) }
        if (linked.isEmpty()) return emptyMap()
        val keys = projects.findAll(filter).associate { it.id to it.key }
        return linked.filter { it.projectId in keys }
            .map { it.library.libraryWorkflowId to Adoption(it.projectId, keys.getValue(it.projectId), it.workflowId, it.name, it.library.version, it.library.modified, it.enabled) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedWith(compareBy({ it.projectKey }, { it.workflowName })) }
    }

    private fun load(id: LibraryWorkflowId): LibraryWorkflow = library.findById(id) ?: throw NotFoundException("LibraryWorkflow", id)

    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    companion object {
        const val ENTITY = "LibraryWorkflow"
    }
}
