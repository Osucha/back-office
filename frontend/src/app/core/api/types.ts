/**
 * Convenience aliases over the generated OpenAPI schema (`schema.d.ts`).
 * Regenerate the schema with `npm run api:types`; never edit schema.d.ts by hand.
 */
import type { components, operations } from './schema';

type S = components['schemas'];

export type Problem = S['Problem'];
export type Money = S['Money'];
export type TokenUsage = S['TokenUsage'];
export type Provider = S['Provider'];
export type Reasoning = S['Reasoning'];
export type ModelOptions = S['ModelOptions'];
export type ModelRef = S['ModelRef'];

export type Dashboard = S['Dashboard'];

// ---- projects / CI configuration
export type ProjectInput = S['ProjectInput'];
/** ADR 0036: Spec Kit of a project (setting, platform switch, missing workflows). */
export type SpecKitStatus = S['SpecKitStatus'];
export type Project = S['Project'];
/** One git repository of a project (ADR 0034); a project has zero or more. */
export type ProjectRepository = S['ProjectRepository'];
export type RepositoryPurpose = S['RepositoryPurpose'];
export const REPOSITORY_PURPOSES: RepositoryPurpose[] = ['SOURCE', 'DOCS', 'STANDARDS', 'TEMPLATES', 'OTHER'];
/** Git access level of a runner job / of one job repository (ADR 0031). */
export type GitAccess = S['GitAccess'];
/** What a runner step did in one repository (ADR 0034). */
export type RepositoryResult = S['RepositoryResult'];
export type CiEntrypoint = S['CiEntrypoint'];
export type CiEntrypointMode = CiEntrypoint['mode'];
export type CiToken = S['CiToken'];
export type CiTokenCreated = S['CiTokenCreated'];
export type CiSnippet = NonNullable<
  operations['getCiSnippet']['responses']['200']['content']['application/json']
>;

// ---- workflows
export type NodeKind = S['NodeKind'];
export type Point = S['Point'];
export type TaskOverrides = S['TaskOverrides'];
export type DecisionBranch = S['DecisionBranch'];
export type WorkflowNode = S['WorkflowNode'];
export type WorkflowNodeTask = NonNullable<WorkflowNode['task']>;
export type WorkflowNodeDecision = NonNullable<WorkflowNode['decision']>;
export type WorkflowNodeGate = NonNullable<WorkflowNode['gate']>;
export type WorkflowNodeCi = NonNullable<WorkflowNode['ci']>;
export type WorkflowNodeEnd = NonNullable<WorkflowNode['end']>;
export type WorkflowNodeNote = NonNullable<WorkflowNode['note']>;
export type WorkflowEdge = S['WorkflowEdge'];
export type Trigger = S['Trigger'];
export type TriggerType = Trigger['type'];
export type WorkflowDefinitionInput = S['WorkflowDefinitionInput'];
export type WorkflowCanvas = NonNullable<WorkflowDefinitionInput['canvas']>;
export type ProjectWorkspace = S['ProjectWorkspace'];
export type LlmProvider = S['LlmProvider'];
export type LlmProviderInput = S['LlmProviderInput'];
export type ProviderModels = S['ProviderModels'];
export type ProviderModelInfo = S['ProviderModelInfo'];
export type ProviderCatalogSection = S['ProviderCatalogSection'];
export type ProjectWorkspaceInput = S['ProjectWorkspaceInput'];
export type WorkspaceSource = NonNullable<ProjectWorkspaceInput['sources']>[number];
export type WorkflowDefinition = S['WorkflowDefinition'];
export type ValidationResult = S['ValidationResult'];
export type ValidationIssue = S['ValidationIssue'];
export type ControlNodeDescriptor = S['ControlNodeDescriptor'];
export type PaletteTask = S['PaletteTask'];
export type WorkflowPalette = S['WorkflowPalette'];
export type StateField = NonNullable<WorkflowPalette['stateFields']>[number];
export type ExpressionValidation = NonNullable<
  operations['validateExpression']['responses']['200']['content']['application/json']
>;

// ---- runs
export type StartRunRequest = S['StartRunRequest'];
export type RunStatus = S['RunStatus'];
export type RunOutcome = S['RunOutcome'];
export type NodeExecutionStatus = S['NodeExecutionStatus'];
export type NodeExecution = S['NodeExecution'];
export type CiContext = S['CiContext'];
export type WorkflowRunSummary = S['WorkflowRunSummary'];
export type WaitingOn = NonNullable<WorkflowRunSummary['waitingOn']>;
export type WorkflowRun = S['WorkflowRun'];
export type WorkflowRunPage = S['WorkflowRunPage'];
export type ManualSignal = S['ManualSignal'];
export type ArtifactKind = S['ArtifactKind'];
export type ArtifactSummary = S['ArtifactSummary'];
export type Artifact = S['Artifact'];
export type LogLine = S['LogLine'];

// ---- approvals
export type ApprovalRequest = S['ApprovalRequest'];
/** The exact external write of a write confirmation (ADR 0033). */
export type WritePreview = S['WritePreview'];
/** ADR 0035: who the caller could approve a Jira write as. */
export type WriteIdentity = S['WriteIdentity'];
/** A least-privilege connection (ADR 0030); the feature has more aliases (features/connections). */
export type Connection = S['Connection'];
export type JiraIntake = S['JiraIntake'];
export type JiraIntakeInput = S['JiraIntakeInput'];
export type ApprovalStatus = NonNullable<ApprovalRequest['status']>;
export type Decision = S['Decision'];
export type Assignee = S['Assignee'];
export type ApprovalAssignment = S['ApprovalAssignment'];
export type AssignApproval = S['AssignApproval'];
export type AssigneeCandidate = S['AssigneeCandidate'];

// ---- CI executions
export type CiExecutionRequest = S['CiExecutionRequest'];
export type Finding = S['Finding'];
export type FindingSeverity = NonNullable<Finding['severity']>;
export type ReviewReport = S['ReviewReport'];
export type ReviewVerdict = NonNullable<NonNullable<ReviewReport>['verdict']>;
export type CiExecutionSummary = S['CiExecutionSummary'];
export type CiExecutionStatus = NonNullable<CiExecutionSummary['status']>;
export type GateResult = NonNullable<CiExecutionSummary['gateResult']>;
export type CiExecution = S['CiExecution'];
export type CiExecutionPage = S['CiExecutionPage'];
export type CiSignalRequest = S['CiSignalRequest'];
export type CiSignalResult = S['CiSignalResult'];

// ---- task library
export type TaskExecutor = S['TaskExecutor'];
export type Scope = S['Scope'];
export type ScopeType = Scope['type'];
export type Slot = S['Slot'];
export type SlotKind = Slot['kind'];
export type TaskInterfaceInput = S['TaskInterfaceInput'];
export type TaskInterface = S['TaskInterface'];
export type TaskPrompt = S['TaskPrompt'];
export type PromptMode = TaskPrompt['mode'];
export type TaskTemplateInput = S['TaskTemplateInput'];
export type TaskTemplate = S['TaskTemplate'];
export type ResolvedTask = S['ResolvedTask'];
export type SkillInput = S['SkillInput'];
export type Skill = S['Skill'];

// ---- model evaluation
export type Pricing = S['Pricing'];
export type ModelProfileInput = S['ModelProfileInput'];
export type ModelProfile = S['ModelProfile'];
export type Capability = NonNullable<ModelProfileInput['capabilities']>[number];
export type ModelTestResult = S['ModelTestResult'];
export type ProviderStatus = S['ProviderStatus'];

export type TaskCategory = S['TaskCategory'];
export type Difficulty = S['Difficulty'];
export type EvalTaskInput = S['EvalTaskInput'];
export type EvalTask = S['EvalTask'];
export type TaskKind = EvalTaskInput['kind'];
export type TaskSuiteInput = S['TaskSuiteInput'];
export type TaskSuiteSummary = S['TaskSuiteSummary'];
export type TaskSuite = S['TaskSuite'];

export type BenchmarkInput = S['BenchmarkInput'];
export type BenchmarkStatus = S['BenchmarkStatus'];
export type BenchmarkSummary = S['BenchmarkSummary'];
export type Benchmark = S['Benchmark'];
export type AttemptStatus = S['AttemptStatus'];
export type Attempt = S['Attempt'];
export type AttemptDetail = S['AttemptDetail'];
export type LeaderboardRow = S['LeaderboardRow'];
export type Leaderboard = S['Leaderboard'];

// ---- usage / settings / events
export type UsageSummary = S['UsageSummary'];
export type UsageGroup = NonNullable<UsageSummary['groups']>[number];
export type UsageEntry = S['UsageEntry'];
export type UsageEntryPage = S['UsageEntryPage'];
export type UsageGroupBy = NonNullable<
  operations['getUsageSummary']['parameters']['query']
>['groupBy'];

export type IntegrationsStatus = S['IntegrationsStatus'];
export type IntegrationTestResult = S['IntegrationTestResult'];
export type IntegrationName = operations['testIntegration']['parameters']['path']['name'];

export type DomainEventEnvelope = S['DomainEventEnvelope'];
export type DomainEventType = DomainEventEnvelope['type'];

// ---- identity: current user, auth, tokens
export type ActorRef = NonNullable<S['ActorRef']>;
export type UserRef = S['UserRef'];
export type Me = S['Me'];
export type MeProject = NonNullable<Me['projects']>[number];
export type UserPreferences = S['UserPreferences'];
export type Theme = NonNullable<UserPreferences['theme']>;
export type AuthConfig = S['AuthConfig'];
export type SessionInfo = S['SessionInfo'];
export type SessionSummary = S['SessionSummary'];
export type PersonalToken = S['PersonalToken'];
export type PersonalTokenInput = S['PersonalTokenInput'];
export type PersonalTokenCreated = S['PersonalTokenCreated'];

// ---- identity: local accounts (ADR 0021)
export type AuthMode = S['AuthMode'];
export type AuthMethods = NonNullable<AuthConfig['methods']>;
export type PasswordPolicy = S['PasswordPolicy'];
export type LocalLoginRequest = S['LocalLoginRequest'];
export type MfaLoginRequest = S['MfaLoginRequest'];
export type LoginStepResult = S['LoginStepResult'];
export type LoginStepStatus = LoginStepResult['status'];
export type PasswordChangeRequest = S['PasswordChangeRequest'];
export type PasswordResetInfo = S['PasswordResetInfo'];
export type PasswordResetLink = S['PasswordResetLink'];
export type MySecurity = S['MySecurity'];
export type RecentLogin = NonNullable<MySecurity['recentLogins']>[number];
export type TotpEnrolment = S['TotpEnrolment'];
export type RecoveryCodes = S['RecoveryCodes'];

// ---- identity: administration
export type UserStatus = S['UserStatus'];
export type UserDetail = S['UserDetail'];
export type UserPage = S['UserPage'];
export type UserInvite = S['UserInvite'];
export type UserUpdate = S['UserUpdate'];
export type UserProjectMembership = NonNullable<UserDetail['projectMemberships']>[number];
export type Group = S['Group'];
export type GroupInput = S['GroupInput'];
export type GroupRef = { id?: string; name?: string };
export type PermissionScope = S['PermissionScope'];
export type PermissionDefinition = S['PermissionDefinition'];
export type Role = S['Role'];
export type RoleInput = S['RoleInput'];
export type GrantLevel = S['GrantLevel'];
export type Grant = S['Grant'];
export type GrantEffect = Grant['effect'];
export type GrantSet = S['GrantSet'];
export type EffectivePermissions = S['EffectivePermissions'];
export type EffectivePermissionRow = NonNullable<EffectivePermissions['rows']>[number];
export type LevelEffect = NonNullable<EffectivePermissionRow['system']>;
export type PlatformSettings = S['PlatformSettings'];
export type ProjectMember = S['ProjectMember'];
export type ProjectMemberInput = S['ProjectMemberInput'];
export type PrincipalType = ProjectMemberInput['principalType'];
export type AuditEntry = S['AuditEntry'];
export type AuditPage = S['AuditPage'];
export type AuditResult = NonNullable<AuditEntry['result']>;
export type AuditQuery = NonNullable<operations['searchAuditLog']['parameters']['query']>;
export type UserQuery = NonNullable<operations['listUsers']['parameters']['query']>;

// ---- runner fleet and sandbox policy (ADR 0024)
export type SandboxIsolation = S['SandboxIsolation'];
export type SandboxPolicy = S['SandboxPolicy'];
export type SandboxResources = NonNullable<SandboxPolicy['resources']>;
export type SandboxTools = NonNullable<SandboxPolicy['tools']>;
export type SandboxEnvironment = NonNullable<SandboxPolicy['environment']>;
export type SandboxCache = NonNullable<SandboxEnvironment['caches']>[number];
export type SandboxPolicyView = S['SandboxPolicyView'];
export type RunnerPoolInput = S['RunnerPoolInput'];
export type RunnerPool = S['RunnerPool'];
export type Runner = S['Runner'];
export type RunnerBackend = NonNullable<Runner['backends']>[number];
export type LlmCredentials = S['LlmCredentials'];
export type PersonalRunner = S['PersonalRunner'];
export type PersonalRegistrationToken = S['PersonalRegistrationToken'];
export type RegistrationToken = NonNullable<
  operations['createRegistrationToken']['responses']['201']['content']['application/json']
>;
export type AgentJobStatus = S['AgentJobStatus'];
export type AgentJob = S['AgentJob'];
export type AgentJobPage = S['AgentJobPage'];
export type AgentJobQuery = NonNullable<operations['listAgentJobs']['parameters']['query']>;

// ---- operations
export type WorkItem = S['WorkItem'];
export type WorkItemStatus = NonNullable<WorkItem['status']>;
export type WorkItemPage = S['WorkItemPage'];
export type OperationsOverview = S['OperationsOverview'];
export type Replica = NonNullable<OperationsOverview['replicas']>[number];

// ---- governance: budgets, insights, provenance (ADR 0025)
export type BudgetInput = S['BudgetInput'];
export type BudgetStatus = S['BudgetStatus'];
export type BudgetState = NonNullable<BudgetStatus['state']>;
export type DistributionStat = S['DistributionStat'];
export type DeliveryInsights = S['DeliveryInsights'];
export type TemplateSuccess = NonNullable<DeliveryInsights['agentSuccessByTemplate']>[number];
export type ModelSuccess = NonNullable<DeliveryInsights['agentSuccessByModel']>[number];
export type GateDecisionCount = NonNullable<DeliveryInsights['gateDecisions']>[number];
export type WeeklyInsight = NonNullable<DeliveryInsights['weekly']>[number];
export type Bottleneck = NonNullable<DeliveryInsights['bottleneck']>;
// ---- workflow library and workflow metrics (ADR 0037)
export type LibraryLink = S['LibraryLink'];
export type LibraryWorkflowSummary = S['LibraryWorkflowSummary'];
export type LibrarySource = S['LibrarySource'];
export type LibrarySourceInput = S['LibrarySourceInput'];
export type LibraryWorkflow = S['LibraryWorkflow'];
export type LibraryWorkflowVersionSummary = S['LibraryWorkflowVersionSummary'];
export type LibraryWorkflowVersionDetail = S['LibraryWorkflowVersionDetail'];
export type LibraryWorkflowAdoption = S['LibraryWorkflowAdoption'];
export type PublishWorkflowRequest = S['PublishWorkflowRequest'];
export type LibraryWorkflowUpdate = S['LibraryWorkflowUpdate'];
export type AdoptLibraryWorkflowRequest = S['AdoptLibraryWorkflowRequest'];
export type LibraryUpdateRequest = S['LibraryUpdateRequest'];
export type WorkflowMetrics = S['WorkflowMetrics'];
export type WorkflowNodeMetrics = S['WorkflowNodeMetrics'];
export type WorkflowInsights = S['WorkflowInsights'];
export type WorkflowVersionMetrics = NonNullable<WorkflowInsights['byVersion']>[number];
export type WorkflowProjectMetrics = NonNullable<WorkflowInsights['byProject']>[number];
export type LibraryWorkflowMetricsRow = S['LibraryWorkflowMetricsRow'];
export type AttestationSummary = S['AttestationSummary'];
export type DsseEnvelope = S['DsseEnvelope'];
export type AttestationVerification = NonNullable<
  operations['verifyAttestation']['responses']['200']['content']['application/json']
>;

// ---- gates
export type GateApprovers = NonNullable<S['GateApprovers']>;
export type ChecklistItem = S['ChecklistItem'];

/**
 * RFC 9457 problem with the extension members the backend adds on 403 (`permission`, `projectId`).
 */
export type AccessProblem = Problem & { permission?: string; projectId?: string };

/** Generic page shape shared by every paged endpoint. */
export interface Page<T> {
  content?: T[];
  page?: number;
  size?: number;
  totalElements?: number;
}

export const RUN_STATUSES: RunStatus[] = [
  'PENDING',
  'RUNNING',
  'WAITING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
];
export const NODE_KINDS: NodeKind[] = [
  'START',
  'END',
  'TASK',
  'DECISION',
  'HUMAN_GATE',
  'WAIT_FOR_CI',
  'NOTE',
];
export const TASK_EXECUTORS: TaskExecutor[] = [
  'LLM',
  'SPEC_KIT',
  'IMPLEMENT',
  'FETCH_ISSUE',
  'NOTIFY',
  'TRANSITION_ISSUE',
];
export const SLOT_KINDS: SlotKind[] = ['INPUT', 'STATE', 'ARTIFACT'];
export const TRIGGER_TYPES: TriggerType[] = ['MANUAL', 'JIRA_TRANSITION', 'API', 'CI'];
export const PROVIDERS: Provider[] = [
  'ANTHROPIC',
  'OPENAI',
  'GOOGLE',
  'MISTRAL',
  'OLLAMA',
  'OPENAI_COMPATIBLE',
];
export const REASONING_LEVELS: Reasoning[] = ['NONE', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'];
export const CAPABILITIES: Capability[] = [
  'CODING',
  'REASONING',
  'LONG_CONTEXT',
  'TOOLS',
  'VISION',
  'CHEAP',
];
export const TASK_CATEGORIES: TaskCategory[] = [
  'CODING',
  'ANALYSIS',
  'SPECIFICATION',
  'REVIEW',
  'ARCHITECTURE',
];
export const DIFFICULTIES: Difficulty[] = ['EASY', 'MEDIUM', 'HARD', 'EXPERT'];
export const USAGE_GROUP_BYS: UsageGroupBy[] = [
  'MODEL',
  'PROJECT',
  'BENCHMARK',
  'DAY',
  'CONTEXT_KIND',
];
export const FINDING_SEVERITIES: FindingSeverity[] = ['BLOCKER', 'MAJOR', 'MINOR', 'NIT'];
export const USER_STATUSES: UserStatus[] = ['INVITED', 'ACTIVE', 'DEACTIVATED'];
export const AUDIT_RESULTS: AuditResult[] = ['SUCCESS', 'DENIED', 'FAILURE'];
export const GRANT_LEVELS: GrantLevel[] = ['SYSTEM', 'GROUP', 'USER'];
export const AGENT_JOB_STATUSES: AgentJobStatus[] = [
  'QUEUED',
  'LEASED',
  'STARTING',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'TIMED_OUT',
  'CANCELLED',
  'LOST',
];
export const SANDBOX_ISOLATIONS: SandboxIsolation[] = ['STANDARD', 'HIGH', 'DEV'];
export const SANDBOX_CACHES: SandboxCache[] = [
  'gradle',
  'maven',
  'npm',
  'pnpm',
  'pip',
  'cargo',
  'go',
];
export const WORK_ITEM_STATUSES: WorkItemStatus[] = ['READY', 'LEASED', 'DONE', 'DEAD'];
/** Built-in project roles (fallback when `/admin/roles?scope=PROJECT` is not readable). */
export const BUILT_IN_PROJECT_ROLES = ['owner', 'maintainer', 'developer', 'approver', 'viewer'];

// ---- personal work (ADR 0038)
export type IntakeRuleInput = S['IntakeRuleInput'];
export type IntakeRule = S['IntakeRule'];
export type IntakeProposal = S['IntakeProposal'];
export type IntakeProposalState = IntakeProposal['state'];
