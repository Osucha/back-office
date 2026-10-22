package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.tasks.ResolvedTask

/**
 * Resolves the model profile of a task: the resolved task's profile (template or node override), then
 * the project default (the review default for tasks that produce a review).
 */
class ModelResolver(private val catalog: ModelCatalog) {

    fun forTask(task: ResolvedTask, project: Project): ModelProfileSnapshot {
        val projectDefault = if (task.producesReview) project.defaults.reviewModelProfileId ?: project.defaults.modelProfileId else project.defaults.modelProfileId
        return resolve(task.modelProfileId ?: projectDefault, "task ${task.key}")
    }

    /** ADR 0050: a Claude Code login model cannot answer a gateway call. */
    fun forLlmCall(task: ResolvedTask, project: Project): ModelProfileSnapshot = forTask(task, project).also {
        if (it.runtime == ModelRuntime.CLAUDE_CODE_LOGIN) {
            throw InvalidStateException("${it.displayName} is a Claude Code login model; the LLM step ${task.key} needs a model of a connected provider")
        }
    }

    /** ADR 0050: a login model runs on its user's personal runner, which only private projects use (ADR 0040). */
    fun forCodingAgent(task: ResolvedTask, project: Project): ModelProfileSnapshot = forTask(task, project).also {
        if (it.runtime == ModelRuntime.CLAUDE_CODE_LOGIN && !project.personal) {
            throw InvalidStateException("${it.displayName} uses a personal Claude login, which works only in private projects; choose a model of a connected provider for ${task.key}")
        }
    }

    fun resolve(id: ModelProfileId?, purpose: String): ModelProfileSnapshot {
        if (id == null) throw InvalidStateException("no model profile configured for $purpose; set one on the template, the node or the project defaults")
        val profile = catalog.find(id) ?: throw InvalidStateException("model profile $id used by $purpose does not exist")
        if (!profile.enabled) throw InvalidStateException("model profile ${profile.displayName} used by $purpose is disabled")
        return profile
    }

    companion object {
        val ResolvedTask.producesReview: Boolean
            get() = "review" in produces || interfaces.any { it.equals("Reviewer", ignoreCase = true) }
    }
}
