package pl.mos.backoffice.tasks.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.tasks.domain.model.PromptMode
import pl.mos.backoffice.tasks.domain.model.SlotKind
import java.time.Instant
import java.util.UUID

/* Request and response bodies of the task-library tag in docs/api/openapi.yaml. */

private const val KEY_PATTERN = "^[a-z][a-z0-9-]{1,63}$"

enum class ScopeTypeDto { GLOBAL, PROJECT }

/** `ActorRef` schema: who saved the current version (ADR 0017). */
data class ActorRefDto(val kind: PrincipalKind, val userId: UUID?, val displayName: String) {
    companion object {
        fun of(actor: Actor?): ActorRefDto? = actor?.let { ActorRefDto(it.kind, it.userId, it.displayName) }
    }
}

/** `Scope` schema: `PROJECT` requires `projectId`, `GLOBAL` ignores it. */
data class ScopeDto(val type: ScopeTypeDto, val projectId: UUID? = null)

data class SlotDto(
    @field:NotBlank val name: String,
    val kind: SlotKind,
    val required: Boolean = true,
    val description: String? = null,
)

data class TaskInterfaceInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String,
    @field:NotBlank val name: String,
    val description: String = "",
    @field:Valid val inputs: List<SlotDto> = emptyList(),
    @field:Valid val outputs: List<SlotDto> = emptyList(),
    val ports: List<String> = listOf("success", "failure"),
)

data class TaskInterfaceDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val inputs: List<SlotDto>,
    val outputs: List<SlotDto>,
    val ports: List<String>,
    val builtIn: Boolean,
    val implementedBy: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
)

/** `RunInputField` schema (ADR 0053). */
data class RunInputFieldDto(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{0,63}$") val name: String,
    @field:NotBlank @field:Size(max = 200) val label: String,
    val type: RunInputType = RunInputType.TEXT,
    val required: Boolean = false,
    @field:Size(max = 1000) val help: String? = null,
)

data class TaskPromptDto(val system: String? = null, val user: String? = null, val mode: PromptMode = PromptMode.APPEND)

data class TaskTemplateInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String,
    @field:NotBlank val name: String,
    val description: String = "",
    val abstract: Boolean = false,
    val extendsKey: String? = null,
    val implements: List<String> = emptyList(),
    val executor: TaskExecutor? = null,
    val scope: ScopeDto,
    val modelProfileId: UUID? = null,
    val prompt: TaskPromptDto = TaskPromptDto(),
    val skills: List<String> = emptyList(),
    val removeSkills: List<String> = emptyList(),
    val config: Map<String, Any?> = emptyMap(),
    val outputSchema: String? = null,
    val confidenceField: String? = null,
    val produces: List<String> = emptyList(),
    val consumes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @field:Valid val runInputs: List<RunInputFieldDto> = emptyList(),
)

data class TaskTemplateDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val abstract: Boolean,
    val extendsKey: String?,
    val implements: List<String>,
    val executor: TaskExecutor?,
    val scope: ScopeDto,
    val modelProfileId: UUID?,
    val prompt: TaskPromptDto,
    val skills: List<String>,
    val removeSkills: List<String>,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val tags: List<String>,
    val runInputs: List<RunInputFieldDto>,
    val builtIn: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
)

data class ResolvedPromptDto(val system: String?, val user: String?)

data class ResolvedSkillDto(val key: String, val name: String)

data class InterfaceCheckDto(val ok: Boolean, val problems: List<String>)

/** `TaskOverrides` schema: a workflow step's overrides of its template (ADR 0054, POST resolve). */
data class TaskOverridesDto(
    val modelProfileId: UUID? = null,
    val systemPromptAppend: String? = null,
    val promptAppend: String? = null,
    val promptReplace: String? = null,
    val addSkills: List<String> = emptyList(),
    val removeSkills: List<String> = emptyList(),
    val config: Map<String, Any?> = emptyMap(),
)

/** `ResolvedTask` schema: the merged preview, including abstract templates (then `executor` may be null). */
data class ResolvedTaskDto(
    val key: String,
    val name: String,
    val executor: TaskExecutor?,
    val modelProfileId: UUID?,
    val prompt: ResolvedPromptDto,
    val skills: List<ResolvedSkillDto>,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val interfaces: List<String>,
    val lineage: List<String>,
    val ports: List<String>,
    val runInputs: List<RunInputFieldDto>,
    val abstract: Boolean,
    val interfaceCheck: InterfaceCheckDto,
)

data class SkillInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String,
    @field:NotBlank val name: String,
    val description: String = "",
    @field:NotBlank val content: String,
    val scope: ScopeDto,
    val tags: List<String> = emptyList(),
)

data class SkillDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val content: String,
    val scope: ScopeDto,
    val tags: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
)
