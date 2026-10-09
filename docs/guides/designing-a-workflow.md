# Designing a workflow

## When to use this guide

You want to turn a delivery process for one project into a workflow that AI agents execute and that
pauses only where you want to decide, or where your CI has to run first. Read this before opening the
workflow designer (or before writing workflow JSON by hand) and whenever a validation error or a
routing surprise sends you back to the canvas. It replaces the iteration-1 "Designing a pipeline"
guide: workflows are graphs now (ADR 0014), tasks come from the Task Library (ADR 0015) and CI is
integrated without any SCM API (ADR 0013).

## Canvas concepts

A workflow is a directed graph edited on a canvas (`WorkflowDefinition`, see
`docs/architecture/02-domain-model.md` and `03-pipelines-and-agents.md`):

| Concept | Meaning |
|---|---|
| **Node** | a block on the canvas: `id` (slug `^[a-z][a-z0-9-]{0,39}$`, unique), `kind`, `name`, `position {x, y}`, `maxVisits` (default 3), optional `timeoutSeconds`, plus one kind-specific section (`task`, `decision`, `gate`, `ci`, `end`, `note`) |
| **Port** | a named output of a node; where the run goes next depends on the port the node left through |
| **Edge** | `from` + `fromPort` -> `to`; one edge per port (`(from, fromPort)` is unique); loops back to earlier nodes are ordinary edges |
| **Trigger** | how runs start: `MANUAL`, `API`, `CI` (through an entrypoint), `JIRA_TRANSITION` |
| **Canvas** | viewport `{zoom, x, y}`; node positions live on the nodes |
| **Version** | every save bumps `version`; runs pin the version they started with (`definitionSnapshot`), so editing never disturbs in-flight work |

`GET /api/v1/workflows/palette?projectId=` returns everything the designer needs: the control nodes
with a JSON Schema per section, the concrete task templates of the Task Library (grouped by interface,
with their ports and a config schema per executor) and the state fields usable in expressions.

## Node kinds and ports

| Kind | Purpose | Section | Output ports |
|---|---|---|---|
| `START` | entry; exactly one per workflow | - | `out` |
| `END` | terminal; its `outcome` becomes the run outcome and the CI gate result | `end: {outcome: SUCCESS \| FAILURE}` | none |
| `TASK` | runs a Task Library template with node overrides | `task: {templateKey, overrides}` | the template's ports, by default `success`, `failure` |
| `DECISION` | routes on boolean expressions over the run state | `decision: {branches: [{id, label, expression}], defaultBranch}` | one port per branch id (+ `defaultBranch` when it is not a branch id) |
| `HUMAN_GATE` | pauses; an approver picks an option in the inbox | `gate: {question, options, showArtifacts, timeoutSeconds, approvers, checklist}` | one port per option (default `approve`, `revise`, `reject`) + `timeout` |
| `WAIT_FOR_CI` | pauses until your CI reports a signal for this run | `ci: {signal, timeoutSeconds}` | `success`, `fail`, `timeout` |
| `NOTE` | sticky note; never executed, cannot be connected | `note: {text}` | none |

Rules about ports:

- A **required port** must be connected when the node is reachable: `success` for tasks, every branch
  for decisions, the first option (`approve`) for gates, `success` for CI waits, `out` for START.
  Validation reports `ports.<name>: port <name> must be connected`.
- An **unconnected `failure`, `fail` or `timeout` port fails the run** (`FAILED`, outcome `FAILURE`,
  retryable). An unconnected `reject` port cancels it. Any other unconnected port that is taken fails
  the run with "port x of node y is not connected"; validation warns about those.
- Ports compare case-insensitively (`approve` == `APPROVE`).

## Tasks and overrides

A `TASK` node names a template key (`fetch-issue`, `spec-kit-specify`, `clarify-spec`, `spec-kit-plan`,
`spec-kit-tasks`, `implement-opencode`, `implement-claude-code`, `code-review`, `analyse-issue`, `llm-task`,
`notify-jira`, `transition-issue`, the SDLC templates, or your own). At run time the node is resolved through the Task
Library (`TaskCatalog.resolve`): inheritance is merged, a project-scoped template with the same key
shadows the global one, and the node's `overrides` are applied last:

| Override | Effect |
|---|---|
| `modelProfileId` | model for this node (else the template's, else the project default; review tasks use the project's review default) |
| `systemPromptAppend`, `promptAppend`, `promptReplace` | extend or replace the resolved prompts |
| `addSkills`, `removeSkills` | skill keys added to / removed from the resolved set |
| `config` | deep-merged into the template config: `phase`, `agent`, `verifyCommand`, `setupCommand`, `branchTemplate`, `push`, `mergeRequest`, `mergeRequestTitleTemplate`, `mergeRequestDraft`, `collectArtifacts`, `repositories` (runner tasks; see [Repositories of a step](#step-repositories)); `issueKeyFrom` (`fetch-issue`); `toStatus`, `failIfMissing` (`transition-issue`); `messageTemplate` (`notify-jira`); `maxInputChars` (LLM tasks) |

Executors decide what runs where: `LLM` tasks are one structured model call in Back Office (skills go
into a `<skills>` section of the system prompt, `outputSchema` asks for JSON, `confidenceField` feeds
`confidence`, every `produces` name becomes an artefact; a task that produces `review` is assessed by
the verdict policy and stored as the run review). `SPEC_KIT` and `IMPLEMENT` tasks run in the agent
runner with the skills written to the workspace; `IMPLEMENT` pushes the branch with merge-request push
options, returns the diff as artefact `diff` and the MR URL as `mr`. `FETCH_ISSUE`, `NOTIFY` and
`TRANSITION_ISSUE` call Jira.

Templates are Mustache-like: `{{issue.summary}}`, `{{artifacts.spec}}` (artefact content),
`{{feedback}}` (all revision comments), `{{project.constitution}}`, `{{mr.url}}`, `{{review.score}}`,
`{{input.ci.mergeRequest.targetBranch}}`, `{{ci.message}}`, `{{decision.comment}}`, `{{run.short}}`,
`{{node.attempt}}`; filters `| lower`, `| upper`, `| trim`. Missing values render empty and add a
warning to the run log.

<a id="template-variables"></a>Template variables (`TemplateModelBuilder`, the same model for prompts, `messageTemplate`,
`branchTemplate` and merge-request titles):

| Variable | Content |
|---|---|
| `issue` | the fetched issue: `key`, `summary`, `description`, `type`, `priority`, `status`, `labels`, `components`, `acceptanceCriteria`, `url` |
| `artifacts.<name>` | content of the run's artefact with that name (latest wins) |
| `feedback` / `feedbackList` | all revision comments joined by a blank line / as a list |
| `project.{key,name,constitution,defaultBranch,repositoryUrl}` | the project; `defaultBranch` / `repositoryUrl` are the primary repository's (empty when there is none, kept for compatibility) |
| `project.repositories`, `project.repository` | all repositories of the project (`key`, `url`, `defaultBranch`, `purpose`, `description`, `primary`) / the primary one (may be null) |
| `repos` | this step's repositories: `key`, `url`, `path`, `access`, `purpose`, `baseBranch`, `primary` |
| `repositoryResults.<key>` | the latest result per repository in this run: `branch`, `commitSha`, `mergeRequestUrl`, `pushed` |
| `mr.{url,branch,targetBranch}` | the merge request of the run (empty before `IMPLEMENT`) |
| `review` | the run review (e.g. `review.score`) |
| `ci` | the last CI signal (e.g. `ci.message`) |
| `decision` | the last human decision (e.g. `decision.comment`) |
| `input` | `input.issueKey`, `input.parameters`, `input.ci` (CI trigger: `input.ci.mergeRequest.targetBranch`, ...) |
| `parameters` | the run parameters (same as `input.parameters`) |
| `run.{id,short,workflow,trigger}` | run id, its short form, workflow name, trigger type |
| `branch` | the working branch (state, else the MR branch) |
| `node.{id,name,attempt}` | the current node and attempt number |
| `confidence`, `lastOutcome`, `lastError` | from the run state |

## Decisions and expressions

A `DECISION` evaluates its branches in order; the first `true` branch is the port taken, else
`defaultBranch`, else the run fails ("no branch matched"). Expressions are Spring Expression Language,
evaluated read-only in a sandbox (no method calls, no type references) against `WorkflowStateView`:

| Root | Fields |
|---|---|
| `input` | `issueKey`, `parameters['name']`, `ci.provider`, `ci.ref`, `ci.commitSha`, `ci.mergeRequest.{iid, sourceBranch, targetBranch, title}`, `ci.hasMergeRequest` |
| `issue` | `key`, `summary`, `description`, `type`, `priority`, `status`, `labels`, `components` (after `fetch-issue`) |
| `artifacts['name']` | `{id, name, kind, sizeBytes}` or `null` |
| `confidence` | last numeric confidence produced by a task, or `null` |
| `lastOutcome` | `SUCCESS` / `FAILURE` of the previous node |
| `review` | `verdict`, `score`, `summary`, `blockers`, `majors`, `findings` (counts) |
| `mr` | `url`, `branch`, `targetBranch` after an implementation task pushed |
| `ci` | last CI signal: `signal`, `status`, `message` |
| `decision` | last human decision: `option`, `comment` |
| `feedback` | list of revision comments |

Every sub-object exists even before it was produced (fields are `null`), so
`review.verdict != 'REQUEST_CHANGES'` is `true` before any review ran and `confidence >= 0.8` is
`false` while `confidence` is `null`. Examples:

```
confidence >= 0.8
review.verdict != 'REQUEST_CHANGES'
review.blockers == 0 and review.score >= 70
artifacts['tasks'] != null and lastOutcome == 'SUCCESS'
input.ci.mergeRequest.targetBranch == 'main'
input.parameters['priority'] == 'high' or issue.priority == 'Highest'
ci.status == 'SUCCESS'
decision.option == 'approve'
artifacts['spec']?.sizeBytes > 2000
```

`POST /api/v1/workflows/expressions/validate {"expression": "..."}` parses an expression and evaluates it
against the empty view, so typos in field names (`confidnce`) and non-boolean results are caught while
you type.

## Human gates

A `HUMAN_GATE` pauses the run **before** the node: an inbox item is created with the rendered
`question`, the last summary and confidence, the artefacts named in `showArtifacts` and the merge
request URL. The option you choose is the port the run leaves through:

- `approve` continues; `revise` (with a comment) appends the comment to `feedback`, resets
  `confidence` and follows the `revise` edge, usually back to an earlier task whose prompt includes
  `{{feedback}}`; `reject` follows the `reject` edge or, when unconnected, cancels the run.
- `editedArtifacts` sent with the decision are applied before the run resumes, so you can fix a spec
  by hand and approve it in one go.
- `timeoutSeconds` (on the gate section or the node) expires the approval after that long and leaves
  through `timeout`; unconnected, that fails the run.

Every gate visit is a new attempt of the node (`nodes[]` on the run); `maxVisits` bounds it.

### Who may decide: approver rules (RACI)

Deciding a gate needs the project permission `run.approve` (project roles `owner`, `maintainer`,
`approver`; docs/architecture/08-identity-and-access.md). A gate can narrow that further with
`gate.approvers`:

| Field | Meaning |
|---|---|
| `projectRoles` | project roles allowed to decide, e.g. `["owner", "approver"]`; empty = anyone with `run.approve`. Holders of `admin.projects.all` pass this rule. |
| `users` | explicit user ids allowed to decide (in addition to the role rule: both must hold when both are set) |
| `accountable` | the **single** accountable project role ("A" in RACI), shown in the inbox. A gate has exactly one accountable role; when `projectRoles` is set it must be one of them (validation error otherwise). |
| `forbidSelfApproval` | four-eyes: the person who started the run cannot decide its gate. CI-triggered runs are started by the CI token, so nobody is excluded. |

The rule is copied onto the approval when the run reaches the gate, so editing the workflow later does
not change who decides an open approval. The inbox lists every approval of the projects you can view
and marks each with `canDecide` and, when false, `cannotDecideReason`; a refused decision returns 409
(403 without `run.approve`) and is audited as `RUN_GATE_DECIDED` with result `DENIED`.

### Definition of Ready / Done: checklists

`gate.checklist` is a list of `{id, text, required}` items (ids are slugs, unique per gate; `required`
defaults to `true`). The approver ticks items in the inbox and sends their ids as `checkedItems` with the
decision. **`approve` needs every required item ticked** (400 otherwise); `revise` and `reject` do not.
Unknown ids are rejected. The decision stores `checkedItems`, the comment and `decidedBy`; the audit
entry `RUN_GATE_DECIDED` records the option, the ticked items and the comment length.

```json
"gate": {
  "question": "{{issue.key}} passed AI review. Merge?",
  "showArtifacts": ["review", "diff"],
  "approvers": { "projectRoles": [], "accountable": "owner", "forbidSelfApproval": true },
  "checklist": [
    { "id": "tests-green", "text": "Tests are green in CI", "required": true },
    { "id": "review-findings-addressed", "text": "Review findings are addressed or answered", "required": true },
    { "id": "spec-updated", "text": "Specification and docs are updated", "required": true }
  ]
}
```

Use a Definition of **Ready** on gates before work starts (spec, impact analysis, task list) and a
Definition of **Done** on gates before merge (ADR 0019).

<a id="step-repositories"></a>
## Repositories of a step

A project has zero or more repositories, each with a `key`, URL, default branch, purpose (`DOCS`, `SOURCE`,
`OTHER`), optional description and at most one `primary` (ADR 0034). Only runner tasks (`IMPLEMENT`,
`SPEC_KIT`) use repositories; LLM, Jira, decision, gate and CI-wait nodes need none, so a project without
repositories can run workflows made of them.

A runner task names its repositories in `config.repositories`, each with the least access it needs:

```json
"overrides": { "config": { "repositories": [
  { "key": "service", "access": "PUSH_MR" },
  { "key": "docs",    "access": "PUSH_MR" },
  { "key": "api",     "access": "READ" } ] } }
```

- Without `repositories` the task uses the **primary** repository: `PUSH_MR` when `push` is on (the
  default), `READ` otherwise. Workflows written for single-repository projects therefore work unchanged.
- `READ` = clone and fetch only, never pushed. `PUSH_MR` = push the job branch and open a merge request
  against that repository's default branch with whitelisted push options; never merge (ADR 0031).
- Workspace: every repository is cloned into `/workspace/<key>`; the agent starts in the job's primary
  repository (the project primary if the step uses it, else the step's first repository). The prompt lists
  the repositories with key, path, access, purpose and base branch.
- The same job branch (`branchTemplate`) is pushed to every `PUSH_MR` repository; a branch that equals the
  default branch of one of them is replaced by `run/<run id>`.

Validation errors: a runner task in a project without repositories; no primary repository and no
`repositories` list; an unknown key (the message lists the project's keys); a key twice; an access other than
`READ` / `PUSH_MR`; `PUSH_MR` while `push` is off; `repositories` on a non-runner node. Warning: one task
pushes to a `DOCS` and a `SOURCE` repository. A run whose tasks have repository errors (for example the
repository was removed from the project after the workflow was saved) does not start.

The run keeps a result per repository (`nodes[].repositories`, `repositories`: branch, commit, diff stat,
merge request, pushed, error); `mr` and `mergeRequest` are the primary repository's merge request, or the first
one pushed.

## CI waits and signals

A `WAIT_FOR_CI` node pauses the run until your CI calls `backoffice-ci signal <name> --status success|fail`
(`docs/guides/ci-integration.md`). Matching: the signal names the run explicitly (`--run`, taken from the
`Back-Office-Run` git trailer that the runner writes into every commit), else the newest waiting run of
the project with the same signal whose pushed branch (`mr.branch`) equals the CI branch, else the only
waiting candidate. `success` and `fail` are the ports; `timeoutSeconds` leaves through `timeout`. A project
member with `run.signal` can unblock a run by hand with `POST /api/v1/runs/{id}/signals`.

Back Office never triggers CI: pushing the branch does. The `implement-*` tasks push with
`-o merge_request.create` so GitLab opens the MR and your pipeline runs.

## Loops and `maxVisits`

Loops are ordinary edges back to an earlier node (`review-ok.changes -> implement`,
`spec-gate.revise -> specify`). Every node counts its visits per run; when a node would start for the
`maxVisits + 1`-th time the run fails with `node x exceeded maxVisits=n` (retryable). Default is 3;
set it per node. Validation lists the nodes on cycles as information, not as an error.

## The default workflows

Three workflows are created for every new project with at least one repository (and can be edited,
duplicated or deleted), together with the CI entrypoint `code-review`; the creator is stamped as
`updatedBy`. Every seeded gate names `owner` as the accountable role.

A project created **without a repository** gets instead one starter workflow, **LLM task with review**
(`MANUAL`, `API`): `start -> llm-task` (template `llm-task`; write the prompt in the node with **User prompt →
Replace**; the run parameter `input` is given to the model as data, revision comments as `{{feedback}}`)
`-> review` ("Result ok?"): `approve -> done` (`END SUCCESS`), `revise -> llm-task`, `reject -> rejected`
(`END FAILURE`). No CI entrypoint is created. When the project gets its first repository, the three
workflows below and the `code-review` entrypoint are added once (skipped when workflows with those names
already exist).

**Issue to reviewed MR** (`MANUAL`, `API`): `start -> fetch-issue -> specify -> clarify -> confident?`.
`clarify` (template `clarify-spec`) returns `{confidence, questions}`; the decision takes `yes`
(`confidence >= 0.8`) straight to `plan`, otherwise `no` to the `spec-gate` ("Spec ok?"): `approve ->
plan`, `revise -> specify`, `reject` cancels; its Definition of Ready: acceptance criteria are testable,
open questions are answered or deferred with an owner, scope is stated. Then `plan -> tasks -> implement -> ci-tests`
(`WAIT_FOR_CI` on signal `tests`, 2 h timeout): `success -> code-review`, `fail -> tests-failed`
(`END FAILURE`). `code-review -> review-ok?` (`review.verdict != 'REQUEST_CHANGES'`): `approved ->
merge-gate`, `changes -> implement` (rework loop, `implement` and `code-review` allow 3 visits).
`merge-gate` ("Merge?"): `approve -> transition` (Jira "In Review") `-> done` (`END SUCCESS`),
`revise -> implement`; it is a four-eyes gate (`forbidSelfApproval`) with the Definition of Done "tests
green in CI / review findings addressed / spec updated". People are involved at most twice.

**CI code review** (`CI`, `MANUAL`), registered as entrypoint `code-review` in `BLOCKING` mode:
`start -> code-review -> verdict?` with `approved -> passed` (`END SUCCESS`) and `changes -> failed`
(`END FAILURE`). The CI job uploads the MR diff, which the `code-review` task reads as
`{{artifacts.diff}}`; the END outcome becomes the gate result (`PASS`/`FAIL`, always `PASS` in
`ADVISORY` mode).

**Change request (delta)** (`MANUAL`, `API`; ADR 0019) for changing an existing system:
`start -> impact-analysis` (template `impact-analysis`) `-> impact-gate` ("Approve impact analysis?",
checklist: affected artefacts listed / estimate given / options compared; `revise -> impact-analysis`)
`-> user-story` (`write-user-story`) `-> plan-tasks` (`plan-task-list`) `-> task-list-gate`
("Approve task list?", checklist: every task has verification and test_command / the last task is
reformat, lint, and test; `revise -> plan-tasks`) `-> implement` (`implement-task-list`, TDD, stops on a
red verification) `-> ci-tests` (`WAIT_FOR_CI tests`: `fail -> tests-failed`) `-> code-review -> verdict?`
(`changes -> implement`) `-> merge-gate` (four-eyes, owner accountable, Definition of Done as above)
`-> done`. `reject` on any gate cancels the run.

## Example: workflow JSON

The body accepted by `POST /api/v1/projects/{projectId}/workflows` and `PUT /api/v1/workflows/{id}`
(`WorkflowDefinitionInput`); this is the seeded "CI code review":

```json
{
  "name": "CI code review",
  "description": "Reviews the diff uploaded by the CI job; the END outcome decides the CI gate result.",
  "nodes": [
    { "id": "start", "kind": "START", "name": "Start", "position": { "x": 0, "y": 200 } },
    { "id": "code-review", "kind": "TASK", "name": "AI code review", "position": { "x": 200, "y": 200 }, "maxVisits": 3,
      "task": { "templateKey": "code-review", "overrides": { "addSkills": ["security-review-checklist"], "config": {} } } },
    { "id": "verdict", "kind": "DECISION", "name": "Review ok?", "position": { "x": 400, "y": 200 },
      "decision": { "branches": [ { "id": "approved", "label": "no changes requested", "expression": "review.verdict != 'REQUEST_CHANGES'" } ],
                    "defaultBranch": "changes" } },
    { "id": "passed", "kind": "END", "name": "Passed", "position": { "x": 600, "y": 100 }, "end": { "outcome": "SUCCESS" } },
    { "id": "failed", "kind": "END", "name": "Changes requested", "position": { "x": 600, "y": 300 }, "end": { "outcome": "FAILURE" } }
  ],
  "edges": [
    { "id": "e1", "from": "start", "fromPort": "out", "to": "code-review" },
    { "id": "e2", "from": "code-review", "fromPort": "success", "to": "verdict" },
    { "id": "e3", "from": "verdict", "fromPort": "approved", "to": "passed" },
    { "id": "e4", "from": "verdict", "fromPort": "changes", "to": "failed" }
  ],
  "triggers": [ { "type": "CI" }, { "type": "MANUAL" } ],
  "enabled": true,
  "canvas": { "zoom": 1, "x": 0, "y": 0 }
}
```

A gate and a CI wait look like this inside `nodes`:

```json
{ "id": "spec-gate", "kind": "HUMAN_GATE", "name": "Spec ok?", "position": { "x": 800, "y": 400 },
  "gate": { "question": "Spec for {{issue.key}} has open questions (confidence {{confidence}}). Approve, revise or reject?",
            "options": ["approve", "revise", "reject"], "showArtifacts": ["spec", "clarifications"], "timeoutSeconds": null } },
{ "id": "ci-tests", "kind": "WAIT_FOR_CI", "name": "Wait for CI tests", "position": { "x": 1600, "y": 200 },
  "ci": { "signal": "tests", "timeoutSeconds": 7200 } }
```

Who changed a workflow is visible as `updatedBy` on the definition and in the audit log (`WORKFLOW_CREATE`,
`WORKFLOW_UPDATE` with the old/new name, enabled flag, version and node count, `WORKFLOW_DELETE`,
`WORKFLOW_DUPLICATE`). Editing needs `workflow.edit`, starting and retrying `run.start`, cancelling
`run.cancel`; validation and the palette need `project.view`.

Validate without saving with `POST /api/v1/workflows/validate?projectId=`; the response lists errors
and warnings as `{nodeId, edgeId, field, message}`. Start a run with
`POST /api/v1/workflows/{workflowId}/runs {"issueKey": "SHOP-123"}` (you become the run's `startedBy`); `GET /api/v1/runs/{id}` shows
`nodes[]` with the `port` each node left through, so the UI can replay the path on the same canvas.

## Where to look when it fails

| Symptom | Look at |
|---|---|
| `400` on save with `nodes.<id>.<field>` | the section table above; `POST /workflows/validate` gives the full list including port problems |
| `unknown task template x` | the Task Library (`GET /api/v1/task-templates`); project-scoped templates only resolve for their project |
| Run stops with `FAILED` and `failure.nodeId` | `GET /runs/{id}` (`nodes[].error`, `nodes[].port`) and `GET /runs/{id}/logs?nodeId=...`; fix and `POST /runs/{id}/retry` |
| `port x of node y is not connected` | the node left through a port without an edge; connect it or accept the failure semantics |
| `node x exceeded maxVisits` | a loop did not converge; raise `maxVisits` or add a gate on the loop |
| A decision always takes the default branch | `POST /workflows/expressions/validate`; check the state with `GET /runs/{id}` (`review`, `mergeRequest`, node confidence) |
| Prompt contains empty spots | template warnings in the run log (`missing value for {{...}}`); artefact names are case sensitive |
| Run waits for CI forever | `docs/runbooks/stuck-pipeline-run.md` section 4 and `docs/guides/ci-integration.md` (signals) |
| Nothing happens after approval | `docs/runbooks/stuck-pipeline-run.md` |
| Validation or run start reports an unknown repository key or a missing primary repository | the project's repositories changed; pick keys from the list or mark a repository primary ([Repositories of a step](#step-repositories)) |
| Runner tasks fail immediately | `docs/runbooks/runner-job-failed.md` and `GET /api/v1/settings/integrations` (runner mode) |
