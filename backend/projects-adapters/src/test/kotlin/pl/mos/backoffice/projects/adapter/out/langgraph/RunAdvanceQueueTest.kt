package pl.mos.backoffice.projects.adapter.out.langgraph

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import pl.mos.backoffice.projects.adapter.FakeLlmGateway
import pl.mos.backoffice.projects.adapter.FakeModelCatalog
import pl.mos.backoffice.projects.adapter.FakeTaskCatalog
import pl.mos.backoffice.projects.adapter.ProjectsStandIns
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.adapter.RecordingAgentRunner
import pl.mos.backoffice.projects.application.port.`in`.ManageProjects
import pl.mos.backoffice.projects.application.port.`in`.ManageWorkflows
import pl.mos.backoffice.projects.application.port.`in`.RunQueries
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.domain.model.EndSpec
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.NodeExecutionStatus
import pl.mos.backoffice.projects.domain.model.ProjectDefaults
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunStatus
import pl.mos.backoffice.projects.domain.model.TaskBinding
import pl.mos.backoffice.projects.domain.model.WorkflowEdge
import pl.mos.backoffice.projects.domain.model.WorkflowNode
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.runner.RunnerJobStatus
import pl.mos.backoffice.shared.runner.RunnerMode
import pl.mos.backoffice.shared.runner.RunnerResult
import pl.mos.backoffice.shared.tasks.ResolvedTask
import pl.mos.backoffice.shared.tasks.TaskExecutor
import pl.mos.backoffice.shared.tasks.TaskPrompt
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private fun edge(from: String, port: String, to: String) = WorkflowEdge("$from-$port-$to", from, port, to)

private fun key(prefix: String) = prefix + UUID.randomUUID().toString().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "X")

private fun awaitRun(runs: RunQueries, runId: WorkflowRunId, vararg expected: RunStatus, timeoutMillis: Long = 60_000): WorkflowRun {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val run = runs.get(runId)
        if (run.status in expected) return run
        Thread.sleep(100)
    }
    throw AssertionError("run $runId did not reach ${expected.toList()} in time; current ${runs.get(runId).status}")
}

/**
 * Runner-backed nodes are non-blocking (ADR 0023/0024): the IMPLEMENT node submits its job with a
 * `RUN_ADVANCE` continuation and the graph interrupts; no worker thread waits. Delivering the result by
 * enqueueing the continuation (what the runner control plane does) resumes the run at the waiting node.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class NonBlockingRunnerNodeTest {

    @Autowired lateinit var projects: ManageProjects
    @Autowired lateinit var workflows: ManageWorkflows
    @Autowired lateinit var startRun: StartRun
    @Autowired lateinit var runQueries: RunQueries
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var runner: RecordingAgentRunner
    @Autowired lateinit var queue: WorkQueue
    @Autowired lateinit var engine: LangGraphWorkflowEngine
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `an IMPLEMENT node submits its job, frees the worker and resumes when the continuation delivers the result`() {
        runner.queue = queue
        tasks.register(
            ResolvedTask("implement-x", "Implement", TaskExecutor.IMPLEMENT, null, TaskPrompt(null, "implement {{input.issueKey}}"), emptyList(), mapOf("mergeRequest" to false),
                null, null, listOf("diff"), emptyList(), emptyList(), listOf("implement-x"), listOf("success", "failure")),
        )
        val projectKey = key("NBR")
        val project = projects.create(
            ManageProjects.ProjectCommand(key = projectKey, name = "P", repositories = listOf(GitRepository.legacy("https://git.example.com/g/x.git", "main")), defaults = ProjectDefaults(modelProfileId = catalog.add())),
        )
        val workflow = workflows.create(
            project.id,
            ManageWorkflows.WorkflowCommand(
                name = "runner",
                nodes = listOf(WorkflowNode.Start("start"), WorkflowNode.Task("impl", "Implement", task = TaskBinding("implement-x")), WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS))),
                edges = listOf(edge("start", "out", "impl"), edge("impl", "success", "ok")),
            ),
        )

        val started = startRun.start(workflow.id, RunInput("$projectKey-1"))

        // 1. the job is submitted with a RUN_ADVANCE continuation and the run stays RUNNING without a busy worker
        val deadline = System.currentTimeMillis() + 30_000
        while (runner.submissions.none { it.continuation.payload["runId"] == started.id.toString() } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val submission = runner.submissions.single { it.continuation.payload["runId"] == started.id.toString() }
        assertThat(submission.spec.mode).isEqualTo(RunnerMode.IMPLEMENT)
        assertThat(submission.continuation.kind).isEqualTo(WorkKinds.RUN_ADVANCE)
        assertThat(submission.continuation.dedupeKey).isEqualTo("RUN_ADVANCE:${started.id}")
        assertThat(submission.continuation.payload["resume"]).isEqualTo(mapOf("kind" to "RUNNER_RESULT", "nodeId" to "impl"))
        engine.awaitIdle(started.id)
        val jdbc = JdbcClient.create(dataSource)
        val liveItems = { jdbc.sql("SELECT count(*) FROM work_item WHERE dedupe_key = :k AND status IN ('READY', 'LEASED')").param("k", "RUN_ADVANCE:${started.id}").query(Long::class.javaObjectType).single() }
        val until = System.currentTimeMillis() + 10_000
        while (liveItems() > 0 && System.currentTimeMillis() < until) Thread.sleep(50)
        assertThat(liveItems()).describedAs("nothing holds a worker while the job runs").isZero()
        assertThat(engine.activeRuns()).isZero()
        val waiting = runQueries.get(started.id)
        assertThat(waiting.status).describedAs("failure: ${waiting.failure}").isEqualTo(RunStatus.RUNNING)
        assertThat(waiting.nodes.last { it.nodeId == "impl" }.status).isEqualTo(NodeExecutionStatus.RUNNING)

        // a duplicate or early continuation (no result yet) is harmless
        queue.enqueue(submission.continuation)
        Thread.sleep(500)
        assertThat(runQueries.get(started.id).status).isEqualTo(RunStatus.RUNNING)

        // 2. the job finishes: the continuation resumes the node, which maps the result
        runner.finish(
            submission.jobId,
            RunnerResult(RunnerJobStatus.SUCCEEDED, "implemented", "feature/x", "abc123", null, null, emptyList(), diff = "diff --git a/x b/x", usage = TokenUsage(1000, 200), reportedCostUsd = null, error = null, finishedAt = Instant.now()),
        )
        val done = awaitRun(runQueries, started.id, RunStatus.SUCCEEDED, RunStatus.FAILED)
        assertThat(done.status).describedAs("failure: ${done.failure}").isEqualTo(RunStatus.SUCCEEDED)
        val impl = done.nodes.single { it.nodeId == "impl" }
        assertThat(impl.status).isEqualTo(NodeExecutionStatus.SUCCEEDED)
        assertThat(impl.runnerJobId).isEqualTo(submission.jobId)
        assertThat(done.artifactNamed("diff")?.content).contains("diff --git")
        assertThat(done.nodes.map { it.nodeId }).containsExactly("start", "impl", "ok")
        assertThat(runner.submissions.count { it.continuation.payload["runId"] == started.id.toString() }).describedAs("submitted once").isEqualTo(1)
    }
}

/**
 * The newest checkpoint of a run is the last one written, whatever the database clock says. Reproduces the
 * intermittent `graph stopped before node ...` failure of [TwoReplicaRunAdvanceTest]: the PostgreSQL clock
 * stepped back (VM time sync) while a run was in flight, and the library's `ORDER BY saved_at DESC` took an
 * old checkpoint for the current one. A trigger makes every checkpoint of this test's runs look an hour older
 * than the previous one, i.e. the clock steps back before each write.
 */
@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class CheckpointClockStepTest {

    @Autowired lateinit var projects: ManageProjects
    @Autowired lateinit var workflows: ManageWorkflows
    @Autowired lateinit var startRun: StartRun
    @Autowired lateinit var runQueries: RunQueries
    @Autowired lateinit var catalog: FakeModelCatalog
    @Autowired lateinit var tasks: FakeTaskCatalog
    @Autowired lateinit var llm: FakeLlmGateway
    @Autowired lateinit var checkpoints: org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `a run whose checkpoints are written while the database clock steps back still completes`() {
        val jdbc = JdbcClient.create(dataSource)
        val projectKey = key("SKW")
        val d = "$"
        jdbc.sql(
            """
            CREATE OR REPLACE FUNCTION test_checkpoint_clock_step() RETURNS trigger AS $d$d
            BEGIN
                IF EXISTS (SELECT 1 FROM lg4jthread t JOIN workflow_run r ON r.id::text = t.thread_name
                           WHERE t.thread_id = NEW.thread_id AND r.project_key = '$projectKey') THEN
                    NEW.saved_at := CURRENT_TIMESTAMP - interval '1 hour' * (SELECT count(*) FROM lg4jcheckpoint c WHERE c.thread_id = NEW.thread_id);
                END IF;
                RETURN NEW;
            END $d$d LANGUAGE plpgsql
            """.trimIndent(),
        ).update()
        jdbc.sql("CREATE TRIGGER test_checkpoint_clock_step BEFORE INSERT ON lg4jcheckpoint FOR EACH ROW EXECUTE FUNCTION test_checkpoint_clock_step()").update()
        try {
            listOf("one", "two").forEach { k -> tasks.llm("skew-$k", k, produces = listOf("skew-$k")) }
            llm.reset()
            llm.answer("ok")
            val project = projects.create(
                ManageProjects.ProjectCommand(key = projectKey, name = "Skew", repositories = listOf(GitRepository.legacy("https://git.example.com/g/skew.git", "main")), defaults = ProjectDefaults(modelProfileId = catalog.add())),
            )
            val workflow = workflows.create(
                project.id,
                ManageWorkflows.WorkflowCommand(
                    name = "clock step",
                    nodes = listOf(
                        WorkflowNode.Start("start"), WorkflowNode.Task("s1", "One", task = TaskBinding("skew-one")), WorkflowNode.Task("s2", "Two", task = TaskBinding("skew-two")),
                        WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS)),
                    ),
                    edges = listOf(edge("start", "out", "s1"), edge("s1", "success", "s2"), edge("s2", "success", "ok")),
                ),
            )

            val started = startRun.start(workflow.id, RunInput("$projectKey-1"))
            val done = awaitRun(runQueries, started.id, RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)

            val savedAt = jdbc.sql(
                "SELECT c.saved_at FROM lg4jcheckpoint c JOIN lg4jthread t ON t.thread_id = c.thread_id WHERE t.thread_name = :n ORDER BY c.seq",
            ).param("n", started.id.toString()).query(java.time.OffsetDateTime::class.java).list()
            assertThat(savedAt).describedAs("the trigger made the clock step back on every write").hasSizeGreaterThan(3).isSortedAccordingTo(Comparator.reverseOrder())
            assertThat(done.status).describedAs("failure: ${done.failure}").isEqualTo(RunStatus.SUCCEEDED)
            assertThat(done.nodes.map { it.nodeId }).containsExactly("start", "s1", "s2", "ok")
            val config = org.bsc.langgraph4j.RunnableConfig.builder().threadId(started.id.toString()).build()
            assertThat(checkpoints.get(config).map { it.nextNodeId }).describedAs("the newest checkpoint is the last one written").contains(org.bsc.langgraph4j.GraphDefinition.END)
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_checkpoint_clock_step ON lg4jcheckpoint").update()
            jdbc.sql("DROP FUNCTION IF EXISTS test_checkpoint_clock_step()").update()
        }
    }
}

/**
 * Two replicas (two Spring contexts with their own worker loop, LLM stand-in and caches) on one PostgreSQL:
 * every run started on replica A is advanced by exactly one worker at a time; each node runs exactly once in
 * total, whichever replica picks the run up.
 */
class TwoReplicaRunAdvanceTest {

    @TestConfiguration(proxyBeanMethods = false)
    class ReplicaBeans : ProjectsStandIns()

    private fun replica(db: PostgreSQLContainer, name: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(ProjectsTestApplication::class.java, ReplicaBeans::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.datasource.url=${db.jdbcUrl}", "spring.datasource.username=${db.username}", "spring.datasource.password=${db.password}",
                "backoffice.platform.instance-id=$name", "backoffice.platform.worker.poll-interval=100ms", "backoffice.platform.worker.concurrency=4",
            )
            .run()

    @Test
    fun `runs started on one replica are advanced exactly once across two workers`() {
        PostgreSQLContainer("postgres:16-alpine").use { db ->
            db.start()
            val a = replica(db, "replica-a")
            val b = replica(db, "replica-b")
            try {
                val profile = ModelProfileId.new()
                listOf(a, b).forEach { ctx ->
                    ctx.getBean(FakeModelCatalog::class.java).add(profile)
                    ctx.getBean(FakeTaskCatalog::class.java).let { t -> listOf("one", "two", "three").forEach { k -> t.llm(k, k, produces = listOf(k)) } }
                    ctx.getBean(FakeLlmGateway::class.java).let { it.reset(); it.answer("ok") }
                }
                val projectKey = key("TWO")
                val project = a.getBean(ManageProjects::class.java).create(
                    ManageProjects.ProjectCommand(key = projectKey, name = "Two", repositories = listOf(GitRepository.legacy("https://git.example.com/g/two.git", "main")), defaults = ProjectDefaults(modelProfileId = profile)),
                )
                val workflow = a.getBean(ManageWorkflows::class.java).create(
                    project.id,
                    ManageWorkflows.WorkflowCommand(
                        name = "three steps",
                        nodes = listOf(
                            WorkflowNode.Start("start"), WorkflowNode.Task("s1", "One", task = TaskBinding("one")), WorkflowNode.Task("s2", "Two", task = TaskBinding("two")),
                            WorkflowNode.Task("s3", "Three", task = TaskBinding("three")), WorkflowNode.End("ok", "ok", end = EndSpec(RunOutcome.SUCCESS)),
                        ),
                        edges = listOf(edge("start", "out", "s1"), edge("s1", "success", "s2"), edge("s2", "success", "s3"), edge("s3", "success", "ok")),
                    ),
                )
                val start = a.getBean(StartRun::class.java)
                val runIds = (1..RUNS).map { start.start(workflow.id, RunInput("$projectKey-$it")).id }

                val queries = a.getBean(RunQueries::class.java)
                val finished = runIds.map { awaitRun(queries, it, RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED) }

                assertThat(finished.map { it.status }.toSet())
                    .describedAs("failures: %s", finished.mapNotNull { r -> r.failure?.let { "${r.id}: $it" } })
                    .containsExactly(RunStatus.SUCCEEDED)
                finished.forEach { run ->
                    assertThat(run.nodes.map { it.nodeId }).describedAs("run ${run.id}: every node exactly once").containsExactly("start", "s1", "s2", "s3", "ok")
                }
                val promptsA = a.getBean(FakeLlmGateway::class.java).prompts.size
                val promptsB = b.getBean(FakeLlmGateway::class.java).prompts.size
                assertThat(promptsA + promptsB).describedAs("one LLM call per task node, no duplicates").isEqualTo(RUNS * 3)
                assertThat(promptsB).describedAs("replica B's worker took part").isGreaterThan(0)
            } finally {
                b.close()
                a.close()
            }
        }
    }

    private companion object {
        const val RUNS = 12
    }
}
