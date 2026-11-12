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
| `ResolvedTask`, `TaskOverrides`, `TaskSummary`, `AgentSnapshot`, `SkillSnapshot`, `RuleSnapshot`, `TemplateCategory`, `RunInputField` | value objects | what the Task Library hands to the workflow engine; `AgentSnapshot(key, name, harness?, modelProfileId?, instructions, rules, skills, connectors, access, session)` is who runs the step and how (no harness = model answer, ADR 0056, 0057; `session` and `access.directory` ADR 0062; `connectors` = connector keys, ADR 0064); `TemplateParameter`, `ResolvedParameter(definition, value, source, binding?, fallback?)` (ADR 0058, 0062), `ParameterBinding(source VALUE/START/ARTIFACT, value?, artifact?)` (ADR 0062); `ResolvedTask.instructions` is the merged instruction text (ADR 0062) |
| `RunnerJobSpec`, `RunnerResult`, `RunnerSkill`, `RunnerMergeRequest` | value objects | runner protocol |
| `ConnectorKind` (`MCP_HTTP`, `GITLAB_READ`), `ConnectorAuth` (`NONE`, `BEARER`, `HEADER`), `RunnerConnector(key, kind, url?, auth?, headerName?, repository?)`, `JobConnectors(connectors, credentials, notes)`, `JobCredentialRef` capability `CONNECTOR` | value objects | ADR 0064: a job's connectors as the runner gets them; the secret is only named (`JobCredentialRef(ref = connector id, capability CONNECTOR, connector = key)`), never carried |

## Context: Task Library (`tasks-core`)

See [07-task-library.md](07-task-library.md) for semantics.

| Aggregate | Fields |
|---|---|
| `TaskTemplate` | `id, key, name, description, abstract, extendsKey?, category: TemplateCategory?, scope: Scope(GLOBAL | PROJECT(projectId)), instructions?, instructionsMode: APPEND/REPLACE, parameters: List<TemplateParameter>, parameterValues: Map, config: Map, outputSchema?, confidenceField?, produces, consumes, tags, requiresSkills, outputFiles, builtIn, version, timestamps` (no skills or rules since ADR 0057; parameters ADR 0058; `requiresSkills` ADR 0060: a step whose agent lacks one gets a warning; ADR 0061: no `agentKey` and no `modelProfileId`, the template is only the work; derived, read-only `needsCodingAgent` = the merged config has `codingAgent: true` or `prepareOnly` (ADR 0063; until then also a Spec Kit `phase` or `push`); ADR 0063: `outputFiles` replaces the config key `collectArtifacts`; ADR 0062: one `instructions` text instead of the system and user prompt, no `runInputs` (a step binds parameters to the run start), no sandbox policy) |
| `Skill` | `id, key, name, description, content, scope, tags, builtIn, timestamps` |
| `Rule` | `id, key, name, description, content, scope, automatic, tags, builtIn, timestamps` (ADR 0055) |
| `Agent` | `id, key, name, description, scope, harness: AgentHarness? (OPENCODE/CLAUDE_CODE; none = model answer), modelProfileId?, instructions, rules, skills, connectors: List<String> (connector keys, ADR 0064; replaced `mcpServers`), access: AgentAccess(mode FULL/READ_ONLY/PATHS, paths, directory RUN/SEPARATE), session: AgentSession(PER_STEP/PER_RUN/FRESH), tags, builtIn, timestamps` (ADR 0056, 0057; access ADR 0060; directory and session ADR 0062) |

Template keys are unique across scopes; skills, rules and agents by `(key, scope)` (ADR 0055). Domain service
`TaskResolver` produces `ResolvedTask` (with the resolved agent: the step's, else `opencode` when the work needs a
coding agent, else `model-answer`, ADR 0061; model: the step's, else the agent's, else the defaults; the
agent's rules and skills; the parameters with value and source; warnings such as required skills the agent lacks, ADR 0060) and reports when the agent does
not fit the template or a required parameter has no value; `TemplateValidator` checks keys
(`^[a-z][a-z0-9-]{1,63}$`), parent existence, JSON schema syntax and parameter declarations and values. Task interfaces (ADR 0015) were removed by ADR 0055, the template executor by
ADR 0056.

## Context: Projects & Workflows (`projects-core`)

### Project (aggregate root)

| Field | Type | Notes |
|---|---|---|
| `id`, `key`, `name`, `description` | | key `^[A-Z][A-Z0-9_]{1,15}$` |
| `repositories` | `List<GitRepository(key, url, defaultBranch = main, purpose: DOCS/SOURCE/OTHER, description?, primary)>` | zero or more (ADR 0034); keys `^[a-z][a-z0-9-]{0,30}$` unique in the project (also the workspace directory), at most one `primary` (the default source of a workflow whose `sources` are empty, ADR 0063; optional). Used by the runner only (git, no SCM API). Persistence port `ProjectRepository`; Flyway `V109` turned the former single repository into `main` (SOURCE, primary) and keeps `repo_url` / `repo_default_branch` as a mirror of the primary for one release. `primaryRepository()` returns the primary or null |
| `issueTracker` | `IssueTrackerBinding(jiraProjectKey, jql?)` | optional |
| `constitution` | markdown | the project owner's rules; part of every step's agent environment (ADR 0057) and readable as `{{project.constitution}}`. The Spec Kit switch `specKitEnabled` (ADR 0036) was dropped with ADR 0063 (V122) |
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
| `sources: List<WorkflowSource(key, library)>` | ADR 0063: the repositories cloned into the run directory (project repositories, library sources with `library = true`); empty = the project's primary repository. Agents read them; only `DELIVER_CHANGES` writes back, to project repositories (`WorkflowSources`) |
| `setupCommand?` | ADR 0060: runs once per run directory before the first coding step (again when it changes); since ADR 0063 the only setup command (steps have none) |
| `canvas: Canvas(zoom, x, y)?` | viewport only; node positions live on nodes |

`WorkflowNode`: `id, kind: NodeKind, name, position: Point(x, y), timeoutSeconds?` plus one of:
`task: TaskBinding(templateKey, overrides: TaskOverrides)` (overrides include `agentKey`, ADR 0056, the only place
an agent is chosen since ADR 0061, `modelProfileId`, `instructionsAppend` / `instructionsReplace` and
`parameterBindings: Map<String, ParameterBinding>` (VALUE/START/ARTIFACT), ADR 0062, and the agent step config
`allowQuestions`, `maxQuestionRounds`, ADR 0060; no skill changes since ADR 0060; the step keys `directory`, `session`,
`continueSession` moved to the agent with ADR 0062),
`action` (`type`: FETCH_ISSUE/COMMENT_ISSUE/TRANSITION_ISSUE/START_WORKFLOW/PROJECT_DIGEST/DELIVER_CHANGES, `config`)
(node kind `ACTION`, ADR 0056; a stored `TASK` node naming a former action template is read as one; ADR 0063: a
stored step with the former git keys is read as the step plus a `DELIVER_CHANGES` node, `LegacyStepGit`),
`decision: Decision(branches: List<Branch(id,
label, expression)>, defaultBranch?)`, `gate: Gate(question, options, showArtifacts, timeoutSeconds?)`,
`ci: CiWait(signal, timeoutSeconds?)`, `end: End(outcome: SUCCESS/FAILURE)`, `note: Note(text)`.
`maxVisits` (default 3) limits loops per node.

Validator (`WorkflowValidator`, domain service): exactly one START; ≥ 1 END; edge endpoints exist;
ports exist for the node kind (TASK and ACTION nodes have `success`/`failure`); every reachable non-END node
has its `success`/branch ports connected; no unreachable nodes (warning); expressions parse
(`ExpressionEvaluator.validate`); template keys resolve (error when unknown, warning when abstract);
gate options non-empty; signal names `^[a-z][a-z0-9-]{0,39}$`. The workflow's sources and its deliveries (ADR 0034,
0063, rules in [03-pipelines-and-agents.md](03-pipelines-and-agents.md#workflow-sources)) are checked against the
project's repositories and the library sources; `StartRun` re-checks them and refuses a run with repository errors.
A `DELIVER_CHANGES` reachable from a decision on model output without a human gate is a warning.

`AgentAuditor` (domain service, ADR 0061) builds the agent audit of a workflow draft: per agent its steps use
(`AgentAuditEntry`: agent, scope, node ids, rules, skills, required skills with the nodes needing them, missing
skills) and `AgentAuditFinding(kind, severity, message, nodeIds, key?)` with kinds `MISSING_SKILL` (WARNING),
`NEEDS_CODING_AGENT`, `UNKNOWN_RULE`, `UNKNOWN_SKILL`, `UNKNOWN_AGENT` (ERROR: the resolver refuses the step) and
`DUPLICATE_SKILL_NAME` (WARNING); `READ_ONLY_PUSH` stays in the API but is no longer reported (ADR 0063: agents never
push). It reads the Task Library only through the `TaskCatalog` port
(`stepNeeds`, `agentProfile`). `AgentCoherenceService` (application service) produces a `CoherenceReport(agentKey,
model?, cost, sources, findings[severity CONTRADICTION/AMBIGUITY/REDUNDANCY, summary, quotes, suggestion?])`; nothing
is stored but the audit entry `AGENT_COHERENCE_CHECK`.

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

`LibraryPortability` (domain service) strips project-local settings before publishing (the project repositories
among the workflow's `sources`, a pinned Jira connection id, named approver users; library sources stay, ADR 0043,
0063; an update keeps the copy's own project repositories), re-applies them by node id when a copy takes a new version,
and compares graphs ignoring them, node positions and edge ids (`sameGraph`, the `modified` flag). Use case
`ManageWorkflowLibrary` (`WorkflowLibraryService`): list, get, version, update details, delete (refused while
linked), publish, adopt, update a copy, detach. Tables `library_workflow`, `library_workflow_version` (V111).

### LibrarySource (aggregate root, ADR 0043)

| Field | Notes |
|---|---|
| `id, key, name, description, kind, url, defaultBranch, purpose, createdAt, updatedAt, updatedBy` | `key` unique and immutable, `^[a-z][a-z0-9-]{1,26}$`; `kind` only `GIT`; `purpose` a `RepositoryPurpose` |

`jobKey = "lib-" + key` is its repository key and folder in a job (`asRepository()` gives the read-only
`GitRepository`). A workflow lists it in its `sources` as `{key, library: true}` (ADR 0063; until then a step's
`config.repositories` entry `{key, access: READ, library: true}`); `WorkflowSources.plan` resolves it through
`LibrarySourceRepository` (unknown key or a clash with a project key is an error). It is never delivered to. Use case `ManageLibrarySources` (`LibrarySourceService`, `library.view` / `library.manage`, audited).
Table `library_source` (V116).

### Connector (aggregate root, ADR 0064)

| Field | Notes |
|---|---|
| `id, key, name, description, kind: ConnectorKind, projectId?` | `projectId` null = platform scope (every project), else project scope; `key` `^[a-z][a-z0-9-]{0,39}$`, derived from the name when omitted, immutable, unique per scope; the scope cannot change either |
| `url?, auth: ConnectorAuth, headerName?` | `MCP_HTTP`: http(s) URL of the MCP endpoint (streamable HTTP); `BEARER` sends `Authorization: Bearer <secret>`, `HEADER` sends `<headerName>: <secret>` (`^[A-Za-z0-9-]{1,64}$`). Derived `host` = the host the sandbox egress policy must allow |
| `secret: SealedSecret?` | write-only, single line; AES-256-GCM with a random data key wrapped by the key-encryption key (like connection secrets, ADR 0030), associated data `connector:<id>`; never returned (`hasSecret` only). Rewrapped on key rotation (`CONNECTOR_SECRET_REWRAP`) |
| `repository?` | `GITLAB_READ` (project scope only): a project repository key; null = the primary repository |
| `lastTest: ConnectorTest(ok, message?, tools, testedAt)?` | result of the last **Test** from Back Office |
| `createdAt, createdBy, updatedAt, updatedBy` | |

Use case `ManageConnectors` (`ConnectorService`): list (`library.view` for the platform, `project.view` with a
project), create / update / delete / test (`library.manage` for platform connectors, `project.connections.manage` for
project connectors), audited `CONNECTOR_CREATE` / `UPDATE` / `DELETE` / `TEST`. Test: `MCP_HTTP` through the port
`McpProbePort` (`McpHttpProbe`: `initialize`, `notifications/initialized`, `tools/list`, JSON or SSE answers, no
redirects, short timeouts); `GITLAB_READ` re-verifies the least-privileged read-only `CI_READ` token of the repository.
Domain service `ConnectorResolution` turns an agent's keys into the job's `JobConnectors`: the project's connector of
a key, else the platform's (`lookup`); unknown keys and MCP connectors whose auth needs a missing secret are skipped
with a note in the step log; a `GITLAB_READ` connector without a verified read-only `CI_READ` token fails the step
before any job exists. Table `connector` (V123; partial unique indexes on `key` for the platform and on
`(project_id, key)`); V310 migrated agent MCP servers and step `agentTools` (see 07-task-library.md).

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
| `mergeRequest: MergeRequestSnapshot(url, branch, targetBranch)?`, `review: ReviewReport?` | `mergeRequest` = the primary repository's merge request of a `DELIVER_CHANGES` step, else the first one opened |
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
