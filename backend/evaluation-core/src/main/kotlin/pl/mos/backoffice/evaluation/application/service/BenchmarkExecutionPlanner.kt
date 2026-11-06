package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.domain.model.Attempt
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.Benchmark
import pl.mos.backoffice.evaluation.domain.model.CandidateRef
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.runner.AgentHarness
import java.time.Instant

/**
 * Builds the attempt matrix of a benchmark: selected tasks x candidates x repetitions. Attempts are ordered task-major
 * so repetitions of one task by different candidates land close together in time, which keeps provider load spread
 * evenly.
 *
 * ADR 0059: a candidate is an agent with a model; a pair that cannot work (a coding task for a model answer agent and
 * the reverse) creates no attempt and counts as not applicable for the candidate. A benchmark without candidates
 * (models only, [Benchmark.legacy]) runs each model as `model-answer` on prompt tasks and as the agent of its coding
 * harness on coding tasks, every pair applicable.
 */
object BenchmarkExecutionPlanner {

    /** One planned candidate: who ([agentKey], [agentName], [harness]) with which [model]. */
    private data class Planned(val agentKey: String, val agentName: String, val harness: AgentHarness?, val model: ModelProfile)

    fun plan(benchmark: Benchmark, suite: TaskSuite, models: List<ModelProfile>, now: Instant): List<Attempt> {
        val tasks = tasks(benchmark, suite)
        val byId = models.associateBy { it.id }
        fun model(id: ModelProfileId) = byId[id] ?: throw ValidationException("model profile $id not found", "modelProfileIds")

        return buildList {
            for (task in tasks) {
                for (repetition in 1..benchmark.repetitions) {
                    for (candidate in candidatesFor(benchmark, task.kind, ::model)) {
                        add(attempt(benchmark, task, repetition, candidate, now))
                    }
                }
            }
        }
    }

    /** The selected tasks of [suite] (all when the benchmark selects none); unknown task ids are refused. */
    fun tasks(benchmark: Benchmark, suite: TaskSuite): List<EvalTask> {
        val tasks = if (benchmark.taskIds.isEmpty()) suite.tasks else suite.tasks.filter { it.id in benchmark.taskIds.toSet() }
        if (tasks.isEmpty()) throw ValidationException("the suite has no tasks to run", "taskIds")
        val missingTasks = benchmark.taskIds.filterNot { id -> suite.tasks.any { it.id == id } }
        if (missingTasks.isNotEmpty()) throw ValidationException("tasks not in suite: ${missingTasks.joinToString()}", "taskIds")
        return tasks
    }

    /**
     * ADR 0059: per candidate (agent key and model) the number of selected tasks it cannot do. Empty for a benchmark
     * without candidates (each model has an agent for every kind of task).
     */
    fun notApplicable(benchmark: Benchmark, tasks: List<EvalTask>): Map<Pair<String, ModelProfileId>, Int> =
        benchmark.candidates.associate { c -> (c.agentKey to c.modelProfileId) to tasks.count { !c.applicable(it.kind) } }

    private fun candidatesFor(benchmark: Benchmark, kind: TaskKind, model: (ModelProfileId) -> ModelProfile): List<Planned> =
        if (benchmark.legacy) {
            val (key, name, harness) = CandidateRef.legacyAgent(kind, benchmark.codingHarness)
            benchmark.modelProfileIds.map { Planned(key, name, harness, model(it)) }
        } else {
            benchmark.candidates.filter { it.applicable(kind) }.map { Planned(it.agentKey, it.agentName, it.harness, model(it.modelProfileId)) }
        }

    private fun attempt(benchmark: Benchmark, task: EvalTask, repetition: Int, candidate: Planned, now: Instant) = Attempt(
        id = AttemptId.new(),
        benchmarkId = benchmark.id,
        taskId = task.id,
        taskName = task.name,
        category = task.category,
        difficulty = task.difficulty,
        kind = task.kind,
        modelProfileId = candidate.model.id,
        modelDisplayName = candidate.model.displayName,
        provider = candidate.model.ref.provider,
        repetition = repetition,
        createdAt = now,
        agentKey = candidate.agentKey,
        agentName = candidate.agentName,
        harness = candidate.harness,
    )
}
