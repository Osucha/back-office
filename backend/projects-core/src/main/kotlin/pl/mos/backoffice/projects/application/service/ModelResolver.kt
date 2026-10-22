package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelDefaults
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.StepKind
import java.util.UUID
import pl.mos.backoffice.shared.tasks.ResolvedTask

/**
 * Resolves the model profile of a task: the resolved task's profile (template or node override), then the project
 * default (the review default for tasks that produce a review), then (ADR 0050 step 2) the default model of the user
 * who started the run for this kind of step and the platform default ([ModelDefaults]).
 */
class ModelResolver(private val catalog: ModelCatalog, private val defaults: ModelDefaults? = null) {

    fun forTask(task: ResolvedTask, project: Project, userId: UUID? = null, stepKind: StepKind = StepKind.LLM_CALL): ModelProfileSnapshot {
        val projectDefault = if (task.producesReview) project.defaults.reviewModelProfileId ?: project.defaults.modelProfileId else project.defaults.modelProfileId
        val id = task.modelProfileId ?: projectDefault ?: defaults?.resolve(userId, stepKind)?.profileId
        return resolve(id, "task ${task.key}")
    }

    /** ADR 0050: a personal model (a login model answers through Claude Code, step 4) only its owner's private work. */
    fun forLlmCall(task: ResolvedTask, project: Project, userId: UUID? = null): ModelProfileSnapshot =
        forTask(task, project, userId, StepKind.LLM_CALL).also { requirePersonalUse(it, project, userId, task) }

    /** ADR 0050: a login model runs on its user's personal runner, which only private projects use (ADR 0040). */
    fun forCodingAgent(task: ResolvedTask, project: Project, userId: UUID? = null): ModelProfileSnapshot =
        forTask(task, project, userId, StepKind.CODING_AGENT).also { requirePersonalUse(it, project, userId, task) }

    /** ADR 0050 step 2: personal models (and login models) work only in private projects, for their owner. */
    private fun requirePersonalUse(profile: ModelProfileSnapshot, project: Project, userId: UUID?, task: ResolvedTask) {
        val personal = profile.ownerUserId != null || profile.runtime == ModelRuntime.CLAUDE_CODE_LOGIN
        if (personal && !project.personal) {
            throw InvalidStateException("${profile.displayName} is a personal model, which works only in private projects; choose a platform model for ${task.key}")
        }
        if (profile.ownerUserId != null && profile.ownerUserId != userId) {
            throw InvalidStateException("${profile.displayName} is another user's personal model; the run's starter cannot use it for ${task.key}")
        }
    }

    fun resolve(id: ModelProfileId?, purpose: String): ModelProfileSnapshot {
        if (id == null) {
            throw InvalidStateException("no model profile configured for $purpose; set one on the step, its template, the project defaults or a default model")
        }
        val profile = catalog.find(id) ?: throw InvalidStateException("model profile $id used by $purpose does not exist")
        if (!profile.enabled) throw InvalidStateException("model profile ${profile.displayName} used by $purpose is disabled")
        return profile
    }

    companion object {
        val ResolvedTask.producesReview: Boolean
            get() = "review" in produces || interfaces.any { it.equals("Reviewer", ignoreCase = true) }
    }
}
