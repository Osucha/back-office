package pl.mos.backoffice.evaluation.adapter.`in`.rest

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.evaluation.application.port.`in`.ListProviders
import pl.mos.backoffice.evaluation.application.port.`in`.ManageBenchmarks
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelProfiles
import pl.mos.backoffice.evaluation.application.port.`in`.ManageTaskSuites
import pl.mos.backoffice.evaluation.domain.model.AttemptId
import pl.mos.backoffice.evaluation.domain.model.BenchmarkId
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskSuiteId
import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.evaluation.application.port.`in`.ManageModelDefaults
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.runner.AgentHarness
import java.net.URI
import java.util.UUID

/** `/api/v1/model-profiles` (openapi tag `model-profiles`). */
@RestController
@RequestMapping("/api/v1/model-profiles")
class ModelProfileController(
    private val profiles: ManageModelProfiles,
    private val providers: ListProviders,
    private val gateway: LlmGateway,
    private val defaults: ManageModelDefaults,
) {
    // ADR 0048: a Claude Code login model needs no provider connection
    private fun dto(p: ModelProfile) = RestMappers.profile(p, p.viaClaudeCodeLogin || gateway.isConfigured(p.ref))

    @GetMapping
    fun list(@RequestParam(required = false) enabled: Boolean?): List<ModelProfileDto> = profiles.list(enabled).map(::dto)

    @PostMapping
    fun create(@RequestBody body: ModelProfileInputDto): ResponseEntity<ModelProfileDto> {
        val created = profiles.create(RestMappers.profileCommand(body))
        return ResponseEntity.created(URI.create("/api/v1/model-profiles/${created.id}")).body(dto(created))
    }

    /** ADR 0050 step 2: the platform default models and the caller's own, per kind of step. */
    @GetMapping("/defaults")
    fun defaults(): ModelDefaultsDto = defaultsDto(defaults.get())

    @PutMapping("/defaults/platform")
    fun setPlatformDefault(@RequestBody body: ModelDefaultInputDto): ModelDefaultsDto =
        defaultsDto(defaults.setPlatform(body.stepKind, body.profileId?.let(::ModelProfileId)))

    @PutMapping("/defaults/mine")
    fun setMyDefault(@RequestBody body: ModelDefaultInputDto): ModelDefaultsDto =
        defaultsDto(defaults.setMine(body.stepKind, body.profileId?.let(::ModelProfileId)))

    /** ADR 0050 step 2: the models that can be picked as a default (scope PLATFORM or PERSONAL), with route and reason. */
    @GetMapping("/options")
    fun options(
        @RequestParam stepKind: StepKind,
        @RequestParam(required = false) harness: AgentHarness?,
        @RequestParam(defaultValue = "PERSONAL") scope: String,
    ): List<ModelOptionDto> = defaults.options(stepKind, harness, scope.equals("PLATFORM", ignoreCase = true)).map(ModelOptionDto::of)

    private fun defaultsDto(d: ManageModelDefaults.Defaults) = ModelDefaultsDto(
        platform = d.platform.entries.associate { (k, v) -> k.name to dto(v) },
        mine = d.mine.entries.associate { (k, v) -> k.name to dto(v) },
    )

    @GetMapping("/providers")
    fun providers(): List<ProviderStatusDto> = providers.list().map { ProviderStatusDto(it.provider, it.configured, it.baseUrl) }

    @GetMapping("/{profileId}")
    fun get(@PathVariable profileId: UUID): ModelProfileDto = dto(profiles.get(ModelProfileId(profileId)))

    @PutMapping("/{profileId}")
    fun update(@PathVariable profileId: UUID, @RequestBody body: ModelProfileInputDto): ModelProfileDto =
        dto(profiles.update(ModelProfileId(profileId), RestMappers.profileCommand(body)))

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable profileId: UUID) = profiles.delete(ModelProfileId(profileId))

    @PostMapping("/{profileId}/test")
    fun test(@PathVariable profileId: UUID): ModelTestResultDto = RestMappers.testResult(profiles.test(ModelProfileId(profileId)))
}

/** `/api/v1/task-suites` (openapi tag `task-suites`). */
@RestController
@RequestMapping("/api/v1/task-suites")
class TaskSuiteController(private val suites: ManageTaskSuites) {

    @GetMapping
    fun list(): List<TaskSuiteSummaryDto> = suites.list().map(RestMappers::suiteSummary)

    @PostMapping
    fun create(@RequestBody body: TaskSuiteInputDto): ResponseEntity<TaskSuiteDto> {
        val created = suites.create(RestMappers.suiteCommand(body))
        return ResponseEntity.created(URI.create("/api/v1/task-suites/${created.id}")).body(RestMappers.suite(created))
    }

    @GetMapping("/{suiteId}")
    fun get(@PathVariable suiteId: UUID): TaskSuiteDto = RestMappers.suite(suites.get(TaskSuiteId(suiteId)))

    @PutMapping("/{suiteId}")
    fun update(@PathVariable suiteId: UUID, @RequestBody body: TaskSuiteInputDto): TaskSuiteDto =
        RestMappers.suite(suites.update(TaskSuiteId(suiteId), RestMappers.suiteCommand(body)))

    @DeleteMapping("/{suiteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable suiteId: UUID) = suites.delete(TaskSuiteId(suiteId))

    @PostMapping("/{suiteId}/tasks")
    fun addTask(@PathVariable suiteId: UUID, @RequestBody body: EvalTaskInputDto): ResponseEntity<EvalTaskDto> {
        val task = suites.addTask(TaskSuiteId(suiteId), RestMappers.taskCommand(body))
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.task(task))
    }

    @PutMapping("/{suiteId}/tasks/{taskId}")
    fun updateTask(@PathVariable suiteId: UUID, @PathVariable taskId: UUID, @RequestBody body: EvalTaskInputDto): EvalTaskDto =
        RestMappers.task(suites.updateTask(TaskSuiteId(suiteId), EvalTaskId(taskId), RestMappers.taskCommand(body)))

    @DeleteMapping("/{suiteId}/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTask(@PathVariable suiteId: UUID, @PathVariable taskId: UUID) = suites.deleteTask(TaskSuiteId(suiteId), EvalTaskId(taskId))
}

/** `/api/v1/benchmarks` (openapi tag `benchmarks`). */
@RestController
@RequestMapping("/api/v1/benchmarks")
class BenchmarkController(private val benchmarks: ManageBenchmarks, private val gateway: LlmGateway, private val profiles: ManageModelProfiles) {

    private fun dto(d: ManageBenchmarks.BenchmarkDetails) = RestMappers.benchmark(d) { it.viaClaudeCodeLogin || gateway.isConfigured(it.ref) }

    @GetMapping
    fun list(): List<BenchmarkSummaryDto> = benchmarks.list().map(RestMappers::benchmarkSummary)

    /** ADR 0046: the shared fleet and the caller's personal runner, with their online runners. */
    /** ADR 0046: one tiny call per candidate and judge before a benchmark starts. */
    @PostMapping("/preflight")
    fun preflight(@RequestBody body: PreflightInputDto): List<PreflightResultDto> =
        profiles.preflight(body.modelProfileIds.map { ModelProfileId(it) }).map { (p, r) ->
            PreflightResultDto(p.id.value, p.displayName, r.ok, r.latencyMs, r.error)
        }

    @GetMapping("/execution-targets")
    fun executionTargets(): List<ExecutionTargetDto> = benchmarks.executionTargets().map { ExecutionTargetDto(it.target, it.pools, it.onlineRunners, it.localLogin, it.claudeLogin, it.harnesses) }

    @PostMapping
    fun create(@RequestBody body: BenchmarkInputDto): ResponseEntity<BenchmarkDto> {
        val created = benchmarks.create(RestMappers.benchmarkCommand(body))
        return ResponseEntity.created(URI.create("/api/v1/benchmarks/${created.benchmark.id}")).body(dto(created))
    }

    @GetMapping("/{benchmarkId}")
    fun get(@PathVariable benchmarkId: UUID): BenchmarkDto = dto(benchmarks.get(BenchmarkId(benchmarkId)))

    @DeleteMapping("/{benchmarkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable benchmarkId: UUID) = benchmarks.delete(BenchmarkId(benchmarkId))

    @PostMapping("/{benchmarkId}/start")
    fun start(@PathVariable benchmarkId: UUID): ResponseEntity<BenchmarkDto> =
        ResponseEntity.accepted().body(dto(benchmarks.start(BenchmarkId(benchmarkId))))

    @PostMapping("/{benchmarkId}/cancel")
    fun cancel(@PathVariable benchmarkId: UUID): BenchmarkDto = dto(benchmarks.cancel(BenchmarkId(benchmarkId)))

    @GetMapping("/{benchmarkId}/attempts")
    fun attempts(
        @PathVariable benchmarkId: UUID,
        @RequestParam(required = false) taskId: UUID?,
        @RequestParam(required = false) modelProfileId: UUID?,
    ): List<AttemptDto> =
        benchmarks.attempts(BenchmarkId(benchmarkId), taskId?.let { EvalTaskId(it) }, modelProfileId?.let { ModelProfileId(it) }).map(RestMappers::attempt)

    @GetMapping("/{benchmarkId}/attempts/{attemptId}")
    fun attempt(@PathVariable benchmarkId: UUID, @PathVariable attemptId: UUID): AttemptDetailDto =
        RestMappers.attemptDetail(benchmarks.attempt(BenchmarkId(benchmarkId), AttemptId(attemptId)))

    @GetMapping("/{benchmarkId}/leaderboard")
    fun leaderboard(@PathVariable benchmarkId: UUID): LeaderboardDto = RestMappers.leaderboard(benchmarks.leaderboard(BenchmarkId(benchmarkId)))
}
