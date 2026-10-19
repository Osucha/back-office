package pl.mos.backoffice.llm.connections

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
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
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.llm.Provider
import java.time.Instant
import java.util.UUID

data class LlmProviderInputDto(
    @field:NotBlank val key: String = "",
    @field:NotBlank val name: String = "",
    val provider: String = "",
    val baseUrl: String? = null,
    /** Write-only; null keeps the stored key. */
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
    val enabled: Boolean = true,
)

data class LlmProviderActorDto(val kind: String, val id: UUID?, val displayName: String)

data class LlmProviderDto(
    val id: UUID?,
    val key: String?,
    val name: String,
    val provider: String,
    val baseUrl: String?,
    val hasApiKey: Boolean,
    val enabled: Boolean,
    val usable: Boolean,
    val source: String,
    val updatedAt: Instant?,
    val updatedBy: LlmProviderActorDto?,
)

data class ProviderModelInfoDto(
    val id: String,
    val name: String?,
    val contextWindow: Int?,
    val inputPerMTok: java.math.BigDecimal?,
    val outputPerMTok: java.math.BigDecimal?,
    val free: Boolean,
)

data class ProviderModelsDto(val ok: Boolean, val models: List<String>, val error: String?, val details: List<ProviderModelInfoDto> = emptyList())

data class CatalogSectionDto(
    val connection: String?,
    val name: String,
    val provider: String,
    val source: String,
    val ok: Boolean,
    val error: String?,
    val models: List<ProviderModelInfoDto>,
)

/** `llm-providers` tag of openapi.yaml (ADR 0045): provider connections managed in Back Office. */
@RestController
@RequestMapping("/api/v1/llm-providers")
class LlmProviderController(private val service: LlmProviderService) {

    @GetMapping
    fun list(): List<LlmProviderDto> = service.list().map(::toDto)

    /** The models of every usable provider endpoint, the source of new model profiles (`?refresh=true` asks again). */
    @GetMapping("/catalog")
    fun catalog(@RequestParam(defaultValue = "false") refresh: Boolean): List<CatalogSectionDto> = service.catalog(refresh).map { s ->
        CatalogSectionDto(s.connection, s.name, s.provider.name, s.source.name, s.ok, s.error, s.models.map(::toDto))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: LlmProviderInputDto): LlmProviderDto = toDto(service.create(command(body)))

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody body: LlmProviderInputDto): LlmProviderDto = toDto(service.update(id, command(body)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = service.delete(id)

    /** Connection test: the models the endpoint lists. */
    @PostMapping("/{id}/models")
    fun models(@PathVariable id: UUID): ProviderModelsDto = toDto(service.models(id, null))

    /** The same for the environment endpoint of a provider type (`?provider=OPENAI`). */
    @PostMapping("/environment/models")
    fun environmentModels(@RequestParam provider: String): ProviderModelsDto = toDto(service.models(null, provider(provider)))

    private fun command(body: LlmProviderInputDto) = LlmProviderCommand(
        key = body.key, name = body.name, provider = provider(body.provider), baseUrl = body.baseUrl, apiKey = body.apiKey,
        clearApiKey = body.clearApiKey, enabled = body.enabled,
    )

    private fun provider(value: String): Provider =
        Provider.entries.firstOrNull { it.name == value.trim().uppercase() } ?: throw ValidationException("unknown provider $value", "provider")

    private fun toDto(v: LlmProviderView) = LlmProviderDto(
        v.id, v.key, v.name, v.provider.name, v.baseUrl, v.hasApiKey, v.enabled, v.usable, v.source.name, v.updatedAt,
        v.updatedBy?.let { LlmProviderActorDto(it.kind.name, it.userId, it.displayName) },
    )

    private fun toDto(m: ProviderModels) = ProviderModelsDto(m.ok, m.models, m.error, m.details.map(::toDto))

    private fun toDto(i: ProviderModelInfo) = ProviderModelInfoDto(i.id, i.name, i.contextWindow, i.inputPerMTok, i.outputPerMTok, i.free)
}
