package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageLibrarySources
import pl.mos.backoffice.projects.application.port.`in`.ManageLibrarySources.SourceCommand
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate

/**
 * Sources of the library (ADR 0043): git repositories with company standards, templates or reference material that
 * steps of every project can attach read-only. A step that names a deleted source fails validation (draft, ADR 0041).
 */
class LibrarySourceService(
    private val sources: LibrarySourceRepository,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManageLibrarySources {

    override fun list(): List<LibrarySource> {
        gate.require(ProjectsPermissions.LIBRARY_VIEW)
        return sources.findAll().sortedBy { it.key }
    }

    override fun get(id: LibrarySourceId): LibrarySource {
        gate.require(ProjectsPermissions.LIBRARY_VIEW)
        return load(id)
    }

    override fun create(command: SourceCommand): LibrarySource {
        val actor = gate.require(ProjectsPermissions.LIBRARY_MANAGE).actor
        val key = command.key.trim()
        if (sources.findByKey(key) != null) throw ValidationException("library source $key already exists", "key")
        val now = clock.now()
        val saved = sources.save(
            LibrarySource(
                id = LibrarySourceId.new(), key = key, name = command.name.trim(), description = command.description.trim(), url = command.url.trim(),
                defaultBranch = branch(command), purpose = command.purpose, createdAt = now, updatedAt = now, updatedBy = actor,
            ),
        )
        audit.record(AuditEntry("LIBRARY_SOURCE_CREATE", ENTITY, saved.id.toString(), details = mapOf("key" to saved.key, "url" to saved.url, "purpose" to saved.purpose.name)))
        return saved
    }

    override fun update(id: LibrarySourceId, command: SourceCommand): LibrarySource {
        val actor = gate.require(ProjectsPermissions.LIBRARY_MANAGE).actor
        val existing = load(id)
        if (command.key.trim() != existing.key) throw ValidationException("the key of a library source cannot be changed: steps refer to it", "key")
        val saved = sources.save(
            existing.copy(
                name = command.name.trim(), description = command.description.trim(), url = command.url.trim(), defaultBranch = branch(command),
                purpose = command.purpose, updatedAt = clock.now(), updatedBy = actor,
            ),
        )
        val changes = AuditableChanges.changed(
            "name" to (existing.name to saved.name),
            "url" to (existing.url to saved.url),
            "defaultBranch" to (existing.defaultBranch to saved.defaultBranch),
            "purpose" to (existing.purpose.name to saved.purpose.name),
        )
        if (changes.isNotEmpty() || existing.description != saved.description) {
            audit.record(AuditEntry("LIBRARY_SOURCE_UPDATE", ENTITY, id.toString(), details = changes + ("key" to saved.key)))
        }
        return saved
    }

    override fun delete(id: LibrarySourceId) {
        gate.require(ProjectsPermissions.LIBRARY_MANAGE)
        val existing = load(id)
        sources.delete(id)
        audit.record(AuditEntry("LIBRARY_SOURCE_DELETE", ENTITY, id.toString(), details = mapOf("key" to existing.key)))
    }

    private fun branch(command: SourceCommand): String = command.defaultBranch?.trim()?.ifEmpty { null } ?: GitRepository.DEFAULT_BRANCH

    private fun load(id: LibrarySourceId): LibrarySource = sources.findById(id) ?: throw NotFoundException("LibrarySource", id)

    companion object {
        const val ENTITY = "LibrarySource"
    }
}
