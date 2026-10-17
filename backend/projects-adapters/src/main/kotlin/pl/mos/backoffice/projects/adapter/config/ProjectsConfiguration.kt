package pl.mos.backoffice.projects.adapter.config

import pl.mos.backoffice.shared.platform.PlatformFeatures
import pl.mos.backoffice.projects.application.port.out.LibrarySourceRepository
import pl.mos.backoffice.projects.application.port.out.LibraryWorkflowRepository
import pl.mos.backoffice.projects.application.service.WorkflowLibraryService
import pl.mos.backoffice.projects.application.service.LibrarySourceService
import pl.mos.backoffice.projects.application.service.PersonalIntakeService
import pl.mos.backoffice.projects.application.port.out.PersonalIntakeRepository
import pl.mos.backoffice.projects.application.service.tasks.SpecKitStepCheck
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer
import org.bsc.langgraph4j.state.AgentStateFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.mos.backoffice.projects.adapter.`in`.security.ProjectCiTokenVerifier
import pl.mos.backoffice.projects.adapter.out.langgraph.LangGraphWorkflowEngine
import pl.mos.backoffice.projects.adapter.out.langgraph.LeakSafeCheckpointSaver
import pl.mos.backoffice.projects.adapter.out.langgraph.SequencedPostgresSaver
import pl.mos.backoffice.projects.adapter.out.langgraph.TrackingDataSource
import pl.mos.backoffice.projects.adapter.out.langgraph.WorkflowState
import pl.mos.backoffice.projects.adapter.out.support.Sha256TokenHasher
import pl.mos.backoffice.projects.application.port.`in`.AssignApproval
import pl.mos.backoffice.projects.application.port.`in`.CompleteCiExecution
import pl.mos.backoffice.projects.application.port.`in`.RecordRunProgress
import pl.mos.backoffice.projects.application.port.`in`.VerifyCiToken
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.CiExecutionRepository
import pl.mos.backoffice.projects.application.port.out.ExpressionEvaluator
import pl.mos.backoffice.projects.application.port.out.IntegrationProbe
import pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher
import pl.mos.backoffice.projects.adapter.out.secrets.SecretsProperties
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.application.port.out.IssueTrackerFactory
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.service.ConnectionService
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.application.service.tasks.JiraStepCheck
import pl.mos.backoffice.projects.application.service.tasks.RepositoryStepCheck
import pl.mos.backoffice.projects.application.service.JiraIntakeService
import pl.mos.backoffice.projects.application.port.out.JiraIntakeRepository
import pl.mos.backoffice.projects.domain.service.JiraIntakePlanner
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import pl.mos.backoffice.projects.application.port.out.JsonCodec
import pl.mos.backoffice.projects.application.port.out.NodeExecutor
import pl.mos.backoffice.projects.application.port.out.ProjectCiTokenRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.RunLogRepository
import pl.mos.backoffice.projects.application.port.out.TaskExecutorStrategy
import pl.mos.backoffice.projects.application.port.out.TokenHasher
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.application.service.ApprovalAssignmentService
import pl.mos.backoffice.projects.application.service.ApprovalService
import pl.mos.backoffice.projects.application.service.CiExecutionCompletionService
import pl.mos.backoffice.projects.application.service.CiExecutionService
import pl.mos.backoffice.projects.application.service.CiTokenService
import pl.mos.backoffice.projects.application.service.DashboardQueryService
import pl.mos.backoffice.projects.application.service.IntegrationsService
import pl.mos.backoffice.projects.application.service.ModelResolver
import pl.mos.backoffice.projects.application.service.ProjectService
import pl.mos.backoffice.projects.application.service.RunProgressService
import pl.mos.backoffice.projects.application.service.RunService
import pl.mos.backoffice.projects.application.service.SignalService
import pl.mos.backoffice.projects.application.service.TemplateModelBuilder
import pl.mos.backoffice.projects.application.service.WaitTimeoutService
import pl.mos.backoffice.projects.application.service.WorkflowService
import pl.mos.backoffice.projects.application.service.nodes.DecisionNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.EndNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.HumanGateNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.NodeExecutors
import pl.mos.backoffice.projects.application.service.nodes.StartNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.TaskExecutors
import pl.mos.backoffice.projects.application.service.nodes.TaskNodeExecutor
import pl.mos.backoffice.projects.application.service.nodes.WaitForCiNodeExecutor
import pl.mos.backoffice.projects.application.service.tasks.FetchIssueTaskExecutor
import pl.mos.backoffice.projects.application.service.tasks.ImplementTaskExecutor
import pl.mos.backoffice.projects.application.service.tasks.LlmTaskExecutor
import pl.mos.backoffice.projects.application.service.tasks.NotifyTaskExecutor
import pl.mos.backoffice.projects.application.service.tasks.RunnerTaskSupport
import pl.mos.backoffice.projects.application.service.tasks.SpecKitTaskExecutor
import pl.mos.backoffice.projects.application.service.tasks.TransitionIssueTaskExecutor
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.service.CiSnippetRenderer
import pl.mos.backoffice.projects.domain.service.ReviewVerdictPolicy
import pl.mos.backoffice.projects.domain.service.TemplateRenderer
import pl.mos.backoffice.projects.domain.service.WorkflowValidator
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CiTokenVerifier
import pl.mos.backoffice.shared.security.PermissionCatalogContribution
import pl.mos.backoffice.shared.security.ProjectAccessDirectory
import pl.mos.backoffice.shared.security.ProjectAccessProvisioner
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.runner.AgentRunner
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.usage.UsageLedger
import javax.sql.DataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import pl.mos.backoffice.shared.platform.ClusterSignals
import pl.mos.backoffice.shared.platform.WorkQueue

/**
 * Wires the Projects & Workflows use cases (plain Kotlin classes from `projects-core`) from the port
 * implementations in this module and the shared beans provided by other modules
 * (`LlmGateway`, `UsageLedger`, `ModelCatalog`, `TaskCatalog`, `AgentRunner`, `Clock`, `EventPublisher`, `DataSource`)
 * and by the identity context (`AuthorizationGate`, `AuditPort`, `ProjectAccessProvisioner`). Contributes the
 * `projects` permission catalogue and the `CiTokenVerifier` the identity bearer-token filter uses.
 */
@Configuration
@EnableConfigurationProperties(IntegrationsProperties::class, PipelinesProperties::class, RunnerSettings::class, PublicUrlProperties::class, SecretsProperties::class)
class ProjectsConfiguration {

    // ---- domain services

    @Bean fun workflowValidator() = WorkflowValidator()

    @Bean fun templateRenderer() = TemplateRenderer()

    @Bean fun reviewVerdictPolicy() = ReviewVerdictPolicy()

    @Bean fun ciSnippetRenderer() = CiSnippetRenderer()

    @Bean fun templateModelBuilder() = TemplateModelBuilder()

    @Bean fun modelResolver(catalog: ModelCatalog) = ModelResolver(catalog)

    @Bean fun tokenHasher(): TokenHasher = Sha256TokenHasher()

    // ---- security contributions

    @Bean fun projectsPermissionCatalog(): PermissionCatalogContribution = PermissionCatalogContribution { ProjectsPermissions.definitions }

    @Bean fun projectCiTokenVerifier(tokens: VerifyCiToken): CiTokenVerifier = ProjectCiTokenVerifier(tokens)

    // ---- use cases

    @Bean
    fun projectService(
        projects: ProjectRepository, workflows: WorkflowDefinitionRepository, runs: WorkflowRunRepository, approvals: ApprovalRequestRepository,
        ciExecutions: CiExecutionRepository, ciTokens: ProjectCiTokenRepository, usageLedger: UsageLedger, snippets: CiSnippetRenderer,
        publicUrl: PublicUrlProperties, gate: AuthorizationGate, provisioner: ProjectAccessProvisioner, audit: AuditPort, clock: Clock,
        features: ObjectProvider<PlatformFeatures>,
    ) = ProjectService(
        projects, workflows, runs, approvals, ciExecutions, ciTokens, usageLedger, snippets, publicUrl.publicUrl, gate, provisioner, audit, clock,
        features.getIfAvailable { PlatformFeatures.DEFAULTS },
    )

    @Bean
    fun ciTokenService(tokens: ProjectCiTokenRepository, projects: ProjectRepository, hasher: TokenHasher, gate: AuthorizationGate, audit: AuditPort, clock: Clock) =
        CiTokenService(tokens, projects, hasher, gate, audit, clock)

    @Bean
    fun workflowService(
        workflows: WorkflowDefinitionRepository, projects: ProjectRepository, runs: WorkflowRunRepository, validator: WorkflowValidator,
        taskCatalog: TaskCatalog, expressions: ExpressionEvaluator, gate: AuthorizationGate, audit: AuditPort, clock: Clock,
        jira: JiraConnectionAccess, specKit: SpecKitStepCheck, library: LibraryWorkflowRepository, librarySources: LibrarySourceRepository,
    ) = WorkflowService(workflows, projects, runs, validator, taskCatalog, expressions, gate, audit, clock, jira, specKit, library, librarySources)

    /** Sources of the library (ADR 0043). */
    @Bean
    fun librarySourceService(sources: LibrarySourceRepository, gate: AuthorizationGate, audit: AuditPort, clock: Clock) =
        LibrarySourceService(sources, gate, audit, clock)

    /** Workflow library (ADR 0037): publish, adopt, update and detach linked copies. */
    @Bean
    fun workflowLibraryService(
        library: LibraryWorkflowRepository, workflows: WorkflowDefinitionRepository, projects: ProjectRepository, workflowService: WorkflowService,
        taskCatalog: TaskCatalog, gate: AuthorizationGate, audit: AuditPort, clock: Clock,
    ) = WorkflowLibraryService(library, workflows, projects, workflowService, taskCatalog, gate, audit, clock)

    /** Spec Kit gating of workflows (ADR 0036): project setting and platform switch. */
    @Bean
    fun specKitStepCheck(taskCatalog: TaskCatalog, features: ObjectProvider<PlatformFeatures>) =
        SpecKitStepCheck(taskCatalog, features.getIfAvailable { PlatformFeatures.DEFAULTS })

    /** Jira steps of a workflow: connection per step and write-approval settings (ADR 0030, 0033). */
    @Bean fun jiraStepCheck(taskCatalog: TaskCatalog, jira: JiraConnectionAccess) = JiraStepCheck(taskCatalog, jira)

    /** Sources of the runner steps of a workflow: project repositories (ADR 0034) and library sources (ADR 0043). */
    @Bean fun repositoryStepCheck(taskCatalog: TaskCatalog, librarySources: LibrarySourceRepository) = RepositoryStepCheck(taskCatalog, librarySources)

    @Bean
    fun runService(
        runs: WorkflowRunRepository, workflows: WorkflowDefinitionRepository, projects: ProjectRepository, logs: RunLogRepository,
        engine: WorkflowEngine, progress: RecordRunProgress, validator: WorkflowValidator, events: EventPublisher,
        gate: AuthorizationGate, audit: AuditPort, clock: Clock, jiraSteps: JiraStepCheck, repositorySteps: RepositoryStepCheck,
        specKitSteps: SpecKitStepCheck,
    ) = RunService(runs, workflows, projects, logs, engine, progress, validator, events, gate, audit, clock, jiraSteps, repositorySteps, specKitSteps)

    // ---- Jira intake watcher (ADR 0033)

    @Bean fun jiraIntakePlanner() = JiraIntakePlanner()

    @Bean
    fun jiraIntakeService(
        intakes: JiraIntakeRepository, projects: ProjectRepository, workflows: WorkflowDefinitionRepository, runs: RunService,
        jira: JiraConnectionAccess, planner: JiraIntakePlanner, queue: WorkQueue, gate: AuthorizationGate, audit: AuditPort, clock: Clock,
    ) = JiraIntakeService(intakes, projects, workflows, runs, jira, planner, queue, gate, audit, clock)

    /** Personal work (ADR 0038): personal intake rules and run proposals. */
    @Bean
    fun personalIntakeService(
        intake: PersonalIntakeRepository, connections: ConnectionRepository, workflows: WorkflowDefinitionRepository, projects: ProjectRepository,
        runs: RunService, jira: JiraConnectionAccess, queue: WorkQueue, events: EventPublisher, gate: AuthorizationGate, audit: AuditPort, clock: Clock,
    ) = PersonalIntakeService(intake, connections, workflows, projects, runs, jira, queue, events, gate, audit, clock)

    @Bean
    fun signalService(runs: WorkflowRunRepository, engine: WorkflowEngine, events: EventPublisher, gate: AuthorizationGate, audit: AuditPort, clock: Clock) =
        SignalService(runs, engine, events, gate, audit, clock)

    @Bean
    fun runProgressService(
        runs: WorkflowRunRepository, approvals: ApprovalRequestRepository, logs: RunLogRepository, ciExecutions: CompleteCiExecution,
        events: EventPublisher, clock: Clock,
    ): RecordRunProgress = RunProgressService(runs, approvals, logs, ciExecutions, events, clock)

    @Bean
    fun approvalService(
        approvals: ApprovalRequestRepository, runs: WorkflowRunRepository, engine: WorkflowEngine, events: EventPublisher,
        gate: AuthorizationGate, audit: AuditPort, clock: Clock, connections: ConnectionRepository,
    ) = ApprovalService(approvals, runs, engine, events, gate, audit, clock, connections)

    /** Run-time assignment of open approvals (ADR 0028); assignees are resolved by the identity context. */
    @Bean
    fun approvalAssignmentService(
        approvals: ApprovalRequestRepository, directory: ProjectAccessDirectory, events: EventPublisher, gate: AuthorizationGate, audit: AuditPort, clock: Clock,
    ): AssignApproval = ApprovalAssignmentService(approvals, directory, events, gate, audit, clock)

    @Bean
    fun ciExecutionCompletionService(executions: CiExecutionRepository, events: EventPublisher, clock: Clock): CompleteCiExecution =
        CiExecutionCompletionService(executions, events, clock)

    @Bean
    fun ciExecutionService(
        executions: CiExecutionRepository, projects: ProjectRepository, runService: RunService, events: EventPublisher,
        gate: AuthorizationGate, audit: AuditPort, clock: Clock,
    ) = CiExecutionService(executions, projects, runService, events, gate, audit, clock)

    @Bean
    fun waitTimeoutService(runs: WorkflowRunRepository, approvals: ApprovalRequestRepository, engine: WorkflowEngine, clock: Clock) =
        WaitTimeoutService(runs, approvals, engine, clock)

    @Bean
    fun dashboardQueryService(runs: WorkflowRunRepository, approvals: ApprovalRequestRepository, ciExecutions: CiExecutionRepository, gate: AuthorizationGate) =
        DashboardQueryService(runs, approvals, ciExecutions, gate)

    @Bean
    fun integrationsService(
        probes: List<IntegrationProbe>, llmGateway: LlmGateway, publicUrl: PublicUrlProperties, build: ObjectProvider<BuildProperties>, gate: AuthorizationGate,
    ) = IntegrationsService(probes, llmGateway, publicUrl.publicUrl, build.ifAvailable?.version ?: "dev", gate)

    // ---- node executors

    @Bean fun startNodeExecutor() = StartNodeExecutor()

    @Bean fun endNodeExecutor() = EndNodeExecutor()

    @Bean fun decisionNodeExecutor(evaluator: ExpressionEvaluator) = DecisionNodeExecutor(evaluator)

    @Bean fun humanGateNodeExecutor() = HumanGateNodeExecutor()

    @Bean fun waitForCiNodeExecutor() = WaitForCiNodeExecutor()

    @Bean fun taskExecutors(strategies: List<TaskExecutorStrategy>) = TaskExecutors(strategies)

    @Bean fun taskNodeExecutor(catalog: TaskCatalog, strategies: TaskExecutors) = TaskNodeExecutor(catalog, strategies)

    @Bean fun nodeExecutors(executors: List<NodeExecutor>) = NodeExecutors(executors)

    // ---- task executor strategies

    @Bean
    fun llmTaskExecutor(
        llmGateway: LlmGateway, modelResolver: ModelResolver, renderer: TemplateRenderer, templateModel: TemplateModelBuilder,
        verdictPolicy: ReviewVerdictPolicy, json: JsonCodec, audit: AuditPort,
    ) = LlmTaskExecutor(llmGateway, modelResolver, renderer, templateModel, verdictPolicy, json, audit)

    @Bean
    fun runnerTaskSupport(
        runner: AgentRunner, modelResolver: ModelResolver, renderer: TemplateRenderer, templateModel: TemplateModelBuilder, settings: RunnerSettings,
        features: ObjectProvider<PlatformFeatures>, librarySources: LibrarySourceRepository,
    ) = RunnerTaskSupport(runner, modelResolver, renderer, templateModel, settings.defaultTimeout, features.getIfAvailable { PlatformFeatures.DEFAULTS }, librarySources)

    @Bean fun specKitTaskExecutor(support: RunnerTaskSupport) = SpecKitTaskExecutor(support)

    @Bean fun implementTaskExecutor(support: RunnerTaskSupport) = ImplementTaskExecutor(support)

    @Bean fun fetchIssueTaskExecutor(jira: JiraConnectionAccess, json: JsonCodec) = FetchIssueTaskExecutor(jira, json)

    @Bean
    fun notifyTaskExecutor(jira: JiraConnectionAccess, renderer: TemplateRenderer, templateModel: TemplateModelBuilder, audit: AuditPort) =
        NotifyTaskExecutor(jira, renderer, templateModel, audit)

    @Bean fun transitionIssueTaskExecutor(jira: JiraConnectionAccess, audit: AuditPort) = TransitionIssueTaskExecutor(jira, audit)

    // ---- connections (ADR 0030)

    /** Envelope encryption of connection secrets; the only bean that can open them (used by the Jira adapter). */
    @Bean fun secretCipher(properties: SecretsProperties) = AesGcmSecretCipher(properties)

    @Bean fun connectionSelector() = ConnectionSelector()

    @Bean fun connectionVerificationPolicy() = ConnectionVerificationPolicy()

    @Bean
    fun jiraConnectionAccess(connections: ConnectionRepository, legacy: LegacyConnectionSource, selector: ConnectionSelector, trackers: IssueTrackerFactory) =
        JiraConnectionAccess(connections, legacy, selector, trackers)

    @Bean
    fun connectionService(
        connections: ConnectionRepository, projects: ProjectRepository, legacy: LegacyConnectionSource, cipher: AesGcmSecretCipher,
        trackers: IssueTrackerFactory, selector: ConnectionSelector, policy: ConnectionVerificationPolicy, gate: AuthorizationGate,
        audit: AuditPort, clock: Clock, events: EventPublisher,
    ) = ConnectionService(connections, projects, legacy, cipher, trackers, selector, policy, gate, audit, clock, events)

    // ---- engine

    /**
     * LangGraph4j checkpoint store in the application database (`lg4j*` tables, V105). Checkpoints are ordered
     * by insertion sequence, not by the database clock ([SequencedPostgresSaver]).
     */
    @Bean
    fun checkpointSaver(dataSource: DataSource): BaseCheckpointSaver {
        val tracking = TrackingDataSource(dataSource)
        val saver = tracking.scoped {
            SequencedPostgresSaver(
                PostgresSaverV2.builder()
                    .datasource(tracking)
                    .stateSerializer(ObjectStreamStateSerializer(AgentStateFactory { WorkflowState(it) }))
                    .createTables(true),
            )
        }
        return LeakSafeCheckpointSaver(saver, tracking)
    }

    @Bean
    fun workflowEngine(
        runs: WorkflowRunRepository, projects: ProjectRepository, progress: RecordRunProgress, executors: NodeExecutors,
        renderer: TemplateRenderer, templateModel: TemplateModelBuilder, checkpointSaver: BaseCheckpointSaver,
        gate: AuthorizationGate, queue: WorkQueue, runner: AgentRunner, signals: ObjectProvider<ClusterSignals>,
        meters: ObjectProvider<MeterRegistry>,
    ): LangGraphWorkflowEngine = LangGraphWorkflowEngine(
        runs, projects, progress, executors, renderer, templateModel, checkpointSaver, gate, queue, runner, signals.getIfAvailable { ClusterSignals.local() },
    ).also { engine ->
        // backoffice_runs_active{holder}: runs this replica is advancing right now
        meters.ifAvailable?.let { registry ->
            Gauge.builder("backoffice.runs.active") { engine.activeRuns().toDouble() }
                .description("Workflow runs being advanced by this replica").tag("holder", HOLDER).register(registry)
        }
    }

    private companion object {
        val HOLDER: String = System.getenv("HOSTNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("local")
    }
}
