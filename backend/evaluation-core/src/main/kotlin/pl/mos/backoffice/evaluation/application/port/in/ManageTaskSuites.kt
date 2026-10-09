package pl.mos.backoffice.evaluation.application.port.`in`

import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.model.TaskRepository
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import java.time.Duration

/** Use case: maintain task suites and their tasks. */
interface ManageTaskSuites {
    fun create(command: SuiteCommand): TaskSuite
    fun update(id: TaskSuiteId, command: SuiteCommand): TaskSuite
    fun get(id: TaskSuiteId): TaskSuite
    fun list(): List<TaskSuite>
    /** Fails with `InvalidStateException` while a benchmark of the suite is running. */
    fun delete(id: TaskSuiteId)

    fun addTask(suiteId: TaskSuiteId, command: TaskCommand): EvalTask
    fun updateTask(suiteId: TaskSuiteId, taskId: EvalTaskId, command: TaskCommand): EvalTask
    fun deleteTask(suiteId: TaskSuiteId, taskId: EvalTaskId)

    data class SuiteCommand(
        val name: String,
        val description: String? = null,
        val codingScoreWeights: CodingScoreWeights = CodingScoreWeights.DEFAULT,
    )

    data class TaskCommand(
        val name: String,
        val category: TaskCategory,
        val difficulty: Difficulty,
        val kind: TaskKind,
        val instructions: String,
        val context: String? = null,
        val rubric: String,
        val referenceAnswer: String? = null,
        val repository: TaskRepository? = null,
        val timeout: Duration = Duration.ofSeconds(EvalTask.DEFAULT_TIMEOUT_SECONDS),
        val weight: Double = 1.0,
    )
}
