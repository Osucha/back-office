# 02 — Domain model

Identifiers are UUIDs wrapped in typed value classes (`ProjectId`, `WorkflowRunId`, ...). Money is
`Money(amount: BigDecimal, currency = USD)`. Timestamps are `Instant` (UTC).

## Shared kernel

| Type | Kind | Fields / notes |
|---|---|---|
| `ModelRef` | value object | `provider: Provider`, `modelId: String`, `options: ModelOptions` (temperature?, maxOutputTokens?, reasoning: `NONE/LOW/MEDIUM/HIGH/XHIGH/MAX`, extra: Map) |
| `Provider` | enum | `ANTHROPIC, OPENAI, GOOGLE, MISTRAL, OLLAMA, OPENAI_COMPATIBLE` |
| `LlmRequest` / `LlmResult` / `TokenUsage` | value objects | one chat call and its outcome; `responseSchema` asks for structured output |
| `UsageRecord`, `UsageContext`, `Pricing` | ledger | cost accounting; `UsageKind = PIPELINE_STEP, CODE_REVIEW, BENCHMARK_ATTEMPT, MODEL_TEST` |
| `LlmGateway`, `UsageLedger`, `ModelCatalog`, `AgentRunner`, `TaskCatalog`, `Clock`, `EventPublisher` | ports | see 01-overview for who implements what |
| `ResolvedTask`, `TaskOverrides`, `TaskSummary`, `AgentSnapshot`, `SkillSnapshot`, `RuleSnapshot`, `TemplateCategory`, `RunInputField` | value objects | what the Task Library hands to the workflow engine; `AgentSnapshot(key, name, harness?, modelProfileId?, instructions, rules, skills, mcpServers)` is who runs the step and how (no harness = model answer, ADR 0056, 0057); `TemplateParameter`, `ResolvedParameter(definition, value, source)` (ADR 0058) |
| `RunnerJobSpec`, `RunnerResult`, `RunnerSkill`, `RunnerMergeRequest` | value objects | runner protocol |

## Context: Task Library (`tasks-core`)

See [07-task-library.md](07-task-library.md) for semantics.

| Aggregate | Fields |
|---|---|
| `TaskTemplate` | `id, key, name, description, abstract, extendsKey?, category: TemplateCategory?, agentKey?, scope: Scope(GLOBAL | PROJECT(projectId)), modelProfileId?, prompt: Prompt(system?, user?, mode: APPEND/REPLACE), parameters: List<TemplateParameter>, parameterValues: Map, config: Map, outputSchema?, confidenceField?, produces, consumes, tags, runInputs, requiresSkills, builtIn, version, timestamps` (no skills or rules since ADR 0057; parameters ADR 0058; `requiresSkills` ADR 0060: a step whose agent lacks one gets a warning) |
| `Skill` | `id, key, name, description, content, scope, tags, builtIn, timestamps` |
| `Rule` | `id, key, name, description, content, scope, automatic, tags, builtIn, timestamps` (ADR 0055) |
| `Agent` | `id, key, name, description, scope, harness: AgentHarness? (OPENCODE/CLAUDE_CODE; none = model answer), modelProfileId?, instructions, rules, skills, mcpServers: List<AgentMcpServer(name, url)>, access: AgentAccess(mode FULL/READ_ONLY/PATHS, paths), tags, builtIn, timestamps` (ADR 0056, 0057; access ADR 0060) |

Template keys are unique across scopes; skills, rules and agents by `(key, scope)` (ADR 0055). Domain service
`TaskResolver` produces `ResolvedTask` (with the resolved agent: step, template lineage, else `model-answer`; the
agent's rules and skills; the parameters with value and source; warnings such as required skills the agent lacks, ADR 0060) and reports when the agent does
not fit the template or a required parameter has no value; `TemplateValidator` checks keys
(`^[a-z][a-z0-9-]{1,63}$`), parent existence, JSON schema syntax and parameter declarations and values. Task interfaces (ADR 0015) were removed by ADR 0055, the template executor by
ADR 0056.

## Context: Projects & Workflows (`projects-core`)

### Project (aggregate root)

| Field | Type | Notes |
|---|---|---|
| `id`, `key`, `name`, `description` | | key `^[A-Z][A-Z0-9_]{1,15}$` |
| `repositories` | `List<GitRepository(key, url, defaultBranch = main, purpose: DOCS/SOURCE/OTHER, description?, primary)>` | zero or more (ADR 0034); keys `^[a-z][a-z0-9-]{0,30}$` unique in the project (also the workspace directory), at most one `primary` (the default of runner steps that name no repository; optional). Used by the runner only (git, no SCM API). Persistence port `ProjectRepository`; Flyway `V109` turned the former single repository into `main` (SOURCE, primary) and keeps `repo_url` / `repo_default_branch` as a mirror of the primary for one release. `primaryRepository()` returns the primary or null |
| `issueTracker` | `IssueTrackerBinding(jiraProjectKey, jql?)` | optional |
| `constitution` | markdown | Spec Kit constitution; part of every step's agent environment (ADR 0057) and readable as `{{project.constitution}}` |
| `defaults` | `ProjectDefaults(modelProfileId?, reviewModelProfileId?)` | |
| `ciEntrypoints` | `List<CiEntrypoint(key, workflowId, mode: BLOCKING/ADVISORY, description, uploadDiff, repository?)>` | keys unique; `repository` = optional repository key: only CI jobs of that repository may start the entrypoint |
| `createdAt, updatedAt` | | |

### ProjectCiToken (aggregate root)

`id, projectId, name, tokenHash (SHA-256), prefix (first 8 chars for display), createdAt, lastUsedAt?,
revokedAt?`. The plaintext is returned once on creation and never stored.

### WorkflowDefinition (aggregate root)

| Field | Notes |
|---|---|
| `id, projectId, name, description, version, enabled, triggers: List<Trigger(MANUAL/JIRA_TRANSITION(status)/API/CI)>` | |
| `nodes: List<WorkflowNode>` | ids unique, slug `^[a-z][a-z0-9-]{0,39}$` |
| `edges: List<WorkflowEdge(id, from, fromPort, to, label?)>` | `(from, fromPort)` unique |
| `setupCommand?` | ADR 0060: runs once per run directory before the first coding step (again when it changes) |
| `canvas: Canvas(zoom, x, y)?` | viewport only; node positions live on nodes |

`WorkflowNode`: `id, kind: NodeKind, name, position: Point(x, y), timeoutSeconds?` plus one of:
`task: TaskBinding(templateKey, overrides: TaskOverrides)` (overrides include `agentKey`, ADR 0056, `parameters`,
ADR 0058, and the coding agent config `directory` RUN/SEPARATE, `session` OWN/NEW/CONTINUE with `continueSession`, `allowQuestions`, `maxQuestionRounds`, ADR 0057, 0060; no skill changes since ADR 0060),
`action` (`type`: FETCH_ISSUE/COMMENT_ISSUE/TRANSITION_ISSUE/START_WORKFLOW/PROJECT_DIGEST, `config`) (node
kind `ACTION`, ADR 0056; a stored `TASK` node naming a former action template is read as one),
`decision: Decision(branches: List<Branch(id,
label, expression)>, defaultBranch?)`, `gate: Gate(question, options, showArtifacts, timeoutSeconds?)`,
`ci: CiWait(signal, timeoutSeconds?)`, `end: End(outcome: SUCCESS/FAILURE)`, `note: Note(text)`.
`maxVisits` (default 3) limits loops per node.

Validator (`WorkflowValidator`, domain service): exactly one START; ≥ 1 END; edge endpoints exist;
ports exist for the node kind (TASK and ACTION nodes have `success`/`failure`); every reachable non-END node
has its `success`/branch ports connected; no unreachable nodes (warning); expressions parse
(`ExpressionEvaluator.validate`); template keys resolve (error when unknown, warning when abstract);
gate options non-empty; signal names `^[a-z][a-z0-9-]{0,39}$`. Step repositories (ADR 0034, rules in
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#step-repositories)) are checked against the project's
repositories; `StartRun` re-checks them and refuses a run whose steps have repository errors.

`library: LibraryLink(libraryWorkflowId, key, version, modified)?` marks a linked copy of a library workflow
(ADR 0037); `WorkflowService.update` recomputes `modified` against the followed version.

### PersonalIntakeRule and IntakeProposal (aggregate roots, ADR 0038)

`PersonalIntakeRule`: `id, ownerUserId, ownerName, name, connectionId` (the owner's personal connection),
`jql, pollIntervalMinutes (1..1440), workflowId, enabled, includeExisting, initialized`, polling state (`nextPollAt`,
`lastPollAt`, `lastSuccessAt`, `lastError`, `consecutiveFailures`). `IntakeProposal`: `id, ruleId, ownerUserId,
issueKey, summary?, issueType?, status?, priority?, state (NEW / STARTED / DISMISSED / BASELINE), detectedAt, decidedAt?,
workflowId?, runId?`; unique per `(ruleId, issueKey)`. `Project.personal` marks a private project (ADR 0038).

### LibraryWorkflow (aggregate root, ADR 0037)

| Field | Notes |
|---|---|
| `id, key, name, description, tags, deprecated, createdAt, updatedAt, updatedBy` | `key` unique, slug `^[a-z][a-z0-9-]{1,63}$` |
| `versions: List<LibraryWorkflowVersion>` | append-only, immutable: `version, changeNote, nodes, edges, triggers, canvas, publishedAt, publishedBy, source(projectId, projectKey, workflowId, workflowVersion)` |

`LibraryPortability` (domain service) strips project-local settings before publishing (step repositories, a
pinned Jira connection id, named approver users; library sources of a step stay, ADR 0043), re-applies them by node id when a copy takes a new version,
and compares graphs ignoring them, node positions and edge ids (`sameGraph`, the `modified` flag). Use case
`ManageWorkflowLibrary` (`WorkflowLibraryService`): list, get, version, update details, delete (refused while
linked), publish, adopt, update a copy, detach. Tables `library_workflow`, `library_workflow_version` (V111).

### LibrarySource (aggregate root, ADR 0043)

| Field | Notes |
|---|---|
| `id, key, name, description, kind, url, defaultBranch, purpose, createdAt, updatedAt, updatedBy` | `key` unique and immutable, `^[a-z][a-z0-9-]{1,26}$`; `kind` only `GIT`; `purpose` a `RepositoryPurpose` |

`jobKey = "lib-" + key` is its repository key and folder in a job (`asRepository()` gives the read-only
`GitRepository`). Steps attach it as `config.repositories` entry `{key, access: READ, library: true}`;
`StepRepositories.plan` resolves it through `LibrarySourceRepository` (unknown key or a clash with a project key is
an error). Use case `ManageLibrarySources` (`LibrarySourceService`, `library.view` / `library.manage`, audited).
Table `library_source` (V116).

### WorkflowRun (aggregate root)

| Field | Notes |
|---|---|
| `id` (LangGraph thread id), `projectId`, `projectKey`, `workflowId`, `workflowVersion`, `definitionSnapshot` | |
| `trigger: Trigger(type: MANUAL/API/CI/JIRA, ciExecutionId?)` | |
| `input: RunInput(issueKey?, parameters, ci: CiContext?)` | |
| `status: PENDING → RUNNING ⇄ WAITING → SUCCEEDED / FAILED / CANCELLED` | |
| `outcome: SUCCESS / FAILURE?` | from the END node reached (FAILURE on unhandled failure) |
| `waitingOn: WaitingOn(kind: HUMAN_GATE/WAIT_FOR_CI/WRITE_CONFIRMATION/AGENT_QUESTIONS, nodeId, approvalId?, signal?)?` | `AGENT_QUESTIONS`: a step's agent stopped with questions (ADR 0060) |
| `nodes: List<NodeExecution>` | `id, nodeId, nodeName, kind, templateKey?, agent: StepAgentRecord?, attempt, status (RUNNING/WAITING/SUCCEEDED/FAILED/SKIPPED), port (taken output port), startedAt, finishedAt, summary, confidence, runnerJobId, usage, cost, error, artifactIds, repositories: List<RepositoryResult>`; `agent` (ADR 0059) = who did a `TASK` step: `agentKey, agentName, harness?, modelProfileId?, modelLabel?`, null for other kinds and for executions before ADR 0059 |
| `artifacts: List<Artifact>` | `id, nodeId, name, kind (SPEC/PLAN/TASKS/ANALYSIS/DIFF/REVIEW/LOG/JSON/MARKDOWN), mediaType, content, createdAt` |
| `mergeRequest: MergeRequestSnapshot(url, branch, targetBranch)?`, `review: ReviewReport?` | `mergeRequest` = the primary repository's merge request, else the first one pushed |
| `repositories: List<RepositoryResult>` | latest result per repository over all runner steps (ADR 0034): `key, url, purpose, access, primary, branch?, commitSha?, diffStat?, mergeRequestUrl?, pushed, error?` |
| `cost`, `createdAt`, `startedAt`, `finishedAt`, `failure: RunFailure(nodeId, message, retryable)?` | |

Invariants: one node RUNNING at a time; `WAITING` implies `waitingOn`; terminal runs immutable except
cost roll-up; `maxVisits` enforced per node.

### ApprovalRequest (aggregate root) — the inbox

`id, runId, nodeId, projectId, projectKey, workflowName, issueKey, question, options, context (summary,
confidence, artifactIds, mergeRequestUrl), status (OPEN/DECIDED/EXPIRED), decision: Decision(option,
comment, editedArtifacts, decidedAt)?, createdAt, kind (GATE/WRITE_CONFIRMATION/AGENT_QUESTIONS)`. ADR 0060: an
`AGENT_QUESTIONS` request carries `questions: List<AgentQuestion(question, why?)>` (at most 10), `questionRound` and
`maxQuestionRounds`; its options are `answer` (the comment holds the answers; the step runs again) and `cancel` (the
step fails).

### CiExecution (aggregate root)

| Field | Notes |
|---|---|
| `id, projectId, projectKey, entrypoint, mode, status (QUEUED/RUNNING/SUCCEEDED/FAILED), gateResult (PASS/FAIL/PENDING)` | |
| `context: CiContext` | provider, projectPath, repositoryUrl?, pipelineId, jobId, jobUrl, ref, commitSha, defaultBranch, mergeRequest?, actor |
| `repositoryKey?` | the project repository the CI job runs in, matched by normalised `repositoryUrl` (ADR 0034); null for older clients without a URL whose `projectPath` matches no repository |
| `variables: Map<String,String>`, `diffArtifactId?`, `changedFiles: List<String>` | |
| `runId?`, `summary?`, `report: ReviewReport?` (verdict, score, findings), `cost`, `createdAt`, `finishedAt?`, `error?` | |

`gateResult` = `PASS` when the run outcome is `SUCCESS` or the mode is `ADVISORY`; `FAIL` otherwise;
`PENDING` while running.

### CiSignal (value object, stored on the run)

`signal, status (SUCCESS/FAIL), message?, context: CiContext, receivedAt`.

### ReviewReport (value object)

`verdict (APPROVE/COMMENT/REQUEST_CHANGES), score 0–100, summary, findings: List<Finding(severity
BLOCKER/MAJOR/MINOR/NIT, file, line?, title, detail, suggestion?)>, modelRef, usage, cost`.

### Domain events

`WorkflowRunStarted, NodeStarted, NodeCompleted, NodeFailed, RunWaitingForHuman, RunWaitingForCi,
ApprovalDecided, CiSignalReceived, WorkflowRunFinished, CiExecutionFinished, RunnerLog`. Adapters fan
them out on `GET /api/v1/events`.

## Context: Model Evaluation

`ModelProfile`, `TaskSuite` / `EvalTask`, `Benchmark` / `Attempt`, leaderboard read model (see
[04-model-evaluation.md](04-model-evaluation.md)). Since ADR 0059 a `Benchmark` holds `candidates:
List<BenchmarkCandidate>` (agent key, name, harness, model profile; empty = a benchmark of models, `legacy`), each
`Attempt` the agent it ran as (`agentKey`, `agentName`, `harness`), and a leaderboard row is one candidate
(`CandidateRef`) with `notApplicable` and `costPerAccepted`. Agents are read through the shared-kernel `TaskCatalog`
port.

## State machines

```
WorkflowRun:   PENDING -> RUNNING -> WAITING -> RUNNING -> SUCCEEDED (outcome SUCCESS|FAILURE)
                              \-> FAILED (outcome FAILURE)   \-> CANCELLED (reject / cancel)
CiExecution:   QUEUED -> RUNNING -> SUCCEEDED | FAILED     (gateResult derived)
Benchmark:     DRAFT -> RUNNING -> COMPLETED | FAILED | CANCELLED
RunnerJob:     CREATED -> RUNNING -> SUCCEEDED | FAILED | TIMED_OUT
```
