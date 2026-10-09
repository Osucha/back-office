package pl.mos.backoffice.projects.application.service.tasks

import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.service.ValidationIssue
import pl.mos.backoffice.shared.platform.PlatformFeatures
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.tasks.TaskExecutor

/**
 * Spec Kit is optional (ADR 0036): a project uses it only when its setting `specKit` is on AND the platform allows it.
 * When it is off, `SPEC_KIT` steps are validator errors, runs refuse to start them, and the designer palette hides
 * the Spec Kit templates (also templates that extend them: the check is on the resolved executor).
 */
class SpecKitStepCheck(private val catalog: TaskCatalog, private val features: PlatformFeatures = PlatformFeatures.DEFAULTS) {

    /** Spec Kit may be used in [project]. */
    fun effective(project: Project): Boolean = project.specKit && features.specKitAllowed()

    /** The platform allows projects to turn Spec Kit on. */
    fun allowedByPlatform(): Boolean = features.specKitAllowed()

    fun designIssues(project: Project, nodes: List<WorkflowNode>): List<ValidationIssue> =
        if (effective(project)) emptyList() else specKitSteps(project, nodes).map { node -> ValidationIssue(nodeId = node.id, field = FIELD, message = message(project)) }

    fun runBlockers(project: Project, nodes: List<WorkflowNode>): List<String> =
        if (effective(project)) emptyList() else specKitSteps(project, nodes).map { "step \"${it.name}\" (${it.id}): ${message(project)}" }

    private fun specKitSteps(project: Project, nodes: List<WorkflowNode>): List<WorkflowNode.Task> =
        nodes.filterIsInstance<WorkflowNode.Task>().filter { it.task.templateKey.isNotBlank() }.filter { node ->
            runCatching { catalog.resolve(node.task.templateKey, project.id.value, node.task.overrides) }.getOrNull()?.executor == TaskExecutor.SPEC_KIT
        }

    private fun message(project: Project): String =
        "this step uses Spec Kit, which is ${specKitOff(project.specKit)}; turn it on or remove the step (manual: $GUIDE)"

    companion object {
        const val FIELD = "task.templateKey"
        const val GUIDE = "projects#spec-kit"
    }
}
