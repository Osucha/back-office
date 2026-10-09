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
| `ResolvedTask`, `TaskOverrides`, `TaskSummary`, `SkillSnapshot`, `TaskExecutor` | value objects | what the Task Library hands to executors |
| `RunnerJobSpec`, `RunnerResult`, `RunnerSkill`, `RunnerMergeRequest` | value objects | runner protocol |

## Context: Task Library (`tasks-core`)

See [07-task-library.md](07-task-library.md) for semantics.

| Aggregate | Fields |
|---|---|
| `TaskInterface` | `id, key, name, description, inputs: List<Slot(name, kind: INPUT/STATE/ARTIFACT, required, description)>, outputs: List<Slot>, ports: List<String>, builtIn, createdAt, updatedAt` |
| `TaskTemplate` | `id, key, name, description, abstract, extendsKey?, implements: List<String>, executor: TaskExecutor?, scope: Scope(GLOBAL | PROJECT(projectId)), modelProfileId?, prompt: Prompt(system?, user?, mode: APPEND/REPLACE), skills: List<String>, removeSkills: List<String>, config: Map, outputSchema?, confidenceField?, produces, consumes, tags, builtIn, version, timestamps` |
| `Skill` | `id, key, name, description, content, scope, tags, builtIn, timestamps` |

Uniqueness: `(key, scope)` for templates and skills; `key` for interfaces. Domain service
`TaskResolver` produces `ResolvedTask`; `TemplateValidator` checks keys (`^[a-z][a-z0-9-]{1,63}$`),
parent existence, executor presence on concrete templates, JSON schema syntax.

## Context: Projects & Workflows (`projects-core`)

### Project (aggregate root)

| Field | Type | Notes |
|---|---|---|
| `id`, `key`, `name`, `description` | | key `^[A-Z][A-Z0-9_]{1,15}$` |
| `repositories` | `List<GitRepository(key, url, defaultBranch = main, purpose: DOCS/SOURCE/OTHER, description?, primary)>` | zero or more (ADR 0034); keys `^[a-z][a-z0-9-]{0,30}$` unique in the project (also the workspace directory), at most one `primary` (the default of runner steps that name no repository; optional). Used by the runner only (git, no SCM API). Persistence port `ProjectRepository`; Flyway `V109` turned the former single repository into `main` (SOURCE, primary) and keeps `repo_url` / `repo_default_branch` as a mirror of the primary for one release. `primaryRepository()` returns the primary or null |
| `issueTracker` | `IssueTrackerBinding(jiraProjectKey, jql?)` | optional |
| `constitution` | markdown | Spec Kit constitution, injected into `base-agent` prompts |
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
| `canvas: Canvas(zoom, x, y)?` | viewport only; node positions live on nodes |

`WorkflowNode`: `id, kind: NodeKind, name, position: Point(x, y), timeoutSeconds?` plus one of:
`task: TaskBinding(templateKey, overrides: TaskOverrides)`, `decision: Decision(branches: List<Branch(id,
label, expression)>, defaultBranch?)`, `gate: Gate(question, options, showArtifacts, timeoutSeconds?)`,
`ci: CiWait(signal, timeoutSeconds?)`, `end: End(outcome: SUCCESS/FAILURE)`, `note: Note(text)`.
`maxVisits` (default 3) limits loops per node.

Validator (`WorkflowValidator`, domain service): exactly one START; ≥ 1 END; edge endpoints exist;
ports exist for the node kind (TASK ports come from the resolved template through a `TaskPortsProvider`
port, defaulting to `success`/`failure` when the catalog is unavailable); every reachable non-END node
has its `success`/branch ports connected; no unreachable nodes (warning); expressions parse
(`ExpressionEvaluator.validate`); template keys resolve (error when unknown, warning when abstract);
gate options non-empty; signal names `^[a-z][a-z0-9-]{0,39}$`. Step repositories (ADR 0034, rules in
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#step-repositories)) are checked against the project's
repositories; `StartRun` re-checks them and refuses a run whose steps have repository errors.

`library: LibraryLink(libraryWorkflowId, key, version, modified)?` marks a linked copy of a library workflow
(ADR 0037); `WorkflowService.update` recomputes `modified` against the followed version.

### LibraryWorkflow (aggregate root, ADR 0037)

| Field | Notes |
|---|---|
| `id, key, name, description, tags, deprecated, createdAt, updatedAt, updatedBy` | `key` unique, slug `^[a-z][a-z0-9-]{1,63}$` |
| `versions: List<LibraryWorkflowVersion>` | append-only, immutable: `version, changeNote, nodes, edges, triggers, canvas, publishedAt, publishedBy, source(projectId, projectKey, workflowId, workflowVersion)` |

`LibraryPortability` (domain service) strips project-local settings before publishing (step repositories, a
pinned Jira connection id, named approver users), re-applies them by node id when a copy takes a new version,
and compares graphs ignoring them, node positions and edge ids (`sameGraph`, the `modified` flag). Use case
`ManageWorkflowLibrary` (`WorkflowLibraryService`): list, get, version, update details, delete (refused while
linked), publish, adopt, update a copy, detach. Tables `library_workflow`, `library_workflow_version` (V111).

### WorkflowRun (aggregate root)

| Field | Notes |
|---|---|
| `id` (LangGraph thread id), `projectId`, `projectKey`, `workflowId`, `workflowVersion`, `definitionSnapshot` | |
| `trigger: Trigger(type: MANUAL/API/CI/JIRA, ciExecutionId?)` | |
| `input: RunInput(issueKey?, parameters, ci: CiContext?)` | |
| `status: PENDING → RUNNING ⇄ WAITING → SUCCEEDED / FAILED / CANCELLED` | |
| `outcome: SUCCESS / FAILURE?` | from the END node reached (FAILURE on unhandled failure) |
| `waitingOn: WaitingOn(kind: HUMAN_GATE/WAIT_FOR_CI, nodeId, approvalId?, signal?)?` | |
| `nodes: List<NodeExecution>` | `id, nodeId, nodeName, kind, attempt, status (RUNNING/WAITING/SUCCEEDED/FAILED/SKIPPED), port (taken output port), startedAt, finishedAt, summary, confidence, runnerJobId, usage, cost, error, artifactIds, repositories: List<RepositoryResult>` |
| `artifacts: List<Artifact>` | `id, nodeId, name, kind (SPEC/PLAN/TASKS/ANALYSIS/DIFF/REVIEW/LOG/JSON/MARKDOWN), mediaType, content, createdAt` |
| `mergeRequest: MergeRequestSnapshot(url, branch, targetBranch)?`, `review: ReviewReport?` | `mergeRequest` = the primary repository's merge request, else the first one pushed |
| `repositories: List<RepositoryResult>` | latest result per repository over all runner steps (ADR 0034): `key, url, purpose, access, primary, branch?, commitSha?, diffStat?, mergeRequestUrl?, pushed, error?` |
| `cost`, `createdAt`, `startedAt`, `finishedAt`, `failure: RunFailure(nodeId, message, retryable)?` | |

Invariants: one node RUNNING at a time; `WAITING` implies `waitingOn`; terminal runs immutable except
cost roll-up; `maxVisits` enforced per node.

### ApprovalRequest (aggregate root) — the inbox

`id, runId, nodeId, projectId, projectKey, workflowName, issueKey, question, options, context (summary,
confidence, artifactIds, mergeRequestUrl), status (OPEN/DECIDED/EXPIRED), decision: Decision(option,
comment, editedArtifacts, decidedAt)?, createdAt`.

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

Unchanged from the first iteration: `ModelProfile`, `TaskSuite` / `EvalTask`, `Benchmark` / `Attempt`,
leaderboard read model (see [04-model-evaluation.md](04-model-evaluation.md)).

## State machines

```
WorkflowRun:   PENDING -> RUNNING -> WAITING -> RUNNING -> SUCCEEDED (outcome SUCCESS|FAILURE)
                              \-> FAILED (outcome FAILURE)   \-> CANCELLED (reject / cancel)
CiExecution:   QUEUED -> RUNNING -> SUCCEEDED | FAILED     (gateResult derived)
Benchmark:     DRAFT -> RUNNING -> COMPLETED | FAILED | CANCELLED
RunnerJob:     CREATED -> RUNNING -> SUCCEEDED | FAILED | TIMED_OUT
```
