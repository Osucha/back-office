# 03 — Workflows, CI and agents

## What a workflow is

A workflow is a directed graph designed on a canvas (ADR 0014): nodes are blocks, edges connect a
node's **output port** to the next node. Blocks are mostly agent tasks taken from the Task Library
(ADR 0015); decisions, human gates and CI gates are control nodes. A workflow belongs to a project and is
versioned; every run pins the version it started with.

Where things execute (ADR 0013):

| Runs in Back Office | Runs in the project's CI (or any CI) |
|---|---|
| Agent tasks (LLM calls, coding agents in the runner), delivery of changes (commit, branch, merge request), decisions, human gates, Jira tasks | Build, tests, linters, deployment, and calls to Back Office through `backoffice-ci.sh` |
| A `WAIT_FOR_CI` node pauses a run until CI reports a signal | `backoffice-ci signal <name> --status success|fail` resumes it |
| A CI entrypoint maps a key to a workflow | `backoffice-ci run <entrypoint>` starts it, waits, exits 0/1 |

## Node kinds and ports

| Kind | Purpose | Output ports |
|---|---|---|
| `START` | entry; exactly one per workflow | `out` |
| `END` | terminal; `outcome: SUCCESS | FAILURE` decides run outcome and CI gate result | none |
| `TASK` | executes a task template (`templateKey` + overrides) | template ports, by default `success`, `failure` |
| `DECISION` | routes on expressions over the run state | one port per branch (+ optional `default`) |
| `HUMAN_GATE` | pauses; the owner picks an option in the inbox | one port per option (default `approve`, `revise`, `reject`) |
| `WAIT_FOR_CI` | pauses until CI signals `signal` for this run | `success`, `fail`, `timeout` |
| `NOTE` | sticky note on the canvas | none |

An unconnected `failure` port means "fail the run"; an unconnected `success` port is a validation error
unless the node is followed by nothing on purpose (then connect it to an `END`).

## Decision expressions

Boolean SpEL expressions evaluated read-only against `WorkflowStateView`:

| Root | Content |
|---|---|
| `input` | run input: `issueKey`, `parameters`, and for CI-triggered runs `ci` (see CI context) |
| `issue` | Jira snapshot after a `fetch-issue` task |
| `artifacts` | map name → `{id, name, kind, sizeBytes}` (`artifacts['spec'] != null`) |
| `confidence` | last numeric confidence produced by a task |
| `lastOutcome` | `SUCCESS`, `FAILURE` of the previous node |
| `review` | last review report: `verdict`, `score`, `blockers`, `majors` |
| `mr` | `{url, branch, targetBranch}` after an implementation task pushed (the primary repository's merge request, else the first one pushed; ADR 0034) |
| `ci` | last CI signal: `{signal, status, message}` |
| `decision` | last human decision: `{option, comment}` |

Examples: `confidence >= 0.8`, `review.verdict != 'REQUEST_CHANGES'`, `input.ci.mergeRequest.targetBranch == 'main'`,
`artifacts['tasks'] != null and lastOutcome == 'SUCCESS'`.

## Example: issue to reviewed merge request

```
START ─▶ fetch-issue ─▶ write-spec ─▶ clarify-spec ─▶ [DECISION confidence >= 0.8]
                                             ▲                 │ true            │ false
                                             │ revise          ▼                 ▼
                                             └────────── [HUMAN_GATE "Spec ok?"] ◀┘
                                                               │ approve
                                                               ▼
     write-plan ─▶ write-task-list ─▶ implement-opencode ─▶ [ACTION DELIVER_CHANGES] ─▶ [WAIT_FOR_CI "tests"]
                                              ▲                                          │ success   │ fail
                                              │ (revise)                                 ▼           ▼
                     code-review ◀───────────────────────────────────────────────────────┘      END(FAILURE)
                          │
                          ▼
              [DECISION review.verdict != 'REQUEST_CHANGES'] ── false ──▶ implement-opencode (loop, max 2)
                          │ true
                          ▼
              [HUMAN_GATE "Merge?"] ── approve ──▶ transition-issue ─▶ END(SUCCESS)
```

The owner is involved at most twice. The implementation task changes the run directory; `DELIVER_CHANGES` commits,
pushes a branch with push options that open the merge request; the project's CI runs its tests and calls `backoffice-ci signal tests`.

## Example: CI code review entrypoint

```
START ─▶ code-review ─▶ [DECISION review.verdict != 'REQUEST_CHANGES'] ─ true ─▶ END(SUCCESS)
                                                                        └ false ─▶ END(FAILURE)
```

Registered on the project as entrypoint `code-review` (mode `BLOCKING`). The CI job runs
`backoffice-ci run code-review --wait` which uploads the MR diff; the `code-review` task reads
`artifacts['diff']`.

## Run state and execution

`WorkflowRun` records a `NodeExecution` per node attempt (status, taken port, usage, cost, confidence,
artefacts, runner job id) and `waitingOn` when paused (wait kind `HUMAN_GATE` with the approval id,
`WAIT_FOR_CI` with the signal name, or `WRITE_CONFIRMATION` with the approval id of a write confirmation,
ADR 0033). A `WRITE_CONFIRMATION` wait keeps the task's node execution open (`WAITING`, then `RUNNING`
until the write finishes) and never times out (`WaitTimeoutService` skips it; the node timeout limits
execution, not the wait). The run outcome is the `END` node outcome; a run that fails with an
unhandled error or an unconnected `failure` port is `FAILED` with `outcome = FAILURE`.

State channels used for routing (kept in the LangGraph checkpoint): `input`, `issue`, `artifacts`
(map name → artifact id), `confidence`, `lastOutcome`, `review`, `mr`, `ci`, `decision`, `feedback`
(appender of revision comments), `loopCounts` (per node, to stop infinite loops; default max 3 visits),
`pendingJob` (`{nodeId, jobId}` of the runner job a node waits for) and `pendingWrite` (`{nodeId, action,
issueKey, comment, toStatus, ...}` of the external write a task waits to have confirmed; key
`RunStateKeys.PENDING_WRITE`, ADR 0033).

## Compilation to LangGraph4j

```
WorkflowDefinition ──compile──► StateGraph<WorkflowState>
  node n (TASK/DECISION/...)  ──► graph node "n" delegating to the executor for its kind
  edge n.port → m             ──► conditional edge on "n": state.lastPort == port → "m"
  END(outcome)                ──► terminal node that records the outcome, then END
  HUMAN_GATE, WAIT_FOR_CI     ──► interruptBefore("n"); resume with GraphInput.resume({decision|ci})
  TASK n (runner-backed)      ──► "n::await", interruptBefore; the job's continuation resumes it
  ACTION n (external write)   ──► "n::confirm", interruptBefore; resumed by the write decision
  START                       ──► START → first successor
```

**Approval before external writes (ADR 0033).** A node that may write externally also gets a synthetic node
`<id>::confirm`, interrupting like `<id>::await`. A Jira write action (`COMMENT_ISSUE`, `TRANSITION_ISSUE`) with
node configuration `approval: REQUIRED` (the default; missing = `REQUIRED`) renders its write once, checks
that a usable connection exists and returns the write as `pendingWrite` instead of performing it; the
engine routes to `<id>::confirm`, the run records `waitingOn = WRITE_CONFIRMATION` and an approval of kind
`WRITE_CONFIRMATION` (options approve / reject, no artefact edits, preview with `contentSha256`) is opened
and announced with `RunWaitingForHuman(kind = WRITE_CONFIRMATION)`. The decision resumes the graph:
approve performs exactly the stored write (no re-rendering) and leaves through `success`; reject writes
nothing and leaves through `failure` ("write rejected by <name>, nothing was written ..."). Who decides is
the node's `approvers` (gate approver rules, default owner and maintainer); `run.assign` reassignment
works as for gates. Audit: `RUN_WRITE_DECIDED`, `EXTERNAL_WRITE`. With `approval: NOT_REQUIRED` the step
writes at once and the validator warns.

Checkpoints go to PostgreSQL (`PostgresSaverV2`). Graphs are cached per `(workflowId, version)`.
Recovery on restart resumes `RUNNING` runs from their checkpoint; runner-backed nodes re-attach to
Jobs by name (`run-<runId8>-<nodeId>-<attempt>`).

## How task and action nodes run

The Projects context resolves a `TASK` node through the `TaskCatalog` port into a `ResolvedTask`. Since ADR 0056 a
template has no executor: the step runs according to its resolved **agent** and the template's config. Since ADR 0061
the template names no agent and no model: the agent is the step's `overrides.agentKey`, else `opencode` when the
merged config needs a coding agent (`codingAgent: true`, `prepareOnly`; ADR 0063), else `model-answer`. Model: the step's,
else the agent's, then the defaults (`ModelResolver`; the model plan reports the source `AGENT` for the agent's model;
the source `TEMPLATE` is no longer produced).
Since ADR 0057 every task step gets one **environment document** rendered by `AgentEnvironment`: the platform preamble
for its kind of agent, the agent's instructions, the project constitution, then the rules (automatic, then the
agent's); see [07-task-library.md](07-task-library.md#agents-adr-0056-0057). The task prompt carries the task only.

**The task message (ADR 0062).** A template has one text, `instructions` (no system and user prompt any more).
`TaskMessage` composes what the agent gets as its task: the step's merged instructions (template lineage with
`instructionsMode` APPEND/REPLACE, then the step's `instructionsAppend` or `instructionsReplace`; `Task: {{node.name}}`
when there are none), then the **standard data sections**, each only when it has content and the instructions do not
place it with its own placeholder: the issue (`{{issue.*}}`), the artefacts the task consumes (`{{artifacts.<name>}}`,
headings such as "Specification", "Latest review"), the parameter values bound `START` that the instructions do not
read (`{{params.<name>}}`, under the parameter's label; none when the instructions use the ADR 0053 `{{runInputText}}` /
`{{runInput}}`), the reviewer's feedback (`{{feedback}}`) and the owner's answers (`{{answers}}`, ADR 0060); a consumed
`diff` comes last, so a message cut at its size limit loses diff lines, never the instructions. Everything is rendered
in one `renderPrompt` pass, so untrusted values are data blocks (ADR 0029) wherever they are placed. The system part
is the platform's: the environment document, the skills (model answer) and the answer format (output schema,
questions). A model answer gets the message as its user message, a coding agent as its prompt (with the answer
section and the repositories list after it). Why one text: the frame (who you are, which rules) is the agent's since
ADR 0057, coding agents glued both fields into one prompt anyway, and what was left in template system prompts were
role sentences duplicating the agent and data sections every template had to remember.

The deterministic integrations are `ACTION` nodes (`action.type`,
`action.config`; palette `actions` with config schema, defaults, run inputs, ports `success`/`failure` and required
connection capabilities), not tasks.

| Way of running | When | Implementation | Inputs / outputs |
|---|---|---|---|
| model answer (former `LLM`) | agent without a harness | one structured call through `LlmGateway`; system prompt = environment document, then the skills in a `<skills>` section, then the data-block notice; the gateway adds the answer schema (PROMPT jobs of ADR 0050 get the same as `systemPrompt`); user message = the task message (ADR 0062), rendered with `issue`, `artifacts.*`, `feedback`, `answers`, `params.*`, `project.*`, `input.ci.*`; `outputSchema` → `structured`; `confidenceField` → `confidence`; every key in `produces` that matches a structured field or the whole content becomes an artefact | consumes/produces per template |
| agent job (former `IMPLEMENT`) | coding agent (OpenCode, Claude Code) | runner Job, `mode = IMPLEMENT`, the agent's harness; environment document in `spec.instructions`, the agent's connectors in `spec.connectors` (secrets named in `credentials`, ADR 0064, [Connectors at a step](#connectors-at-a-step)); skills written to the agent's home (ADR 0060); `verifyCommand`; the template's `outputFiles` as `collectArtifacts` (named like the file without extension: `spec`, `plan`, `tasks`, `work-log`, ...); the workflow's sources, all `READ` ([Workflow sources and delivery](#step-repositories)); **never commits or pushes** (ADR 0063): result `diff` (work tree against the base) becomes artefact `diff`, per-repository results → `NodeExecution.repositories`; with an `outputSchema` the structured answer below | produces `diff` and the output files; without a repository the final answer is the `result` artefact |

ADR 0063 removed the Spec Kit job (`mode = SPEC_KIT`, config `phase`, ADR 0036): `write-spec`, `write-plan` and
`write-task-list` are ordinary agent jobs that write `specs/<feature>/spec.md`, `plan.md` and `tasks.md`, and a stored
`phase` is ignored (V309 moved such steps to the replacement templates; `LegacyStepGit` does the same at read time).

The resolver reports a problem (the step cannot run) when a template needs a coding agent (`codingAgent: true`,
`prepareOnly`; V309 turned a former `phase` or `push: true` into `codingAgent: true`) but the step's agent is a model
answer, or when a required template parameter (ADR 0058) has no value.

**Precedence of sources (ADR 0061).** The platform preamble ends with point 5 (`AgentEnvironmentText.PRECEDENCE`):
when the agent's sources disagree, the project constitution wins, then the rules (repository `AGENTS.md` /
`CLAUDE.md`, project, agent), then the skills, then the task; a contradiction the agent cannot resolve is asked
(when questions are allowed) instead of guessed.

**Agent audit and coherence check (ADR 0061).** `POST /workflows/agent-audit` (`project.view`; body: `projectId` and
the draft's `nodes`) runs `AgentAuditor` over the draft: per agent its task steps use, its rules, skills, the skills
the steps require (from `TaskCatalog.stepNeeds`, which resolves each step with its overrides) and the missing ones,
plus deterministic findings (`MISSING_SKILL`, `NEEDS_CODING_AGENT`, `UNKNOWN_RULE`, `UNKNOWN_SKILL`,
`UNKNOWN_AGENT`, `DUPLICATE_SKILL_NAME`; `UNKNOWN_CONNECTOR`, a warning, when a coding agent lists a connector key
defined neither in the project nor on the platform, ADR 0064; `READ_ONLY_PUSH` stays in the API but is no longer reported, ADR 0063). No model is called and nothing changes; the designer reloads it on edits
and shows the panel "Agents in this workflow". "Add to agent" is an ordinary agent update (a global agent is first
copied into the project with the same key). `POST /agents/{agentId}/coherence-check?projectId=` (`library.view`, with
a project also `project.view`) is the on-demand model check: `AgentCoherenceService` sends the environment document
(preamble, instructions, constitution, rules) plus skill names and descriptions, and the primary repository's
`AGENTS.md` / `CLAUDE.md` when Back Office can read them (otherwise `sources` says the repository files were not read),
to the platform default model for model answers through `LlmGateway` (booked as usage); `409` when no usable default
model is configured. Never run before a run.

**Structured answers of coding agents (ADR 0057).** A coding agent step with an `outputSchema` is told in its prompt
to write its answer as one JSON object to `structured-answer.json` in its working directory. The runner collects it
as the artefact `structured-answer`, a delivery keeps it out of the commit, and a stale one is removed before the agent starts;
`StructuredAnswers` parses it and applies the same schema check, confidence field and produced artefacts as a model
answer. A missing or invalid answer fails the step with the reasons.

**Run directory and sessions (ADR 0060, 0062; replacing the run workspaces of ADR 0044).** Every run has one run
directory: the coding steps whose agent has `access.directory` `RUN` (default) work in it one after another (the
runner workspace with key `run`, ADR 0044 leasing and volumes unchanged); an agent with `SEPARATE` gets a throwaway
directory with a fresh copy of the workflow's sources and no session (its changes are never delivered).
`WorkflowDefinition.setupCommand` runs once per run directory before the first coding step (again when it changes);
since ADR 0063 it is the only setup command (V309 appended former step `setupCommand`s to it, joined by ` && `). Since ADR 0062 the session is the agent's too
(`Agent.session`), and `RunDirectory` derives the session role from it: `PER_STEP` (default; the step's own role
`s-<node id>`, so repeated executions such as rework loops or answered questions continue it), `PER_RUN` (the agent's
role `a-<agent key>`: all steps of this agent in a run are one conversation) or `FRESH` (no role, every execution
starts anew); a `SEPARATE` agent and a prepare step have no role. Claude Code resumes with `--resume`, OpenCode with
`--continue`. Because the rules live in the environment document and not in the prompt, a continued session does not
receive them again. Compatibility: the stored step keys `workspace` (ADR 0044) and `session`, `continueSession`,
`directory` (ADR 0060 step settings) are dropped when workflows are read (V308 removed them from stored steps); a
legacy `sessionRole` is the step's session role while the agent keeps `PER_STEP`, so steps that shared one keep
sharing their session; `prepareOnly` steps (`prepare-workspace`, deprecated) prepare the run directory and need an
agent with directory `RUN`. Migration `V121__run_directory_and_agent_questions.sql` moved a project workspace's `setupCommand` to the
workflows whose steps named it and its sources to the `repositories` of those steps that had none (since ADR 0063 the
workflow's `sources`), then dropped `project_workspace`.

**Agent home and access (ADR 0060).** Every coding job names its agent's home (`RunnerJobSpec.agentHome` = the agent
key): `/workspace/.agents/<key>/` holds the environment document, skills, MCP configuration (without secrets) and sessions (Claude Code
`CLAUDE_CONFIG_DIR`; OpenCode `OPENCODE_CONFIG_DIR` and the data directory per session role). Nothing of an agent is
written into a repository. `Agent.access` (`FULL` default, `READ_ONLY`, `PATHS` with globs) travels as
`RunnerJobSpec.access` when not `FULL`; the runner applies it as harness tool rules and reverts changes outside it
after the agent (runner README, "Agent home, access and questions"). Agents never push (ADR 0063), so a `READ_ONLY`
agent fits every step. The environment is set once, on the agent: `TaskOverrides.addSkills` / `removeSkills` are gone
(stored steps are read without them); a template's `requiresSkills` (inherited, unioned) the agent lacks becomes a
`check.warnings` entry, not a problem (and a `MISSING_SKILL` finding of the agent audit, ADR 0061).

<a id="connectors-at-a-step"></a>**Connectors at a step (ADR 0064).** The tools of a coding step are its agent's
connectors; a step adds none (the step config key `agentTools` is gone: V310 removed it from workflows, library
versions and unfinished run snapshots, and the config forms no longer show it). When `RunnerTaskExecutors` plans an agent job it
reads `AgentSnapshot.connectors` (model answer agents: ignored) and `ConnectorResolution` resolves each key: the run's
project connector, else the platform one. The result is `spec.connectors` plus `spec.credentials` entries
(`CONNECTOR` for an `MCP_HTTP` secret, `CI_READ` for a `GITLAB_READ` repository's verified read-only token), never a
secret value, and step-log notes: what each connector is, which are skipped (unknown key; an `MCP_HTTP` connector
whose auth needs a missing secret; a `GITLAB_READ` repository that is not a source of the step). A `GITLAB_READ`
connector without a verified read-only `CI_READ` token fails the step before any job exists, naming the token to
add; a token with write powers never reaches an agent. The runner-controller fetches the secrets sealed (ADR 0051)
and delivers them as bundle files; the runner puts an MCP secret only into the agent process's environment
(`BO_CONNECTOR_<KEY>`) and redacts it; reachability is the sandbox egress policy's (10-agent-execution.md). Benchmarks
get only platform `MCP_HTTP` connectors (04-model-evaluation.md).

**Agent questions (ADR 0060).** When the step allows it (config `allowQuestions`, default true; `maxQuestionRounds`,
default 2), the platform preamble tells the agent how to ask. A model answer that replies with only
`{"agentQuestions": [...]}`, or a coding agent that writes `agent-questions.json` (`{"questions": [{"question",
"why"?}]}`, collected as the artefact `agent-questions`, never committed) does not finish the step: `StepQuestions`
turns the result into questions, and the engine routes to the node's `<id>::questions` node (`interruptBefore`, like
`<id>::confirm`), which opens an `ApprovalRequest` of kind `AGENT_QUESTIONS` (options `answer`, `cancel`; questions,
round and limit on the approval; run `waitingOn.kind = AGENT_QUESTIONS`). `answer` runs the step again in the same
execution with the comment as `{{answers}}` (section "Answers of the owner" appended to the prompt; a coding agent
continues its own session); `cancel` fails the step. Over the limit the questions are written to the step log and the
step fails ("the agent asked more than N rounds of questions"). What asking cost is added to the step's final result.
Decisions are audited as `RUN_AGENT_QUESTIONS_ANSWERED`.

`GET /workflows/task-config-schemas` returns the config schema of model answer and coding agent steps (it replaced
`/workflows/task-executors`). The former config key `agent` of a stored step is read as `overrides.agentKey`.

| Action (`ACTION` node) | Implementation | Inputs / outputs |
|---|---|---|
| `FETCH_ISSUE` | Jira REST | produces `issue` |
| `COMMENT_ISSUE` (former `NOTIFY`) | Jira comment, after a write confirmation unless `approval: NOT_REQUIRED` | — |
| `TRANSITION_ISSUE` | Jira transition, after a write confirmation unless `approval: NOT_REQUIRED` | — |
| `START_WORKFLOW` | starts runs of another workflow per entry of a JSON artefact (ADR 0052) | produces `started-runs` |
| `PROJECT_DIGEST` | deterministic project digest (ADR 0052) | produces `digest` |
| `DELIVER_CHANGES` | ADR 0063: runner job `mode = DELIVER` (no agent) in the run directory: commit, push of the run branch, merge request through push options ([Workflow sources and delivery](#step-repositories)) | produces `mr`, `diff` |

A stored `TASK` node naming a former action template (`fetch-issue`, `notify-jira`, `transition-issue`,
`start-workflow`, `project-digest`) is read as the `ACTION` node with the former template defaults, the step's
`config` and its replacement text (`instructionsReplace`, formerly `promptReplace`) as the comment message; the next
save writes the new form.

Model answer, action, decision, gate and CI-wait nodes need no repository; a project without repositories (ADR 0034)
runs workflows made of these. The built-in `llm-task` template (run by a model answer agent, `model-answer` in the
default workflow) is the generic step of such
workflows: its task is the template parameter `task` set per node (ADR 0058; its instructions are `{{params.task}}`,
and a stored replacement still wins); the issue, its parameter `input` ("Input material", bound `START` in the
default workflows) and the revision feedback are standard data sections of the task message (ADR 0062), so changing
the task keeps them.

<a id="run-inputs"></a>
### Parameters and the run start form (ADR 0053, 0062)

Templates declare only **parameters** (`TemplateParameter`: `TEXT`, `MULTILINE`, `NUMBER`, `BOOLEAN`, `CHOICE`,
`ISSUE_KEY`); since ADR 0062 they no longer declare run inputs (the ADR 0053 `runInputs` of a template). A task step
binds each parameter in `TaskOverrides.parameterBindings` (a `ParameterBinding` per name):

| Source | Value at run time |
|---|---|
| `VALUE` | a fixed value set in the designer (the former step `parameters`) |
| `START` | the run parameter of that name, asked in the run start form; label, type, required and help come from the parameter |
| `ARTIFACT` | the content of the latest artefact of the named earlier step output (`artifact`) |

An unbound parameter (or a `START` / `ARTIFACT` binding the run leaves empty) takes the preset value (`parameterValues`
of the nearest template) or the default (`ResolvedParameter.fallback`). `StepParameters` computes the values per run;
the values bound at run time (`START`, `ARTIFACT`) come from outside the design and are untrusted: the template model
lists them under `TemplateRenderer.UNTRUSTED_PARAMS`, so `{{params.<name>}}` renders them as data blocks (ADR 0029),
while design-time values are trusted like the instructions. An `ARTIFACT` value is also guard-checked like a consumed
artefact.

A workflow's start form (`GET /workflows/{id}/run-inputs`) is the union of the parameters its task steps bind `START`
(`TemplateParameter.asRunInput`: `MULTILINE` a text area, `ISSUE_KEY` an issue key, every other type a single line
whose value the start checks against the declaration; a `CHOICE` names its options in the help) and the run inputs of
its action nodes (ADR 0056); the same name across steps is one field (required when any requires it, the first label
wins). The START node declares nothing and shows the list read-only. The values travel as run `parameters` by name;
a `START`-bound `ISSUE_KEY` parameter is the run's `issueKey` unless the request gives one (and reads the run's issue
key when the run parameter is empty).

- **Checked starters:** `POST /workflows/{id}/runs` (the start dialog and API-token calls) refuses a missing or blank
  required input, any declared input longer than 20 000 characters, an `ISSUE_KEY` value that is not shaped like
  `ABC-123` and a value that does not fit a `START`-bound parameter's type (number, true/false, one of the options),
  with a validation problem naming the field. A step whose `issueKeyFrom` names another parameter makes its
  `ISSUE_KEY` input optional.
  The personal intake, the Jira intake watcher, CI and the MCP `start_workflow` tool are not checked.
- **Prompts:** the parameters a step binds `START` and the instructions do not read become standard data sections of
  the task message under their labels. The ADR 0053 roots `runInput` (`{name, label, value}`) and `runInputText`
  (`Label: value` paragraphs) are still in the template model, now filled from the `START` bindings; instructions that
  use them get no extra sections. `{{parameters.*}}` keeps working for custom templates.
- **Design time:** the resolver (`TaskResolver`) reports a required parameter that has no value and is not bound at
  run time, an `ARTIFACT` binding without an artefact name and a `VALUE` for an undeclared parameter (a `START` /
  `ARTIFACT` binding of an undeclared one is a warning and ignored); `WorkflowValidator` warns when a step reads
  `issueKeyFrom` from a run parameter no step binds `START`, and when two steps bind the same name with different
  labels.

The seeded `code-review` template is a model answer task with `consumes: [diff]`, `produces: [review]` and a
schema for `{summary, findings[], verdict, score}`; the verdict policy (blocker → REQUEST_CHANGES,
> 2 majors → REQUEST_CHANGES, score ≥ 80 → APPROVE, else COMMENT) is applied by Back Office, not the model, and stored
in `state.review`.

<a id="workflow-sources"></a><a id="step-repositories"></a>
## Workflow sources and delivery (ADR 0034, 0043, 0063)

A project has zero or more repositories (`key`, `url`, `defaultBranch`, `purpose` DOCS / SOURCE / OTHER,
`description`, optional `primary`; ADR 0034). Since ADR 0063 the repositories a run works with are a setting of the
**workflow**, not of a step:

```json
"sources": [ { "key": "service" }, { "key": "docs" }, { "key": "standards", "library": true } ]
```

`WorkflowDefinition.sources` lists project repositories by key and library sources (ADR 0043) with `library: true`;
they are cloned into the run directory (ADR 0060). Empty = the project's primary repository (a project without
repositories has none: an agent step with `repositoryOptional` runs in an empty directory). `WorkflowSources.plan`
turns them into a `StepRepositoryPlan`, every entry `READ`: library sources are keyed `lib-<key>` in the job; the job's
primary entry is the project's primary repository when it is a source, else the first project source, else the first
library source. Every coding step's agent job gets the same plan; an agent with a `SEPARATE` directory gets a fresh
copy of it. **Agents never push**: a runner job of mode `IMPLEMENT` carries only `READ` entries, and the runner treats
any `PUSH_MR` entry of an agent job as `READ` (defence in depth).

**Delivery.** `DELIVER_CHANGES` (`DeliverChangesExecutor`, an `ActionStrategy`) is the only step that writes back.
Config (`ActionCatalog`): `branchTemplate`, `commitSubjectTemplate`, `mergeRequest` (default true),
`mergeRequestTitleTemplate`, `mergeRequestDescriptionTemplate`, `mergeRequestDraft`, `repositories` (keys of project
repositories among the sources; empty = every project repository of the sources; only those with changes are
committed and pushed). `WorkflowSources.delivery` gives those entries `PUSH_MR` with the job branch as `pushBranch`
and, with `mergeRequest`, the rendered title, description (default `Implements {{issue.key}}.` plus the run trailer),
target = the repository's default branch and the draft flag. The job is a runner job of mode `DELIVER` in the run
directory (`spec.workspace` = the run's, unchanged setup command), no agent, no model, no prompt of an agent; it is
non-blocking like an agent job (`RUN_ADVANCE` continuation). Credentials: a project repository token with
`PUSH_BRANCH` (ADR 0051), else the runner pool's push token (deprecated). Branch: `branchTemplate`, else the run's
branch, else `feature/{{issue.key | lower}}`; a branch equal to a delivered repository's default branch, `HEAD` or a
`refs/` path falls back to `run/<run id8>`, and the runner refuses such refspecs before cloning (ADR 0031). The result
maps to `mr` (the primary repository's merge request, else the first opened; also `state.mr`), `diff` (primary
repository, after the commit), `state.branch` and `NodeExecution.repositories`; ports `success` / `failure` (a failed
commit or push in any repository); a delivery with nothing to commit succeeds with the warning "nothing to deliver".

`WorkflowValidator` (save and `POST /workflows/validate`) and `StartRun` (the project may have changed after the
workflow was saved) check, through `RepositoryStepCheck`, when the workflow has a coding step or a delivery:

| Severity | Rule |
|---|---|
| error | a coding step in a project without repositories (unless `repositoryOptional`) |
| error | no primary repository and no project repository among the sources |
| error | unknown project repository key (the message lists the project's keys) or unknown library source |
| error | the same source twice |
| error | a project repository whose key clashes with a library source's job key |
| error | a delivery whose `repositories` names a library source or a key that is not a project source |
| error | a delivery with no project repository to deliver to |
| error | a delivery while every coding step's agent works in a `SEPARATE` directory (nothing to deliver) |
| warning | a delivery that pushes to a `DOCS` and a `SOURCE` repository (one merge request per repository) |
| warning | a delivery reachable from a decision on model output without a human gate (`modelRoutedPushes`, [12-prompt-injection.md](12-prompt-injection.md)) |

A run whose sources or deliveries have errors is refused at start; the message names the step or "sources of the
workflow".

**Job building** (`RunnerJobs`, `RunnerTaskSupport`): every planned repository becomes a `JobRepository` entry of
`RunnerJobSpec.repositories` (`key`, `url`, `baseBranch` = the repository's default branch, `access`, `path` = `key`,
`pushBranch` and `mergeRequest` for `PUSH_MR` in a `DELIVER` job only, `primary`). For older runners the legacy
`repository` is filled from the primary entry and `gitAccess` is the highest access among the entries (`READ` for
every agent job). Workspace layout and the runner side:
[10-agent-execution.md](10-agent-execution.md#repositories-and-workspace-adr-0034).

**Prompt.** The coding agent's prompt lists the repositories (key, path, access, purpose, base branch);
descriptions are untrusted text and are written inside data blocks (ADR 0029).

**Result mapping.** `RunnerResult.repositories[]` (`key`, `branch`, `commitSha`, `diffStat`,
`mergeRequestUrl`, `pushed`, `error`) becomes `NodeExecution.repositories` (enriched with url, purpose, access
and primary from the spec) and updates `WorkflowRun.repositories` (latest result per key). A result without the
list (older runner) is read from the legacy top-level fields as the result of the primary repository.
`mr` / `run.mergeRequest` = the delivery's merge request of the primary repository, else the first one opened;
decisions and templates written for one repository keep working.

**Template model.** `project.repositories` (list), `project.repository` (primary, may be null),
`project.defaultBranch` / `project.repositoryUrl` (primary, may be empty, kept for compatibility), `repos`
(this job's repositories: key, url, path, access, purpose, baseBranch, primary) and `repositoryResults`
(key -> latest result: branch, commitSha, mergeRequestUrl, pushed).

**Workflows stored before ADR 0063.** `V122__workflow_sources_no_spec_kit.sql` (projects range) adds `sources` to
`workflow_definition` and `library_workflow_version` and drops `project.spec_kit_enabled`;
`V309__delivery_is_an_action.sql` (tasks range, runs after V122 because Flyway runs out of order and sorts by version)
rewrites `workflow_definition`, `library_workflow_version` and the definition snapshot of unfinished runs, using the
template lineage while `task_template` still holds the git keys (nearest template first, a project template shadows
the global one):

- a TASK step whose effective `push` is true (its config, else the nearest template that sets it; the built-ins
  `implement-opencode`, `implement-claude-code`, `implement-task-list` had `push: true`) and that is not `prepareOnly`
  gets an `ACTION` node `<step id>-deliver` (`DELIVER_CHANGES`) to its right, carrying the step's git keys (each from
  the step, else from the nearest template; the step's `PUSH_MR` repositories as `repositories`): the step's
  `success` goes to the delivery, the step's former `success` edges start at the delivery's `success`, and the
  delivery's `failure` goes where the step's `failure` went. Steps that pushed only by default (no `push` key
  anywhere, for example Spec Kit phases) get no delivery: their work stays in the run directory for a later one;
- the steps' `repositories` join `sources` (union, first seen first; the primary repository joins when a coding step
  relied on the default next to listed ones; library versions keep library sources only); step `setupCommand`s are
  appended to the workflow's setup command (` && `) unless already part of it;
- steps of `spec-kit-specify` / `spec-kit-plan` / `spec-kit-tasks`, or with `phase` SPECIFY / PLAN / TASKS, name
  `write-spec` / `write-plan` / `write-task-list`;
- `push`, `phase`, `repositories`, `setupCommand` and the git keys leave the step config (`collectArtifacts` stays and
  is read as extra output files);
- in `task_template`, `collectArtifacts` becomes the column `output_files`, a template with a `phase` or `push: true`
  gets `codingAgent: true`, and the same keys leave the config.

`LegacyStepGit` applies the same rules when a workflow is read (library imports, MCP clients, anything V309 did not
rewrite), so a definition with the old keys still runs and the next save writes the new form.

**Seeding.** A project created with at least one repository gets the SDLC workflows (`DefaultWorkflows.all`: Issue
to reviewed MR, CI code review, Change request (delta) and, since ADR 0059, **Reference: one Claude Code session**) and
the `code-review` CI entrypoint. Since ADR 0063 every default workflow that implements has a `DELIVER_CHANGES` node
`implement-deliver` ("Deliver changes", the action's defaults, `maxVisits` 3) between `implement` and what followed it
(the CI wait, or the merge gate in the reference workflow). A project created without a repository gets the starter workflow **LLM task with
review** (`start -> llm-task` (template `llm-task`) `-> review` ("Result ok?", owner accountable): `approve -> done` (End SUCCESS),
`revise -> llm-task`, `reject -> rejected` (End FAILURE)) and no CI entrypoint. When it later gets its first repository, the SDLC workflows
and the `code-review` entrypoint are seeded once (skipped when workflows with those names exist). ADR 0063 removed
the Spec Kit gating of ADR 0036 (the project and platform switches, `/projects/{id}/spec-kit*`): **Issue to reviewed
MR** uses `write-spec`, `write-plan` and `write-task-list` and is seeded like the others. ADR 0059: `GET`
`/projects/{id}/default-workflows` lists the names of the default workflows that a project with a repository lacks,
and `POST` on the same path
("Add missing default workflows", `workflow.edit`, audited `PROJECT_DEFAULT_WORKFLOWS_SEEDED`, 409 without a
repository) seeds them by name; existing workflows with the same name are never replaced. That is how projects created
before the reference workflow get it. ADR 0061: every task step of a default workflow names its agent
(`overrides.agentKey`): `opencode` for the specification, plan, task list and implementation steps, `analyst` for clarify, impact analysis,
user story and task list, `reviewer` for code review, `model-answer` for `llm-task`, `claude-code` in the reference
workflow.

**Steps stored before ADR 0061.** `V307__template_is_only_the_work.sql` (Task Library range, because it must run before
the template columns are dropped) gives every task step of `workflow_definition`, `library_workflow_version` and the
definition snapshot of unfinished runs (`PENDING`, `RUNNING`, `WAITING`) that names no agent (`overrides.agentKey` or
the legacy config key `agent`) the agent its template lineage named (`task_template.agent_key`, nearest first, project
shadowing as in the resolver), and the lineage's `model_profile_id` when the step has no model and its agent has none
(the old order was step, agent, template). Then `task_template.agent_key` and `task_template.model_profile_id` are
dropped.

**Reference workflow (ADR 0059).** "Reference: one Claude Code session" is the yardstick for the cost of an accepted
result: `start -> fetch-issue` (action `FETCH_ISSUE`) `-> implement` (template `agent-task`, agent `claude-code`,
parameter `task`: read the repository, implement the issue end to end, add tests, run the build and the tests,
summarise) `-> implement-deliver` (`DELIVER_CHANGES`, branch and merge request, ADR 0063) `-> merge-gate` ("Merge?", four-eyes, owner accountable, Definition of
Done; `revise -> implement`, `reject -> rejected`) `-> done`. Run the same issues through it and through cheaper
workflows or agents and compare in agent and workflow insights ([11-governance.md](11-governance.md)).

**Who did a step (ADR 0059).** When a `TASK` step runs, its node execution records the agent and model it resolved
to (`NodeExecution.agent`, `StepAgentRecord`: agent key, name, harness, model profile id and label); the run page
shows "template · agent · model" and agent insights aggregate it.

## Workflow library

Workflows can be shared across projects through the Library (ADR 0037). A project workflow is **published**
(`POST /workflows/{id}/publish`, `library.manage` + `workflow.edit`) as a new `LibraryWorkflow` or as the next
immutable version of one; `LibraryPortability` strips the project-local settings (the workflow's project-repository
sources, keeping library sources; an update keeps the copy's own project repositories; a pinned
Jira connection id, named approver users) and publishing requires every TASK node to resolve to a concrete
**global** template that the source project does not shadow. Another project **adopts** a version
(`POST /projects/{id}/workflows/from-library`): an ordinary workflow, validated exactly like a save in the
designer, with `library = LibraryLink(id, key, version, modified=false)`. The copy runs and is edited like any
workflow; saving recomputes `modified`. `POST /workflows/{id}/library/update` replaces the graph with another
library version, re-applies the local settings of nodes with the same id and keeps name, description,
triggers, enabled and viewport (409 for a modified copy unless `discardLocalChanges`); `.../library/detach`
removes the link. A run snapshots the definition, so the persistence adapter copies the link into
`workflow_run.library_workflow_id`, `library_version` and `library_modified`: the workflow metrics
([11-governance.md](11-governance.md#workflow-metrics)) attribute every run to the library version it ran.

## Agent runner protocol

Unchanged from the first iteration except for these additions, all optional:

| Field | Meaning |
|---|---|
| `spec.skills[]` | `{key, name, content}` written to `<agent home>/skills/<key>/SKILL.md` before the agent starts (ADR 0060); without `agentHome` (older Back Office) to `.claude/skills/<key>/SKILL.md` and `.opencode/skills/<key>/SKILL.md` |
| `spec.instructions` | ADR 0057: the environment document; with `agentHome` the home's `CLAUDE.md` (Claude Code user memory, `CLAUDE_CONFIG_DIR` = the home) or `AGENTS.md` listed under `instructions` of the home's `opencode.json` (OpenCode); without it OpenCode `/workspace/.backoffice-agent/AGENTS.md` and Claude Code `$CLAUDE_CONFIG_DIR/CLAUDE.md`; outside every repository, covered by the spec digest |
| `spec.agentHome` | ADR 0060: the agent key; everything of the agent (environment, skills, MCP configuration, sessions) lives in `/workspace/.agents/<agentHome>/`, nothing in a repository; covered by the spec digest |
| `spec.access` | ADR 0060: `{mode, paths}` with mode `READ_ONLY` or `PATHS` (absent = FULL); harness tool rules plus the runner's revert after the agent; the result's `access.reverted[]` lists what was undone; covered by the spec digest |
| `spec.workspace` | ADR 0044: `{name, key, sessionRole, prepareOnly, setupCommand}`; since ADR 0060 the key is always `run` (the run directory); absent for a `SEPARATE` step; a `DELIVER` job reuses it (same name and setup command, so nothing is set up again) |
| `spec.mode` | ADR 0063: `IMPLEMENT` (agent job, never pushes), `DELIVER` (no agent: commit, push and merge request of the run directory), `EVAL_CODING`, `PROMPT`; `SPEC_KIT` is gone and refused by current runners |
| `spec.commitSubject` | ADR 0063: the first line of the delivery's commit message (rendered `commitSubjectTemplate`, sanitised) |
| `spec.connectors[]` | ADR 0064 (replaces ADR 0057 `mcpServers` and ADR 0051 `agentTools`): `{key, kind, url?, auth?, headerName?, repository?}`. `MCP_HTTP`: OpenCode `mcp.<key>` (type `remote`, headers `{env:BO_CONNECTOR_<KEY>}`), Claude Code `mcp.json` in the agent home with `--mcp-config` (headers `${BO_CONNECTOR_<KEY>}`); `GITLAB_READ`: glab with the repository's `CI_READ` token. Invalid or repeated entries are skipped with a warning; a runner still reads an older Back Office's `mcpServers` / `agentTools`. Covered by the spec digest |
| `spec.credentials[]` | ADR 0051, 0064: `{ref, repository, capability, connector?}`, names only; capability `CONNECTOR` names a connector's secret (`ref` = connector id, `connector` = key, delivered as the bundle file `connector-token-<key>`), `CI_READ` the token of a `GITLAB_READ` connector (bundle file `tool-token-<repository>`) |
| `spec.repository.mergeRequest` | legacy single-repository mirror, filled only for a `DELIVER` job: `{create, title, description, targetBranch, draft}` → `git push -o merge_request.create -o merge_request.target=<t> -o merge_request.title=<title> [-o merge_request.draft]`; the runner reads the MR URL GitLab prints on push and returns it as `result.mergeRequestUrl` |
| `spec.collectDiff` | when true the runner returns `git diff <baseRef>...HEAD` (≤ 2 MB) as `result.diff` |
| `spec.repositories[]` | ADR 0034: one `JobRepository` per repository of the job, each cloned into `/workspace/<path>` with its own `access`; `pushBranch` and `mergeRequest` only in a `DELIVER` job (an agent job uses a `PUSH_MR` entry as `READ`); the result carries `result.repositories[]`; see [Workflow sources and delivery](#step-repositories) |

The runner adds a git trailer `Back-Office-Run: <runId>` to every commit it makes (only `DELIVER` jobs commit), which
is how a later `backoffice-ci signal` finds the waiting run without any SCM API.

Environment, callbacks and `RunnerResult` are described in the runner README and in `openapi.yaml` (`RunnerJobSpec`,
`RunnerResult`). The removed fields `specKit`, `specKitPhase` and `constitution` are ignored by current runners.

## CI client contract

See `05-integrations.md` for `backoffice-ci.sh` commands, the CI context payload and exit codes.
