# 03 — Workflows, CI and agents

## What a workflow is

A workflow is a directed graph designed on a canvas (ADR 0014): nodes are blocks, edges connect a
node's **output port** to the next node. Blocks are mostly agent tasks taken from the Task Library
(ADR 0015); decisions, human gates and CI gates are control nodes. A workflow belongs to a project and is
versioned; every run pins the version it started with.

Where things execute (ADR 0013):

| Runs in Back Office | Runs in the project's CI (or any CI) |
|---|---|
| Agent tasks (LLM calls, Spec Kit phases, implementation in the runner), decisions, human gates, Jira tasks | Build, tests, linters, deployment, and calls to Back Office through `backoffice-ci.sh` |
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
START ─▶ fetch-issue ─▶ spec-kit-specify ─▶ clarify-spec ─▶ [DECISION confidence >= 0.8]
                                                   ▲                 │ true            │ false
                                                   │ revise          ▼                 ▼
                                                   └────────── [HUMAN_GATE "Spec ok?"] ◀┘
                                                                     │ approve
                                                                     ▼
                     spec-kit-plan ─▶ spec-kit-tasks ─▶ implement-opencode ─▶ [WAIT_FOR_CI "tests"]
                                                              ▲                     │ success   │ fail
                                                              │ (revise)            ▼           ▼
                     code-review ◀──────────────────────────────────────────────────┘      END(FAILURE)
                          │
                          ▼
              [DECISION review.verdict != 'REQUEST_CHANGES'] ── false ──▶ implement-opencode (loop, max 2)
                          │ true
                          ▼
              [HUMAN_GATE "Merge?"] ── approve ──▶ transition-issue ─▶ END(SUCCESS)
```

The owner is involved at most twice. The implementation task pushes a branch with push options that
open the merge request; the project's CI runs its tests and calls `backoffice-ci signal tests`.

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
  TASK n (external write)     ──► "n::confirm", interruptBefore; resumed by the write decision
  START                       ──► START → first successor
```

**Approval before external writes (ADR 0033).** Every TASK node also gets a synthetic node
`<id>::confirm`, interrupting like `<id>::await`. A Jira write step (`NOTIFY`, `TRANSITION_ISSUE`) with
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

## Task executors

The Projects context resolves a `TASK` node through the `TaskCatalog` port into a `ResolvedTask` and
dispatches on `executor`:

| Executor | Implementation | Inputs / outputs |
|---|---|---|
| `LLM` | one structured call through `LlmGateway`; skills appended to the system prompt in a `<skills>` section; template rendered with `issue`, `artifacts.*`, `feedback`, `project.constitution`, `input.ci.*`; `outputSchema` → `structured`; `confidenceField` → `confidence`; every key in `produces` that matches a structured field or the whole content becomes an artefact | consumes/produces per template |
| `SPEC_KIT` | runner Job, `mode = SPEC_KIT`, phase from config; skills written to the workspace; artefacts `spec`/`plan`/`tasks`/`analysis`; repositories per [Step repositories](#step-repositories) | produces the phase artefact |
| `IMPLEMENT` | runner Job, `mode = IMPLEMENT`; `verifyCommand`, `branchTemplate`, `push`, `mergeRequest` push options; repositories per [Step repositories](#step-repositories); result `diff` becomes artefact `diff`, the primary repository's `mergeRequestUrl` (else the first pushed) → `mr`, per-repository results → `NodeExecution.repositories` | produces `diff`, `mr` |
| `FETCH_ISSUE` | Jira REST | produces `issue` |
| `NOTIFY` | Jira comment, after a write confirmation unless `approval: NOT_REQUIRED` | — |
| `TRANSITION_ISSUE` | Jira transition, after a write confirmation unless `approval: NOT_REQUIRED` | — |

LLM, Jira, decision, gate and CI-wait nodes need no repository; a project without repositories (ADR 0034)
runs workflows made of these. The built-in `llm-task` template (`LLM`; standalone, it does not extend the engineering-oriented `base-agent`) is the generic
step of such workflows: its task (the user prompt) is written per node (override "replace prompt"); its run
input `input` ("Input material") and the revision feedback (`{{feedback}}`, filled on a second visit) are
part of the template's system prompt, so replacing the task keeps them.

### Run inputs

Library tasks declare the run inputs they ask for (`runInputs`, [ADR 0053](../adr/0053-run-inputs-declared-by-tasks.md),
[Task Library](07-task-library.md)). A workflow's start form is the union of the run inputs of its task steps
(`GET /workflows/{id}/run-inputs`: required when any step requires it, the first label wins); the START node
declares nothing and shows the list read-only. The values travel as run `parameters` by name; the value of an
`ISSUE_KEY` field also becomes the run's `issueKey` unless the request gives one.

- **Checked starters:** `POST /workflows/{id}/runs` (the start dialog and API-token calls) refuses a missing or blank
  required input and any declared input longer than 20 000 characters, with a validation problem naming the field.
  The personal intake, the Jira intake watcher, CI and the MCP `start_workflow` tool are not checked.
- **Prompts:** the template model has `runInput` (the current task's declared inputs that have a value, as
  `{name, label, value}`) and `runInputText` (the same as `Label: value` paragraphs). Both are untrusted roots, so
  prompts get them as data blocks. `base-agent`, `llm-task` and `agent-task` render a "Run input" section from
  `runInputText`. `{{parameters.*}}` keeps working for custom templates.
- **Design time:** `WorkflowValidator` warns when a step reads `issueKeyFrom` from a run parameter no task declares,
  and when two tasks declare the same input with different labels.

The seeded `code-review` template is an `LLM` task with `consumes: [diff]`, `produces: [review]` and a
schema for `{summary, findings[], verdict, score}`; the verdict policy (blocker → REQUEST_CHANGES,
> 2 majors → REQUEST_CHANGES, score ≥ 80 → APPROVE, else COMMENT) is applied by the executor and stored
in `state.review`.

<a id="step-repositories"></a>
## Step repositories

A project has zero or more repositories (`key`, `url`, `defaultBranch`, `purpose` DOCS / SOURCE / OTHER,
`description`, optional `primary`; ADR 0034). A runner step (IMPLEMENT, SPEC_KIT) chooses the repositories its
job gets with the node or template config:

```json
"config": { "repositories": [ { "key": "service", "access": "PUSH_MR" },
                              { "key": "docs",    "access": "PUSH_MR" },
                              { "key": "api",     "access": "READ" } ] }
```

Without `repositories` the step uses the **primary** repository with the access it derives from `push`
(IMPLEMENT / SPEC_KIT with `push != false` -> `PUSH_MR`, else `READ`), which is what every workflow written
before ADR 0034 does. A step may read several repositories and push to several; the same job branch
(`branchTemplate`) is pushed to each `PUSH_MR` repository and each gets its own merge request against its
default branch.

`WorkflowValidator` (save and `POST /workflows/validate`) and `StartRun` (the project may have changed after the
workflow was saved) check:

| Severity | Rule |
|---|---|
| error | a runner step in a project without repositories |
| error | no primary repository and no explicit `repositories` on a runner step |
| error | unknown key (the message lists the project's keys) |
| error | the same key twice in one step |
| error | `access` other than `READ` / `PUSH_MR` |
| error | `PUSH_MR` on a step whose `push` is off |
| error | `repositories` on a non-runner step (LLM, Jira, gates, waits) |
| warning | one step pushes to a `DOCS` and a `SOURCE` repository (allowed, made visible) |

A run whose steps have repository errors is refused at start; the message names the node and the rule.

**Job building** (`RunnerTaskSupport`): every chosen repository becomes a `JobRepository` entry of
`RunnerJobSpec.repositories` (`key`, `url`, `baseBranch` = the repository's default branch, `access`, `path` =
`key`, `pushBranch` and `mergeRequest` for `PUSH_MR` only, `primary`). The job's primary entry is the project
primary when the step uses it, else the step's first repository. The job branch must differ from the default
branch of every `PUSH_MR` repository; otherwise `run/<run id>` is used. For older runners the legacy
`repository` is filled from the primary entry and `gitAccess` is the highest access among the entries.
Workspace layout and the runner side: [10-agent-execution.md](10-agent-execution.md#repositories-and-workspace-adr-0034).

**Prompt.** The coding agent's prompt lists the repositories (key, path, access, purpose, base branch);
descriptions are untrusted text and are written inside data blocks (ADR 0029).

**Result mapping.** `RunnerResult.repositories[]` (`key`, `branch`, `commitSha`, `diffStat`,
`mergeRequestUrl`, `pushed`, `error`) becomes `NodeExecution.repositories` (enriched with url, purpose, access
and primary from the spec) and updates `WorkflowRun.repositories` (latest result per key). A result without the
list (older runner) is read from the legacy top-level fields as the result of the primary repository.
`mr` / `run.mergeRequest` = the primary repository's merge request, else the first one pushed; decisions and
templates written for one repository keep working.

**Template model.** `project.repositories` (list), `project.repository` (primary, may be null),
`project.defaultBranch` / `project.repositoryUrl` (primary, may be empty, kept for compatibility), `repos`
(this step's repositories: key, url, path, access, purpose, baseBranch, primary) and `repositoryResults`
(key -> latest result: branch, commitSha, mergeRequestUrl, pushed).

**Seeding.** A project created with at least one repository gets the three SDLC workflows and the
`code-review` CI entrypoint. A project created without a repository gets the starter workflow **LLM task with
review** (`start -> llm-task` (template `llm-task`) `-> review` ("Result ok?", owner accountable): `approve -> done` (End SUCCESS),
`revise -> llm-task`, `reject -> rejected` (End FAILURE)) and no CI entrypoint. When it later gets its first repository, the SDLC workflows
and the `code-review` entrypoint are seeded once (skipped when workflows with those names exist). ADR 0036: the
SDLC workflows that use Spec Kit (**Issue to reviewed MR**) are seeded only while the project's Spec Kit is on
(and the platform allows it); after turning it on, `POST /projects/{id}/spec-kit/workflows` ("Add Spec Kit
workflows") seeds the missing ones. With Spec Kit off, the palette hides `SPEC_KIT` templates, the validator rejects
`SPEC_KIT` steps, run starts refuse them and runner jobs carry `specKit: false`.

## Workflow library

Workflows can be shared across projects through the Library (ADR 0037). A project workflow is **published**
(`POST /workflows/{id}/publish`, `library.manage` + `workflow.edit`) as a new `LibraryWorkflow` or as the next
immutable version of one; `LibraryPortability` strips the project-local settings (step repositories, a pinned
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
| `spec.skills[]` | `{key, name, content}` written to `.claude/skills/<key>/SKILL.md` and `.opencode/skills/<key>/SKILL.md` before the agent starts |
| `spec.repository.mergeRequest` | `{create, title, description, targetBranch, draft}` → `git push -o merge_request.create -o merge_request.target=<t> -o merge_request.title=<title> [-o merge_request.draft]`; the runner reads the MR URL GitLab prints on push and returns it as `result.mergeRequestUrl` |
| `spec.collectDiff` | when true the runner returns `git diff <baseRef>...HEAD` (≤ 2 MB) as `result.diff` |
| `spec.repositories[]` | ADR 0034: one `JobRepository` per repository of the step, each cloned into `/workspace/<path>` with its own `access`, `pushBranch` and `mergeRequest`; the result carries `result.repositories[]`; see [Step repositories](#step-repositories) |

The runner also adds a git trailer `Back-Office-Run: <runId>` to every commit it makes, which is how a
later `backoffice-ci signal` finds the waiting run without any SCM API.

Environment, callbacks, `RunnerResult` and Spec Kit handling are described in the runner README and in
`openapi.yaml` (`RunnerJobSpec`, `RunnerResult`).

## CI client contract

See `05-integrations.md` for `backoffice-ci.sh` commands, the CI context payload and exit codes.
