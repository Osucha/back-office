package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant

/**
 * Builds the attempt matrix of a benchmark: selected tasks x models x repetitions. Attempts are
 * ordered task-major so repetitions of one task by different models land close together in time,
 * which keeps provider load spread evenly.
 */
object BenchmarkExecutionPlanner {

    fun plan(benchmark: Benchmark, suite: TaskSuite, models: List<ModelProfile>, now: Instant): List<Attempt> {
        val tasks = if (benchmark.taskIds.isEmpty()) suite.tasks else suite.tasks.filter { it.id in benchmark.taskIds.toSet() }
        if (tasks.isEmpty()) throw ValidationException("the suite has no tasks to run", "taskIds")
        val missingTasks = benchmark.taskIds.filterNot { id -> suite.tasks.any { it.id == id } }
        if (missingTasks.isNotEmpty()) throw ValidationException("tasks not in suite: ${missingTasks.joinToString()}", "taskIds")
        val byId = models.associateBy { it.id }
        val orderedModels = benchmark.modelProfileIds.map { byId[it] ?: throw ValidationException("model profile $it not found", "modelProfileIds") }

        return buildList {
            for (task in tasks) {
                for (repetition in 1..benchmark.repetitions) {
                    for (model in orderedModels) {
                        add(
                            Attempt(
                                id = AttemptId.new(),
                                benchmarkId = benchmark.id,
                                taskId = task.id,
                                taskName = task.name,
                                category = task.category,
                                difficulty = task.difficulty,
                                kind = task.kind,
                                modelProfileId = model.id,
                                modelDisplayName = model.displayName,
                                provider = model.ref.provider,
                                repetition = repetition,
                                createdAt = now,
                            ),
                        )
                    }
                }
            }
        }
    }
}
