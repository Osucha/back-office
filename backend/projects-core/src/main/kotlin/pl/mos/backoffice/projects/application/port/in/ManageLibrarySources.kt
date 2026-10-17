package pl.mos.backoffice.projects.application.port.`in`

import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose

/** Sources of the library (ADR 0043): read with `library.view`, changed with `library.manage`. */
interface ManageLibrarySources {
    data class SourceCommand(
        val key: String,
        val name: String,
        val description: String = "",
        val url: String,
        val defaultBranch: String? = null,
        val purpose: RepositoryPurpose = RepositoryPurpose.STANDARDS,
    )

    fun list(): List<LibrarySource>
    fun get(id: LibrarySourceId): LibrarySource
    fun create(command: SourceCommand): LibrarySource

    /** The key cannot change: steps refer to it. */
    fun update(id: LibrarySourceId, command: SourceCommand): LibrarySource
    fun delete(id: LibrarySourceId)
}
