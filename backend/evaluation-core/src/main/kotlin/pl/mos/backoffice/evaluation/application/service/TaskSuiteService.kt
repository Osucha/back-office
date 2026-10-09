package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.application.EvaluationPermissions
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites.SuiteCommand
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites.TaskCommand
import pl.mos.backoffice.evaluation.application.port.out.BenchmarkRepository
import pl.mos.backoffice.evaluation.application.port.out.TaskSuiteRepository
import pl.mos.backoffice.evaluation.domain.model.BenchmarkStatus
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.AuthorizationGate

/**
 * Task suite use cases. Tasks are edited through their suite (aggregate root). Reads need
 * `evaluation.view`, changes `evaluation.run`; changes are audited (`SUITE_*`, `EVAL_TASK_*`).
 */
class TaskSuiteService(
    private val suites: TaskSuiteRepository,
    private val benchmarks: BenchmarkRepository,
    private val clock: Clock,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageTaskSuites {

    override fun create(command: SuiteCommand): TaskSuite {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val saved = suites.save(TaskSuite.create(command.name, command.description, command.codingScoreWeights, now = clock.now(), actor = user.actor))
        audit.record(suiteEntry("SUITE_CREATE", saved, mapOf("name" to saved.name)))
        return saved
    }

    override fun update(id: TaskSuiteId, command: SuiteCommand): TaskSuite {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val existing = load(id)
        val saved = suites.save(existing.update(command.name, command.description, command.codingScoreWeights, clock.now(), user.actor))
        val changes = AuditableChanges.changed(
            "name" to (existing.name to saved.name),
            "description" to (existing.description to saved.description),
            "codingScoreWeights" to (existing.codingScoreWeights.toString() to saved.codingScoreWeights.toString()),
        )
        if (changes.isNotEmpty()) audit.record(suiteEntry("SUITE_UPDATE", saved, changes))
        return saved
    }

    override fun get(id: TaskSuiteId): TaskSuite {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return load(id)
    }

    override fun list(): List<TaskSuite> {
        gate.require(EvaluationPermissions.EVALUATION_VIEW)
        return suites.findAll().sortedBy { it.name.lowercase() }
    }

    override fun delete(id: TaskSuiteId) {
        gate.require(EvaluationPermissions.EVALUATION_RUN)
        val existing = load(id)
        if (benchmarks.existsForSuiteWithStatus(id, BenchmarkStatus.RUNNING)) {
            throw InvalidStateException("task suite $id has a running benchmark")
        }
        suites.delete(id)
        audit.record(suiteEntry("SUITE_DELETE", existing, mapOf("name" to existing.name, "tasks" to existing.tasks.size)))
    }

    override fun addTask(suiteId: TaskSuiteId, command: TaskCommand): EvalTask {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val task = toTask(EvalTaskId.new(), command)
        suites.save(load(suiteId).addTask(task, clock.now(), user.actor))
        audit.record(taskEntry("EVAL_TASK_CREATE", suiteId, task, mapOf("name" to task.name, "kind" to task.kind.name, "category" to task.category.name)))
        return task
    }

    override fun updateTask(suiteId: TaskSuiteId, taskId: EvalTaskId, command: TaskCommand): EvalTask {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val suite = load(suiteId)
        val existing = suite.task(taskId)
        val task = toTask(taskId, command)
        suites.save(suite.updateTask(task, clock.now(), user.actor))
        val changes = AuditableChanges.changed(
            "name" to (existing.name to task.name),
            "category" to (existing.category.name to task.category.name),
            "difficulty" to (existing.difficulty.name to task.difficulty.name),
            "kind" to (existing.kind.name to task.kind.name),
            "instructionsLength" to (existing.instructions.length to task.instructions.length),
            "rubricLength" to (existing.rubric.length to task.rubric.length),
            "repository" to (existing.repository?.url to task.repository?.url),
            "timeoutSeconds" to (existing.timeout.seconds to task.timeout.seconds),
            "weight" to (existing.weight to task.weight),
        )
        if (changes.isNotEmpty()) audit.record(taskEntry("EVAL_TASK_UPDATE", suiteId, task, changes))
        return task
    }

    override fun deleteTask(suiteId: TaskSuiteId, taskId: EvalTaskId) {
        val user = gate.require(EvaluationPermissions.EVALUATION_RUN)
        val suite = load(suiteId)
        val existing = suite.task(taskId)
        suites.save(suite.removeTask(taskId, clock.now(), user.actor))
        audit.record(taskEntry("EVAL_TASK_DELETE", suiteId, existing, mapOf("name" to existing.name)))
    }

    private fun load(id: TaskSuiteId): TaskSuite = suites.findById(id) ?: throw NotFoundException("TaskSuite", id)

    private fun suiteEntry(operation: String, s: TaskSuite, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "TaskSuite", entityId = s.id.toString(), details = details)

    private fun taskEntry(operation: String, suiteId: TaskSuiteId, t: EvalTask, details: Map<String, Any?>) =
        AuditEntry(operation = operation, entityType = "EvalTask", entityId = t.id.toString(), details = mapOf("suiteId" to suiteId.toString()) + details)

    private fun toTask(id: EvalTaskId, c: TaskCommand) = EvalTask(
        id = id,
        name = c.name,
        category = c.category,
        difficulty = c.difficulty,
        kind = c.kind,
        instructions = c.instructions,
        context = c.context,
        rubric = c.rubric,
        referenceAnswer = c.referenceAnswer,
        repository = c.repository,
        timeout = c.timeout,
        weight = c.weight,
    )
}
