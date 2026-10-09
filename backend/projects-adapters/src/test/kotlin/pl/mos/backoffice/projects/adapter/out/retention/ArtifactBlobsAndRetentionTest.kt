package pl.mos.backoffice.projects.adapter.out.retention

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.projects.adapter.ProjectsTestApplication
import pl.mos.backoffice.projects.adapter.ProjectsTestBeans
import pl.mos.backoffice.projects.adapter.out.persistence.ArtifactStore
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.model.Artifact
import pl.mos.backoffice.projects.domain.model.ArtifactId
import pl.mos.backoffice.projects.domain.model.ArtifactKind
import pl.mos.backoffice.projects.domain.model.GitRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.RunOutcome
import pl.mos.backoffice.projects.domain.model.RunTrigger
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.DefaultWorkflows
import pl.mos.backoffice.shared.testing.InMemoryBlobStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(classes = [ProjectsTestApplication::class])
@Import(ProjectsTestBeans::class)
class ArtifactBlobsAndRetentionTest {

    @Autowired lateinit var projects: ProjectRepository
    @Autowired lateinit var workflows: WorkflowDefinitionRepository
    @Autowired lateinit var runs: WorkflowRunRepository
    @Autowired lateinit var blobs: InMemoryBlobStore
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var retention: DataRetentionJobs

    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val large = "# Diff\n" + "x".repeat(70 * 1024)
    private val larger = "# Diff v2\n" + "y".repeat(90 * 1024)

    private fun newRun(): WorkflowRun {
        val key = "BL" + UUID.randomUUID().toString().take(6).uppercase()
        val project = projects.save(
            Project(ProjectId.new(), key, key, repositories = listOf(GitRepository.legacy("https://git.example.com/g/$key.git", "main")), createdAt = now, updatedAt = now),
        )
        val definition = workflows.save(DefaultWorkflows.issueToReviewedMr(project.id, now))
        return WorkflowRun.create(project, definition, RunTrigger(TriggerSource.MANUAL, null), RunInput("$key-1", emptyMap(), null), now).start(now)
    }

    private fun artifactRow(id: ArtifactId): Map<String, Any?> =
        jdbc.sql("SELECT content, blob_key, sha256, size_bytes, content_expired_at FROM artifact WHERE id = :id").param("id", id.value).query().singleRow()

    @Test
    fun `small artefacts stay inline, large ones go to the blob store and read back unchanged`() {
        val small = Artifact(ArtifactId.new(), "specify", "spec", ArtifactKind.SPEC, content = "# Spec", createdAt = now)
        val big = Artifact(ArtifactId.new(), "implement", "diff", ArtifactKind.DIFF, mediaType = "text/x-diff", content = large, createdAt = now)
        val run = runs.save(newRun().addArtifact(small).addArtifact(big))

        val smallRow = artifactRow(small.id)
        assertThat(smallRow["blob_key"]).isNull()
        assertThat(smallRow["content"]).isEqualTo("# Spec")

        val bigRow = artifactRow(big.id)
        val key = "runs/${run.id.value}/artifacts/${big.id.value}"
        assertThat(bigRow["blob_key"]).isEqualTo(key)
        assertThat(bigRow["content"]).isEqualTo("")
        assertThat(bigRow["size_bytes"]).isEqualTo(large.toByteArray().size.toLong())
        assertThat(bigRow["sha256"]).isEqualTo(ArtifactStore.sha256(large.toByteArray()))
        assertThat(String(blobs.objects.getValue(key).bytes)).isEqualTo(large)
        assertThat(blobs.objects.getValue(key).contentType).startsWith("text/x-diff")

        val loaded = runs.findById(run.id)!!
        assertThat(loaded.artifacts.map { it.content }).containsExactly("# Spec", large)
        assertThat(loaded.artifacts.last().sizeBytes).isEqualTo(big.sizeBytes)
    }

    @Test
    fun `saving an unchanged large artefact does not rewrite it, an edit writes a new key and queues the old one`() {
        val big = Artifact(ArtifactId.new(), "implement", "diff", ArtifactKind.DIFF, content = large, createdAt = now)
        val run = runs.save(newRun().addArtifact(big))
        val firstKey = artifactRow(big.id)["blob_key"] as String

        runs.save(runs.findById(run.id)!!)
        assertThat(artifactRow(big.id)["blob_key"]).isEqualTo(firstKey)

        runs.save(runs.findById(run.id)!!.replaceArtifactContent(big.id, larger))
        val secondKey = artifactRow(big.id)["blob_key"] as String
        assertThat(secondKey).startsWith("$firstKey.").isNotEqualTo(firstKey)
        assertThat(runs.findById(run.id)!!.artifacts.single().content).isEqualTo(larger)
        assertThat(gcQueued(firstKey)).isTrue()

        // shrinking below the limit moves the content back inline
        runs.save(runs.findById(run.id)!!.replaceArtifactContent(big.id, "tiny"))
        assertThat(artifactRow(big.id)["blob_key"]).isNull()
        assertThat(runs.findById(run.id)!!.artifacts.single().content).isEqualTo("tiny")
        assertThat(gcQueued(secondKey)).isTrue()

        retention.processBlobGc()
        assertThat(blobs.objects).doesNotContainKeys(firstKey, secondKey)
        assertThat(gcQueued(firstKey)).isFalse()
    }

    @Test
    fun `deleting a project's runs queues their blob prefix, the retention job deletes it`() {
        val big = Artifact(ArtifactId.new(), "implement", "diff", ArtifactKind.DIFF, content = large, createdAt = now)
        val run = runs.save(newRun().addArtifact(big))
        assertThat(blobs.keys("runs/${run.id.value}/")).hasSize(1)

        runs.deleteByProjectId(run.projectId)
        assertThat(gcQueued("runs/${run.id.value}/")).isTrue()

        retention.run()
        assertThat(blobs.keys("runs/${run.id.value}/")).isEmpty()
        assertThat(gcQueued("runs/${run.id.value}/")).isFalse()
    }

    @Test
    fun `blob content of runs finished before the artefact retention expires, recent runs keep it`() {
        val oldBig = Artifact(ArtifactId.new(), "implement", "diff", ArtifactKind.DIFF, content = large, createdAt = now)
        val old = runs.save(newRun().addArtifact(oldBig).succeeded(RunOutcome.SUCCESS, now))
        val recentBig = Artifact(ArtifactId.new(), "implement", "diff", ArtifactKind.DIFF, content = large, createdAt = now)
        val recent = runs.save(newRun().addArtifact(recentBig).succeeded(RunOutcome.SUCCESS, now))
        jdbc.sql("UPDATE workflow_run SET finished_at = now() - interval '200 days' WHERE id = :id").param("id", old.id.value).update()

        retention.expireArtifactBlobs()

        assertThat(blobs.keys("runs/${old.id.value}/")).isEmpty()
        val row = artifactRow(oldBig.id)
        assertThat(row["blob_key"]).isNull()
        assertThat(row["content_expired_at"]).isNotNull()
        assertThat(runs.findById(old.id)!!.artifacts.single().content).isEqualTo(ArtifactStore.EXPIRED_NOTICE)
        // saving the loaded run again does not resurrect or rewrite the expired content
        runs.save(runs.findById(old.id)!!)
        assertThat(artifactRow(oldBig.id)["content"]).isEqualTo("")

        assertThat(blobs.keys("runs/${recent.id.value}/")).hasSize(1)
        assertThat(runs.findById(recent.id)!!.artifacts.single().content).isEqualTo(large)
    }

    @Test
    fun `legacy large inline artefacts are moved to the blob store by the job`() {
        val big = Artifact(ArtifactId.new(), "implement", "diff", ArtifactKind.DIFF, content = "small", createdAt = now)
        val run = runs.save(newRun().addArtifact(big))
        // what a pre-V103 row looks like
        jdbc.sql("UPDATE artifact SET content = :c, size_bytes = :s WHERE id = :id")
            .param("c", large).param("s", large.toByteArray().size.toLong()).param("id", big.id.value).update()

        assertThat(retention.migrateInlineArtifacts()).isGreaterThanOrEqualTo(1)

        val row = artifactRow(big.id)
        assertThat(row["blob_key"]).isEqualTo("runs/${run.id.value}/artifacts/${big.id.value}")
        assertThat(row["content"]).isEqualTo("")
        assertThat(runs.findById(run.id)!!.artifacts.single().content).isEqualTo(large)
    }

    @Test
    fun `checkpoints of old finished runs and orphaned threads are purged, active runs keep theirs`() {
        val old = runs.save(newRun().succeeded(RunOutcome.SUCCESS, now))
        val active = runs.save(newRun())
        jdbc.sql("UPDATE workflow_run SET finished_at = now() - interval '40 days' WHERE id = :id").param("id", old.id.value).update()
        val oldThread = thread(old.id.value.toString())
        val activeThread = thread(active.id.value.toString())
        val orphanThread = thread(UUID.randomUUID().toString(), createdAt = "now() - interval '2 days'")
        val freshOrphan = thread(UUID.randomUUID().toString(), createdAt = "now()")

        retention.purgeCheckpoints()

        assertThat(checkpoints(oldThread)).isZero()
        assertThat(checkpoints(orphanThread)).isZero()
        assertThat(checkpoints(activeThread)).isEqualTo(2)
        assertThat(checkpoints(freshOrphan)).isEqualTo(2)
        assertThat(jdbc.sql("SELECT count(*) FROM lg4jthread WHERE thread_id = :t").param("t", oldThread).query(Long::class.java).single()).isZero()
    }

    @Test
    fun `runner job events older than the retention are deleted`() {
        jdbc.sql("CREATE TABLE IF NOT EXISTS runner_job_event (id bigserial PRIMARY KEY, job_id uuid NOT NULL, at timestamptz NOT NULL, message text NOT NULL, progress boolean NOT NULL DEFAULT false)").update()
        val job = UUID.randomUUID()
        jdbc.sql("INSERT INTO runner_job_event (job_id, at, message) SELECT :job, now() - interval '40 days', 'old ' || g FROM generate_series(1, 30) g").param("job", job).update()
        jdbc.sql("INSERT INTO runner_job_event (job_id, at, message) VALUES (:job, now(), 'new')").param("job", job).update()

        retention.purgeRunnerJobEvents()

        assertThat(jdbc.sql("SELECT message FROM runner_job_event WHERE job_id = :job").param("job", job).query(String::class.java).list()).containsExactly("new")
    }

    @Test
    fun `a second concurrent run is skipped by the advisory lock`() {
        val nested = AdvisoryLock.tryRun(dataSourceOf(), DataRetentionJobs.LOCK_NAME) { listOf(retention.run()) }
        assertThat(nested).isNotNull()
        assertThat(nested!!.single()).isNull()
        assertThat(retention.run()).isNotNull()
    }

    @Autowired lateinit var dataSource: javax.sql.DataSource
    private fun dataSourceOf() = dataSource

    private fun gcQueued(prefix: String): Boolean =
        jdbc.sql("SELECT count(*) FROM run_blob_gc WHERE key_prefix = :p").param("p", prefix).query(Long::class.java).single() > 0

    private fun thread(name: String, createdAt: String = "now()"): Long {
        val id = jdbc.sql("INSERT INTO lg4jthread (thread_name, created_at) VALUES (:n, $createdAt) RETURNING thread_id").param("n", name).query(Long::class.java).single()
        repeat(2) {
            jdbc.sql("INSERT INTO lg4jcheckpoint (checkpoint_id, thread_id, node_id, state_data, state_content_type) VALUES (:c, :t, 'n', '{}'::jsonb, 'application/json')")
                .param("c", UUID.randomUUID()).param("t", id).update()
        }
        return id
    }

    private fun checkpoints(threadId: Long): Long =
        jdbc.sql("SELECT count(*) FROM lg4jcheckpoint WHERE thread_id = :t").param("t", threadId).query(Long::class.java).single()
}
