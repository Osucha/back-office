package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ManageLibrarySources
import pl.mos.backoffice.projects.domain.model.LibrarySource
import pl.mos.backoffice.projects.domain.model.LibrarySourceId
import pl.mos.backoffice.projects.domain.model.RepositoryPurpose
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant
import java.util.UUID

data class LibrarySourceInputDto(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]{1,26}$") val key: String = "",
    @field:NotBlank val name: String = "",
    val description: String = "",
    val kind: String = "GIT",
    @field:NotBlank val url: String = "",
    val defaultBranch: String? = null,
    val purpose: String = "STANDARDS",
)

data class LibrarySourceDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val kind: String,
    val url: String,
    val defaultBranch: String,
    val purpose: String,
    /** Key of the source in a step's job and its directory under `/workspace`. */
    val jobKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto?,
)

/** `library-sources` tag of openapi.yaml (ADR 0043): git sources of the library that steps attach read-only. */
@RestController
@RequestMapping("/api/v1/library/sources")
class LibrarySourceController(private val sources: ManageLibrarySources, private val mapper: RestMapper) {

    @GetMapping
    fun list(): List<LibrarySourceDto> = sources.list().map(::toDto)

    @GetMapping("/{sourceId}")
    fun get(@PathVariable sourceId: UUID): LibrarySourceDto = toDto(sources.get(LibrarySourceId(sourceId)))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid body: LibrarySourceInputDto): LibrarySourceDto = toDto(sources.create(command(body)))

    @PutMapping("/{sourceId}")
    fun update(@PathVariable sourceId: UUID, @RequestBody @Valid body: LibrarySourceInputDto): LibrarySourceDto =
        toDto(sources.update(LibrarySourceId(sourceId), command(body)))

    @DeleteMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable sourceId: UUID) = sources.delete(LibrarySourceId(sourceId))

    private fun command(body: LibrarySourceInputDto): ManageLibrarySources.SourceCommand {
        if (body.kind.uppercase() != "GIT") throw ValidationException("only GIT sources are supported", "kind")
        val purpose = RepositoryPurpose.entries.firstOrNull { it.name == body.purpose.uppercase() }
            ?: throw ValidationException("purpose must be one of ${RepositoryPurpose.entries.joinToString()}", "purpose")
        return ManageLibrarySources.SourceCommand(body.key, body.name, body.description, body.url, body.defaultBranch, purpose)
    }

    private fun toDto(s: LibrarySource) = LibrarySourceDto(
        s.id.value, s.key, s.name, s.description, s.kind.name, s.url, s.defaultBranch, s.purpose.name, s.jobKey, s.createdAt, s.updatedAt, mapper.actor(s.updatedBy),
    )
}
