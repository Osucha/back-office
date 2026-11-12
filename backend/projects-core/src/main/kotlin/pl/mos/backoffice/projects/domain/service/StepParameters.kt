package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.shared.tasks.ParameterBindingSource
import pl.mos.backoffice.shared.tasks.ResolvedParameter
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TemplateParameterType

/**
 * ADR 0062: the values of a step's parameters in a run. A parameter bound `START` reads the run parameter of its name (an
 * `ISSUE_KEY` parameter without one falls back to the run's issue key), one bound `ARTIFACT` the content of the latest
 * artefact of that name; when the run has no value, the preset value or the default applies ([ResolvedParameter.fallback]).
 * Every other parameter has the value the design gave it (the step's `VALUE`, the preset, the default).
 *
 * Values bound at run time come from outside the design: whoever started the run, or an agent's output. They are
 * untrusted ([untrusted]): rendered as data blocks (ADR 0029), never as trusted instruction text.
 */
object StepParameters {

    /** `params` of the template model: parameter name to the value the step uses in [run] (null = none). */
    fun values(task: ResolvedTask, run: WorkflowRun): Map<String, Any?> = task.parameters.associate { it.name to valueOf(it, run) }

    /** The value of [parameter] in [run]. */
    fun valueOf(parameter: ResolvedParameter, run: WorkflowRun): Any? {
        val binding = parameter.binding ?: return parameter.value
        val bound = when (binding.source) {
            ParameterBindingSource.VALUE -> return parameter.value
            ParameterBindingSource.START -> run.input.parameters[parameter.name]?.takeIf(::present)
                ?: run.input.issueKey?.takeIf { parameter.definition.type == TemplateParameterType.ISSUE_KEY && it.isNotBlank() }
            ParameterBindingSource.ARTIFACT -> binding.artifact?.let { run.artifactNamed(it) }?.content?.takeIf(::present)
        }
        return bound ?: parameter.fallback
    }

    /** Names of the parameters whose value comes from the run (start form or artefact): untrusted in prompts. */
    fun untrusted(task: ResolvedTask): Set<String> = task.parameters.filter { it.boundAtRun }.map { it.name }.toSet()

    private fun present(value: Any?): Boolean = value != null && !(value is String && value.isBlank())
}
