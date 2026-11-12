package pl.mos.backoffice.tasks.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.runner.AgentDirectory
import pl.mos.backoffice.shared.runner.AgentSession
import pl.mos.backoffice.shared.tasks.ParameterBindingSource
import pl.mos.backoffice.shared.tasks.RunInputType
import pl.mos.backoffice.shared.tasks.ParameterSource
import pl.mos.backoffice.shared.tasks.TemplateParameterType
import pl.mos.backoffice.shared.tasks.TemplateCategory
import pl.mos.backoffice.tasks.domain.model.InstructionsMode
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

/** `RunInputField` schema (ADR 0053/0062): a field of the run start form; here the parameters a step binds `START`. */
data class RunInputFieldDto(
    val name: String,
    val label: String,
    val type: RunInputType = RunInputType.TEXT,
    val required: Boolean = false,
    val help: String? = null,
)

/**
 * ADR 0055: [key] omitted on create is derived from the name; omitted on update it stays. ADR 0061: no agent and no
 * model (the step chooses them); ADR 0062: no prompt, run inputs or sandbox policy (one [instructions] text); such
 * fields sent by older clients are ignored.
 */
data class TaskTemplateInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String? = null,
    @field:NotBlank val name: String,
    val description: String = "",
    val abstract: Boolean = false,
    val extendsKey: String? = null,
    val category: TemplateCategory? = null,
    val scope: ScopeDto,
    @field:Size(max = 100_000) val instructions: String? = null,
    val instructionsMode: InstructionsMode = InstructionsMode.APPEND,
    val config: Map<String, Any?> = emptyMap(),
    val outputSchema: String? = null,
    val confidenceField: String? = null,
    val produces: List<String> = emptyList(),
    val consumes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @field:Valid val parameters: List<TemplateParameterDto> = emptyList(),
    val parameterValues: Map<String, Any?> = emptyMap(),
    val requiresSkills: List<String> = emptyList(),
    /** ADR 0063: globs of files returned as artefacts. */
    val outputFiles: List<String> = emptyList(),
)

data class TaskTemplateDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val abstract: Boolean,
    val extendsKey: String?,
    val category: TemplateCategory?,
    val scope: ScopeDto,
    val instructions: String?,
    val instructionsMode: InstructionsMode,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val tags: List<String>,
    val parameters: List<TemplateParameterDto>,
    val parameterValues: Map<String, Any?>,
    val requiresSkills: List<String> = emptyList(),
    /** ADR 0063: globs of files returned as artefacts. */
    val outputFiles: List<String> = emptyList(),
    /** ADR 0061 (read-only): the merged config needs a coding agent. */
    val needsCodingAgent: Boolean = false,
    val builtIn: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
)

data class ResolvedSkillDto(val key: String, val name: String)

data class ResolvedRuleDto(val key: String, val name: String, val automatic: Boolean)

data class ResolutionCheckDto(val ok: Boolean, val problems: List<String>, val warnings: List<String> = emptyList())

/** `ParameterBinding` schema (ADR 0062). */
data class ParameterBindingDto(val source: ParameterBindingSource, val value: Any? = null, val artifact: String? = null)

/**
 * `TaskOverrides` schema: a workflow step's overrides of its template (ADR 0054, POST resolve). ADR 0062: the legacy
 * fields `systemPromptAppend` / `promptAppend` (read as [instructionsAppend]), `promptReplace` (as [instructionsReplace])
 * and `parameters` (as VALUE bindings) are read for older clients.
 */
data class TaskOverridesDto(
    val agentKey: String? = null,
    val modelProfileId: UUID? = null,
    val instructionsAppend: String? = null,
    val instructionsReplace: String? = null,
    val config: Map<String, Any?> = emptyMap(),
    val parameterBindings: Map<String, ParameterBindingDto> = emptyMap(),
    val systemPromptAppend: String? = null,
    val promptAppend: String? = null,
    val promptReplace: String? = null,
    val parameters: Map<String, Any?>? = null,
)

/** `AgentRef` schema (ADR 0056): who runs a step; no harness = model answer. */
data class AgentRefDto(
    val key: String,
    val name: String,
    val harness: AgentHarness?,
    val modelProfileId: UUID?,
    val rules: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
)

/** `ResolvedTask` schema: the merged preview, including abstract templates. */
data class ResolvedTaskDto(
    val key: String,
    val name: String,
    val agent: AgentRefDto,
    val modelProfileId: UUID?,
    val instructions: String?,
    val skills: List<ResolvedSkillDto>,
    val config: Map<String, Any?>,
    val outputSchema: String?,
    val confidenceField: String?,
    val produces: List<String>,
    val consumes: List<String>,
    val category: TemplateCategory?,
    val rules: List<ResolvedRuleDto>,
    val lineage: List<String>,
    val ports: List<String>,
    val runInputs: List<RunInputFieldDto>,
    val parameters: List<ResolvedParameterDto>,
    val abstract: Boolean,
    val check: ResolutionCheckDto,
)

data class SkillInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String? = null,
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

/** `AgentInput` schema (ADR 0056): [key] omitted on create is derived from the name; omitted on update it stays. */
data class AgentInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String? = null,
    @field:NotBlank val name: String,
    val description: String = "",
    val scope: ScopeDto,
    val harness: AgentHarness? = null,
    val modelProfileId: UUID? = null,
    @field:Size(max = 20_000) val instructions: String = "",
    val rules: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    @field:Valid @field:Size(max = 10) val mcpServers: List<AgentMcpServerDto> = emptyList(),
    val access: AgentAccessDto? = null,
    /** ADR 0062: default PER_STEP. */
    val session: AgentSession = AgentSession.PER_STEP,
    val tags: List<String> = emptyList(),
)

data class AgentDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val scope: ScopeDto,
    val harness: AgentHarness?,
    val modelProfileId: UUID?,
    val instructions: String,
    val rules: List<String>,
    val skills: List<String>,
    val mcpServers: List<AgentMcpServerDto>,
    val access: AgentAccessDto = AgentAccessDto(),
    val session: AgentSession = AgentSession.PER_STEP,
    val tags: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
)

/** `RuleInput` schema (ADR 0055): [key] omitted on create is derived from the name; omitted on update it stays. */
data class RuleInputDto(
    @field:Pattern(regexp = KEY_PATTERN) val key: String? = null,
    @field:NotBlank val name: String,
    val description: String = "",
    @field:NotBlank val content: String,
    val scope: ScopeDto,
    val automatic: Boolean = false,
    val tags: List<String> = emptyList(),
)

data class RuleDto(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val content: String,
    val scope: ScopeDto,
    val automatic: Boolean,
    val tags: List<String>,
    val builtIn: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ActorRefDto? = null,
)

/** `TemplateParameter` schema (ADR 0058); [default] is a string, number or boolean by type. */
data class TemplateParameterDto(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$") val name: String,
    @field:NotBlank @field:Size(max = 200) val label: String,
    val type: TemplateParameterType = TemplateParameterType.TEXT,
    val required: Boolean = false,
    val default: Any? = null,
    val options: List<String> = emptyList(),
    @field:Size(max = 1000) val help: String? = null,
)

/** `ResolvedParameter` schema (ADR 0058): the declaration with the value the step uses and where it comes from. */
data class ResolvedParameterDto(
    val name: String,
    val label: String,
    val type: TemplateParameterType,
    val required: Boolean,
    val default: Any?,
    val options: List<String>,
    val help: String?,
    val value: Any?,
    val source: ParameterSource,
)

/** `AgentMcpServer` schema (ADR 0057): a remote MCP server, no credentials. */
data class AgentMcpServerDto(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]{0,39}$") val name: String,
    @field:NotBlank @field:Size(max = 500) val url: String,
)

/** `AgentAccess` schema (ADR 0060): FULL (default), READ_ONLY, or PATHS with globs the agent may change; ADR 0062: the directory. */
data class AgentAccessDto(
    val mode: AgentAccessMode = AgentAccessMode.FULL,
    @field:Size(max = 50) val paths: List<String> = emptyList(),
    val directory: AgentDirectory = AgentDirectory.RUN,
)
