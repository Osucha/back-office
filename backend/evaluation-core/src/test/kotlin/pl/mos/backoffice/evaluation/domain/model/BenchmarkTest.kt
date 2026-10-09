package pl.mos.backoffice.evaluation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.evaluation.application.service.BenchmarkExecutionPlanner
import pl.mos.backoffice.evaluation.support.Fixtures
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException

class BenchmarkTest {

    private val judge = Fixtures.profile("Judge")
    private val a = Fixtures.profile("A")
    private val b = Fixtures.profile("B")
    private val suite = Fixtures.suite(Fixtures.promptTask("t1"), Fixtures.promptTask("t2"), Fixtures.codingTask("t3"))

    @Test
    fun `draft starts, running finishes completed when anything succeeded`() {
        val draft = Fixtures.benchmark(suite, listOf(a), judge)
        assertThat(draft.status).isEqualTo(BenchmarkStatus.DRAFT)

        val running = draft.start(Fixtures.now)
        assertThat(running.status).isEqualTo(BenchmarkStatus.RUNNING)
        assertThat(running.startedAt).isEqualTo(Fixtures.now)

        val done = running.finish(anySucceeded = true, now = Fixtures.now.plusSeconds(5))
        assertThat(done.status).isEqualTo(BenchmarkStatus.COMPLETED)
        assertThat(done.finishedAt).isEqualTo(Fixtures.now.plusSeconds(5))
        assertThat(done.finish(anySucceeded = false, now = Fixtures.now)).isEqualTo(done)
    }

    @Test
    fun `running finishes failed when nothing succeeded`() {
        val failed = Fixtures.benchmark(suite, listOf(a), judge).start(Fixtures.now).finish(anySucceeded = false, now = Fixtures.now)
        assertThat(failed.status).isEqualTo(BenchmarkStatus.FAILED)
    }

    @Test
    fun `illegal transitions are refused`() {
        val draft = Fixtures.benchmark(suite, listOf(a), judge)
        assertThatThrownBy { draft.start(Fixtures.now).start(Fixtures.now) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { draft.finish(true, Fixtures.now) }.isInstanceOf(InvalidStateException::class.java)
        val cancelled = draft.cancel(Fixtures.now)
        assertThat(cancelled.status).isEqualTo(BenchmarkStatus.CANCELLED)
        assertThatThrownBy { cancelled.cancel(Fixtures.now) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `validation rejects empty models, duplicates and too many repetitions`() {
        assertThatThrownBy { Fixtures.benchmark(suite, emptyList(), judge) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { Fixtures.benchmark(suite, listOf(a, a), judge) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { Fixtures.benchmark(suite, listOf(a), judge, repetitions = 6) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `planner builds tasks x models x repetitions matrix with snapshots`() {
        val benchmark = Fixtures.benchmark(suite, listOf(a, b), judge, repetitions = 2)

        val attempts = BenchmarkExecutionPlanner.plan(benchmark, suite, listOf(a, b), Fixtures.now)

        assertThat(attempts).hasSize(3 * 2 * 2)
        assertThat(attempts.map { it.id }.toSet()).hasSize(12)
        assertThat(attempts).allSatisfy { assertThat(it.status).isEqualTo(AttemptStatus.PENDING) }
        assertThat(attempts.groupBy { it.taskId }.values.map { it.size }).containsOnly(4)
        assertThat(attempts.groupBy { it.modelProfileId }.values.map { it.size }).containsOnly(6)
        assertThat(attempts.map { it.repetition }.toSet()).containsExactlyInAnyOrder(1, 2)
        val coding = attempts.first { it.kind == TaskKind.CODING_JOB }
        assertThat(coding.taskName).isEqualTo("t3")
        assertThat(coding.category).isEqualTo(TaskCategory.CODING)
        assertThat(attempts.first { it.modelProfileId == b.id }.modelDisplayName).isEqualTo("B")
    }

    @Test
    fun `planner honours a task subset and rejects unknown tasks`() {
        val subset = Fixtures.benchmark(suite, listOf(a), judge).copy(taskIds = listOf(suite.tasks[0].id))
        assertThat(BenchmarkExecutionPlanner.plan(subset, suite, listOf(a), Fixtures.now)).hasSize(1)

        val unknown = subset.copy(taskIds = listOf(EvalTaskId.new()))
        assertThatThrownBy { BenchmarkExecutionPlanner.plan(unknown, suite, listOf(a), Fixtures.now) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `progress counts terminal and failed attempts`() {
        val t = suite.tasks[0]
        val counts = BenchmarkProgressCounts.of(
            listOf(
                Fixtures.attempt(BenchmarkId.new(), t, a, status = AttemptStatus.SUCCEEDED),
                Fixtures.attempt(BenchmarkId.new(), t, a, status = AttemptStatus.FAILED),
                Fixtures.attempt(BenchmarkId.new(), t, a, status = AttemptStatus.TIMED_OUT),
                Fixtures.attempt(BenchmarkId.new(), t, a, status = AttemptStatus.RUNNING),
            ),
        )
        assertThat(counts).isEqualTo(BenchmarkProgressCounts(total = 4, done = 3, failed = 2))
        assertThat(counts.complete).isFalse()
    }
}
