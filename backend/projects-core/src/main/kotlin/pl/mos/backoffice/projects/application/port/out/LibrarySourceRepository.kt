package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId

/** Sources of the library (ADR 0043). Keys are unique. */
interface LibrarySourceRepository {
    fun findAll(): List<LibrarySource>
    fun findById(id: LibrarySourceId): LibrarySource?
    fun findByKey(key: String): LibrarySource?
    fun save(source: LibrarySource): LibrarySource
    fun delete(id: LibrarySourceId)
}
