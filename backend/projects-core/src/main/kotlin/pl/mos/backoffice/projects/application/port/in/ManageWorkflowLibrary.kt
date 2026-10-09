package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.LibraryWorkflow
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowId
import pl.mos.backoffice.projects.domain.model.LibraryWorkflowVersion
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.WorkflowDefinition
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId

/**
 * Use case: the workflow library (ADR 0037). Entries are published from project workflows and adopted by projects
 * as linked copies; `library.view` reads, `library.manage` publishes and curates, `workflow.edit` on a project
 * adopts, updates and detaches its copies.
 */
interface ManageWorkflowLibrary {
    fun list(includeDeprecated: Boolean = true): List<LibraryWorkflowView>
    fun get(id: LibraryWorkflowId): LibraryWorkflowView
    fun version(id: LibraryWorkflowId, version: Int): LibraryVersionView
    fun update(id: LibraryWorkflowId, command: LibraryDetailsCommand): LibraryWorkflowView
    fun delete(id: LibraryWorkflowId)

    /** Publishes a saved project workflow as a new entry or the next version of [PublishCommand.libraryWorkflowId]. */
    fun publish(workflowId: WorkflowDefinitionId, command: PublishCommand): LibraryWorkflowView

    /** Creates a project workflow linked to a library version (default the latest). */
    fun adopt(projectId: ProjectId, libraryWorkflowId: LibraryWorkflowId, version: Int? = null, name: String? = null): WorkflowDefinition

    /** Moves a linked copy to a library version (default the latest), keeping its project-local settings. */
    fun updateFromLibrary(workflowId: WorkflowDefinitionId, version: Int? = null, discardLocalChanges: Boolean = false): WorkflowDefinition

    fun detach(workflowId: WorkflowDefinitionId): WorkflowDefinition

    /** Latest version, name and deprecation of linked entries, for showing "update available" next to workflows. */
    fun linkStatus(ids: Collection<LibraryWorkflowId>): Map<LibraryWorkflowId, LinkStatus>

    data class PublishCommand(
        val libraryWorkflowId: LibraryWorkflowId? = null,
        val key: String? = null,
        val name: String? = null,
        val description: String? = null,
        val tags: List<String>? = null,
        val changeNote: String? = null,
    )

    data class LibraryDetailsCommand(val name: String, val description: String = "", val tags: List<String> = emptyList(), val deprecated: Boolean = false)

    /** A project workflow linked to the entry, visible to the caller. */
    data class Adoption(
        val projectId: ProjectId,
        val projectKey: String,
        val workflowId: WorkflowDefinitionId,
        val workflowName: String,
        val version: Int,
        val modified: Boolean,
        val enabled: Boolean,
    )

    data class LibraryWorkflowView(val workflow: LibraryWorkflow, val adoptions: List<Adoption>) {
        val projectCount: Int get() = adoptions.map { it.projectId }.distinct().size
    }

    data class LibraryVersionView(val workflow: LibraryWorkflow, val version: LibraryWorkflowVersion)

    data class LinkStatus(val name: String, val latestVersion: Int, val deprecated: Boolean)
}
