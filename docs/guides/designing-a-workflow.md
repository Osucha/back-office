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
with a JSON Schema per section, the actions (`actions`: config schema, defaults, run inputs, ports and required connection capabilities of
each, ADR 0056), the concrete task templates of the Task Library (grouped by category, with `needsCodingAgent`, `requiresSkills`,
ports and config schema; ADR 0061: no agent, the step chooses it) and the state fields usable in expressions.

## Node kinds and ports

| Kind | Purpose | Section | Output ports |
|---|---|---|---|
| `START` | entry; exactly one per workflow | - | `out` |
| `END` | terminal; its `outcome` becomes the run outcome and the CI gate result | `end: {outcome: SUCCESS \| FAILURE}` | none |
| `TASK` | runs a Task Library template with node overrides, by the step's agent | `task: {templateKey, overrides}` | `success`, `failure` |
| `ACTION` | a fixed operation without a model (ADR 0056) | `action: {type, config}` with `type` `FETCH_ISSUE`, `COMMENT_ISSUE`, `TRANSITION_ISSUE`, `DELIVER_CHANGES` ([Deliver changes](#deliver-changes)), `START_WORKFLOW` or `PROJECT_DIGEST` | `success`, `failure` |
| `DECISION` | routes on boolean expressions over the run state | `decision: {branches: [{id, label, expression}], defaultBranch}` | one port per branch id (+ `defaultBranch` when it is not a branch id) |
| `HUMAN_GATE` | pauses; an approver picks an option in the inbox | `gate: {question, options, showArtifacts, timeoutSeconds, approvers, checklist}` | one port per option (default `approve`, `revise`, `reject`) + `timeout` |
| `WAIT_FOR_CI` | pauses until your CI reports a signal for this run | `ci: {signal, timeoutSeconds}` | `success`, `fail`, `timeout` |
| `NOTE` | sticky note; never executed, cannot be connected | `note: {text}` | none |

Rules about ports:

- A **required port** must be connected when the node is reachable: `success` for tasks and actions, every branch
  for decisions, the first option (`approve`) for gates, `success` for CI waits, `out` for START.
  Validation reports `ports.<name>: port <name> must be connected`.
- An **unconnected `failure`, `fail` or `timeout` port fails the run** (`FAILED`, outcome `FAILURE`,
  retryable). An unconnected `reject` port cancels it. Any other unconnected port that is taken fails
  the run with "port x of node y is not connected"; validation warns about those.
- Ports compare case-insensitively (`approve` == `APPROVE`).

## Tasks and overrides

A `TASK` node names a template key (`write-spec`, `clarify-spec`, `write-plan`, `write-task-list`,
`implement-opencode`, `code-review`, `llm-task`, `agent-task`, the SDLC templates, or your own). At run time the node is resolved through the Task
Library (`TaskCatalog.resolve`): inheritance is merged, a project-scoped template with the same key
shadows the global one, and the node's `overrides` are applied last. A step is a template (what to do), an agent (who
works and how) and where the template's parameters get their values (ADR 0057, 0058, 0062):

| Override | Effect |
|---|---|
| `agentKey` | the agent that runs this node (`model-answer`, `analyst`, `reviewer`, `opencode`, `claude-code` or a library agent; ADR 0056). Templates name no agent (ADR 0061): the designer pre-selects one when a template is dropped, and empty means `opencode` when the template needs a coding agent, else `model-answer`. With it come its instructions, rules, skills, MCP servers and access (ADR 0060) |
| `parameterBindings` | per parameter of the template (`{{params.<name>}}`), where its value comes from (ADR 0062; designer **Parameters** → **Where the value comes from**): `{"source": "VALUE", "value": ...}` (**Value**, a fixed value), `{"source": "START"}` (**Ask at start**: the run start form asks for it with the parameter's label, type and required flag; an `ISSUE_KEY` parameter is the run's issue key) or `{"source": "ARTIFACT", "artifact": "spec"}` (**Result of an earlier step**: the content of that artefact). Unbound (or bound but empty in the run) = the nearest template's value, else the default; a required parameter without a value or a run-time binding is a validation error. Values from the run are untrusted and reach the prompt as data blocks. The former `parameters` map is read as `VALUE` bindings |
| `modelProfileId` | model for this node (else the agent's, else the defaults: project, starting user, platform; review tasks use the project's review default; templates have no model since ADR 0061) |
| `instructionsAppend`, `instructionsReplace` | designer **Instructions for this step**: **Append** adds notes after the template's instructions, **Replace (advanced)** uses this text instead (it wins over a parameterised instruction); the platform still adds the standard data sections (ADR 0062). The former `systemPromptAppend` / `promptAppend` are read as the append, `promptReplace` as the replacement |
| `config` | deep-merged into the template config: `codingAgent` (**Works in the repository (needs a coding agent)**), `verifyCommand` (**Verify command**), `agentTools` (coding agent steps); `allowQuestions`, `maxQuestionRounds` (agent steps, [Agent questions](#agent-questions)); `maxInputChars` (model answer steps). Since ADR 0060 a node no longer adds or removes skills (`addSkills` / `removeSkills` of stored workflows are ignored) and `workspace` / `sessionRole` are only read compatibly; since ADR 0062 the keys `directory`, `session` and `continueSession` are the agent's ([Run directory and agent sessions](#agent-sessions)) and are dropped from stored steps. The former key `agent` is gone: a stored `config.agent` is read as `agentKey`. Since ADR 0063 a step has no git
settings, repositories, setup command or Spec Kit phase: `push`, `branchTemplate`, `commitSubjectTemplate`,
`mergeRequest*`, `repositories`, `setupCommand` and `phase` are read compatibly ([Workflows stored before ADR
0063](#before-adr-0063)) and `collectArtifacts` is read as extra output files. Action settings (`issueKeyFrom`, `toStatus`, `failIfMissing`, `messageTemplate`, `approval`, `approvers`, `jiraConnection`, ...) live in `action.config` |

The step's agent decides what runs where (ADR 0056; the agent is the step's `agentKey`, else `opencode` when the
template needs a coding agent, else `model-answer`, ADR 0061) and brings the step's environment (ADR 0057): the platform preamble, the agent's instructions, the
project constitution and the rules. A **model answer** step is one structured model call in Back Office (the
environment opens the system prompt, skills go into a `<skills>` section after it, the task message (below) is the
user message,
`outputSchema` asks for JSON, `confidenceField` feeds `confidence`, every `produces` name becomes an artefact; a task
that produces `review` is assessed by the verdict policy and stored as the run review). A **coding agent** step
(OpenCode or Claude Code) runs in the agent runner with the environment written outside the repository (OpenCode
`AGENTS.md`, Claude Code user `CLAUDE.md`, both in the agent's home `/workspace/.agents/<agent key>/`, ADR 0060), the skills
written to the same home (never into a repository) and the agent's MCP servers
configured, as an agent job in the run directory with the workflow's [sources](#workflow-sources). **Agents never
push** (ADR 0063): the job changes files, returns the diff of the work tree against the base as artefact `diff` and
the template's **Output files** as artefacts (without a repository its final answer is the `result` artefact); a
[Deliver changes](#deliver-changes) node commits, pushes and opens the merge request. With an `outputSchema` a coding
agent writes its structured answer to `structured-answer.json`, which is validated like a model answer. A template
that needs a coding agent (`codingAgent: true`, `prepareOnly`) with a model answer is a problem and the step cannot
run. The actions `FETCH_ISSUE`, `COMMENT_ISSUE` and `TRANSITION_ISSUE` call Jira; `DELIVER_CHANGES` delivers the run
directory to Git; `START_WORKFLOW` and `PROJECT_DIGEST` are Back Office operations. A stored `TASK` node naming a former action template (`fetch-issue`, `notify-jira`,
`transition-issue`, `start-workflow`, `project-digest`) is read as that action.

<a id="task-message"></a>**What the agent is told (ADR 0062).** A template has one text, its **Instructions**: what to
do and the acceptance criteria. There is no system and user prompt any more: the frame (who works, which rules) is the
agent's environment, and coding agents got both fields as one prompt anyway. The platform composes the call: the
system part is the agent's environment and the answer format (output schema, how to ask questions); the message is the
instructions (with the step's **Append** or **Replace (advanced)** applied), then the standard data sections, each
only when it has content and the instructions do not place it themselves: the issue (`{{issue.*}}`), the artefacts the
task consumes (`{{artifacts.<name>}}`), the parameter values bound **Ask at start** that the instructions do not read
(`{{params.<name>}}`, under the parameter's label), the reviewer's feedback (`{{feedback}}`) and the owner's answers
(`{{answers}}`); a consumed diff comes last. So a template rarely needs placeholders for data: write what to do, and
place a value yourself only where its position matters.

<a id="start-form"></a>**The run start form (ADR 0053, 0062).** Templates do not declare run inputs. The start form of
a workflow is the union of the parameters its steps bind **Ask at start** and the run inputs of its action nodes (for
example the issue key of **Fetch issue**); the same name in two steps is one field (required when any step requires
it, the first label wins, the designer warns about differing labels). The **Start** node lists the fields read-only;
change them on the steps. `GET /workflows/{id}/run-inputs` returns them. The default workflows bind the built-in
parameter `input` ("Input material") and, on **Plan**, `techHints` ("Technical hints"), so their start forms are the
same as before; workflows stored earlier were migrated the same way (V308: every former run input of a step's
template became an **Ask at start** binding).

<a id="agent-audit"></a>**Agents in this workflow (ADR 0061).** Choosing agents is part of designing the workflow. When
you drop a template, the designer pre-selects an agent of the right kind (a coding agent for a template tagged
"Needs a coding agent", i.e. `codingAgent: true` or `prepareOnly`; otherwise model answer agents first)
that covers most of the template's `requiresSkills`; ties go to the platform default of the kind (`opencode` /
`model-answer`), then built-in, then name. Before a run, open **Agents in this workflow** in the header (its badge
counts the problems): `POST /workflows/agent-audit` lists per agent its steps, rules, skills, the required skills and
the missing ones, and findings (missing skill, a coding-agent template on a model answer agent, unknown
rule/skill/agent, two skills with the same name; a read-only agent is no longer a finding, agents never push). **Add to agent** adds a missing skill once
(a global agent is copied into the project with the same key first). **Check coherence** asks the platform default
model to read one agent's whole environment, with the repository's `AGENTS.md` / `CLAUDE.md` when readable, and
list contradictions, ambiguities and redundancies with quotes; it costs one model call and answers `409` without a
default model. When sources still disagree at run time, the preamble's order applies: constitution, rules (repository
`AGENTS.md` / `CLAUDE.md`, project, agent), skills, task; otherwise the agent asks. Workflows stored before ADR 0061
had the agent and model of their templates written into their steps by the upgrade (V307), so they run as before.

Templates are Mustache-like: `{{issue.summary}}`, `{{artifacts.spec}}` (artefact content),
`{{feedback}}` (the revision comments for this step), `{{project.constitution}}`, `{{mr.url}}`, `{{review.score}}`,
`{{input.ci.mergeRequest.targetBranch}}`, `{{ci.message}}`, `{{decision.comment}}`, `{{run.short}}`,
`{{node.attempt}}`, `{{params.audience}}`; filters `| lower`, `| upper`, `| trim`. Missing values render empty and
add a warning to the run log. Sections `{{#path}}...{{/path}}` keep their text only when the value is present
(not empty), `{{^path}}...{{/path}}` only when it is absent.

<a id="agent-sessions"></a><a id="run-directory"></a>**Run directory and agent sessions (ADR 0060, 0062).** Every run has
one run directory: coding steps whose agent has **Directory** **Run directory (shared)** (`access.directory: RUN`,
default) work in it one after another and see what earlier steps left, uncommitted work included; an agent with
**Separate directory** (`SEPARATE`) gets a fresh copy of the workflow's sources, thrown away after the step
(isolation; no session continuation; its changes are never delivered). The workflow's `setupCommand`
(`WorkflowDefinitionInput.setupCommand`, designer: workflow properties → **Setup command**, next to **Sources**) runs
once per run directory before the first coding step, again when it changes; it is the only setup command (steps have
none since ADR 0063). The agent's **Session** (`session`, ADR 0062): **Per step (repeats continue)** (`PER_STEP`, default;
repeated executions of a step, such as rework loops or answered questions, continue its conversation), **One per run**
(`PER_RUN`; all steps of this agent in the run are one conversation) or **Always fresh** (`FRESH`). Both are set on the
agent (**Library** → **Agents**), not on the step: a session is the agent's conversation, and the session lives in the
agent's home (`/workspace/.agents/<agent key>/`); Claude Code resumes with `--resume`, OpenCode with `--continue`.
Typical use: *implement* → *fix the review findings* with one agent whose session is **One per run**, so it keeps its
context without re-reading the repository; for an independent reviewer, use another agent (or one with **Separate
directory**). Stored workflows: `workspace` is ignored; the step keys `directory`, `session` and `continueSession` of
ADR 0060 are dropped (V308), so such steps follow their agent's settings; a legacy `sessionRole` keeps a shared
session while the agent stays **Per step**; the setup command and sources of the former project workspaces were moved
to the workflows and steps by migration V121 (and the step repositories to the workflow's sources by V309).

<a id="agent-questions"></a>**Agent questions (ADR 0060).** An agent that cannot do its step well without the owner's
answers stops with questions: a coding agent writes `agent-questions.json` (`{"questions": [{"question", "why"?}]}`)
in its working directory, a model answer replies with only `{"agentQuestions": [...]}`. The run waits on an approval of
kind `AGENT_QUESTIONS` (**The agent asks** in **Your turn**); `answer` runs the step again with the answers appended to
its prompt (`{{answers}}`, section "Answers of the owner"; a coding agent continues its own session), `cancel` fails
the step. Config `allowQuestions` (default `true`; `false` tells the agent it cannot ask) and `maxQuestionRounds`
(default 2): an agent that asks after the last round fails the step, its questions go to the step log. CI-triggered
runs keep questions on and wait like at a gate.

<a id="template-variables"></a>Template variables (`TemplateModelBuilder`, the same model for prompts, `messageTemplate`,
and the delivery's `branchTemplate`, `commitSubjectTemplate` and merge-request title and description):

| Variable | Content |
|---|---|
| `issue` | the fetched issue: `key`, `summary`, `description`, `type`, `priority`, `status`, `labels`, `components`, `acceptanceCriteria`, `url` |
| `artifacts.<name>` | content of the run's artefact with that name (latest wins) |
| `feedback` / `feedbackList` | the revision comments for this step, joined by a blank line / as a list: a gate's `revise` comment goes to the next task step that completes and stays with that step for its later attempts; later steps do not get it |
| `feedbackHistory` | every revision comment of the run, joined by a blank line |
| `answers` | ADR 0060: the owner's answers to this step's agent questions; appended to the prompt automatically under "Answers of the owner" after an answer, empty before |
| `project.{key,name,constitution,defaultBranch,repositoryUrl}` | the project; `defaultBranch` / `repositoryUrl` are the primary repository's (empty when there is none, kept for compatibility) |
| `project.repositories`, `project.repository` | all repositories of the project (`key`, `url`, `defaultBranch`, `purpose`, `description`, `primary`) / the primary one (may be null) |
| `repos` | this step's repositories: `key`, `url`, `path`, `access`, `purpose`, `baseBranch`, `primary` |
| `repositoryResults.<key>` | the latest result per repository in this run: `branch`, `commitSha`, `mergeRequestUrl`, `pushed` |
| `mr.{url,branch,targetBranch}` | the merge request of the run (empty before a step pushed and opened one) |
| `review` | the run review (e.g. `review.score`) |
| `ci` | the last CI signal (e.g. `ci.message`) |
| `decision` | the last human decision (e.g. `decision.comment`) |
| `input` | `input.issueKey`, `input.parameters`, `input.ci` (CI trigger: `input.ci.mergeRequest.targetBranch`, ...) |
| `parameters` | the run parameters (same as `input.parameters`) |
| `params.<name>` | the step's template parameter values (ADR 0058, 0062): a **Value**, a preset or the default is trusted like the instructions; a value bound **Ask at start** or **Result of an earlier step** comes from the run and is rendered as a data block |
| `runInput`, `runInputText` | ADR 0053 form of the values bound **Ask at start** (`{name, label, value}` / `Label: value` paragraphs), untrusted; instructions that use them get no extra start-value sections |
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
| `issue` | `key`, `summary`, `description`, `type`, `priority`, `status`, `labels`, `components` (after the **Fetch issue** action) |
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

<a id="workflow-sources"></a><a id="step-repositories"></a>
## Workflow sources

A project has zero or more repositories, each with a `key`, URL, default branch, purpose (`DOCS`, `SOURCE`,
`OTHER`), optional description and at most one `primary` (ADR 0034). Since ADR 0063 the repositories a run works with
are a setting of the **workflow** (designer: workflow properties → **Sources**, next to **Setup command**), not of a
step:

```json
"sources": [ { "key": "service" }, { "key": "docs" }, { "key": "standards", "library": true } ]
```

- Project repositories by key, [library sources](task-library.md) with `"library": true` (cloned read only into
  `/workspace/lib-<key>`). Empty = the project's primary repository, so workflows written for single-repository
  projects work unchanged.
- Every source is cloned once into the run directory (`/workspace/<key>`); every coding step works in these clones
  and reads them with a read-only token. An agent with `access.directory: SEPARATE` gets a fresh copy of the same
  sources. The agent starts in the job's primary repository (the project primary when it is a source, else the first
  project source); the prompt lists the repositories with key, path, access, purpose and base branch.
- Agents never push. Only a [Deliver changes](#deliver-changes) node writes back, and only to project repositories.
- Model answer, action, decision, gate and CI-wait nodes need no repository, so a project without repositories can run
  workflows made of them.

Validation errors (they matter when the workflow has a coding step or a delivery): a coding step in a project without
repositories; no primary repository and no project repository among the sources; an unknown repository key (the
message lists the project's keys) or library source; a source listed twice; a project repository whose key clashes
with a library source. A run whose sources have errors (for example the repository was removed from the project after
the workflow was saved) does not start.

<a id="deliver-changes"></a>
## Deliver changes

`DELIVER_CHANGES` ("Deliver changes", ADR 0063) is the action that hands the agents' work to Git: in the run directory
it commits what the agents changed, pushes a branch and optionally opens a merge request. It is the only step with push
rights; put it where the workflow should deliver (after the implementation, or after the review so that only reviewed
work leaves the run).

```json
{ "id": "implement-deliver", "kind": "ACTION", "name": "Deliver changes",
  "action": { "type": "DELIVER_CHANGES", "config": {
    "branchTemplate": "feature/{{issue.key | lower}}",
    "commitSubjectTemplate": "{{issue.key}}: {{issue.summary}}",
    "mergeRequest": true,
    "mergeRequestTitleTemplate": "{{issue.key}}: {{issue.summary}}",
    "mergeRequestDescriptionTemplate": "{{artifacts.review}}",
    "mergeRequestDraft": false,
    "repositories": [] } } }
```

| Config (designer label) | Meaning |
|---|---|
| `branchTemplate` (**Branch**) | the branch pushed for the run; empty: the run's branch, else `feature/{{issue.key \| lower}}`. Never the default branch: a branch equal to a delivered repository's default branch, `HEAD` or `refs/...` becomes `run/<run id>`, and the runner refuses such a refspec before cloning |
| `commitSubjectTemplate` (**Commit subject**) | first line of the commit message; empty: `{{issue.key}}: {{issue.summary}}`. The commit ends with the trailer `Back-Office-Run: <run id>` |
| `mergeRequest` (**Open a merge request**) | default `true`: GitLab push options open the merge request against the default branch; Back Office never merges |
| `mergeRequestTitleTemplate`, `mergeRequestDescriptionTemplate`, `mergeRequestDraft` (**Merge request title**, **Merge request description**, **Draft**) | the merge request; title default `{{issue.key}}: {{issue.summary}}` |
| `repositories` (**Repositories to deliver**) | keys of project repositories among the sources; empty (**Every changed repository**) = every project source the agents changed; library sources are never written to |

It runs as a runner job of mode `DELIVER` without an agent and without any repository command, in the run directory.
Per delivered repository with changes it commits every changed and new file (`.gitignore` honoured; the runner's
ephemeral `work-log.md`, `structured-answer.json` and `agent-questions.json` are never committed), pushes the branch
(the same name in every repository, one merge request each) and records the result. Credentials: the project's
repository token with `PUSH_BRANCH` (ADR 0051), else the runner pool's push token. Ports `success` / `failure` (a
commit or push failed; one failing repository does not stop the others); it produces `mr` (the run's merge request,
`{{mr.url}}`) and `diff`. Nothing to commit is a success with the warning "nothing to deliver". The run keeps a result
per repository (`nodes[].repositories`, `repositories`: branch, commit, diff stat, merge request, pushed, error); `mr`
and `mergeRequest` are the primary repository's merge request, or the first one opened.

Validation: a delivery needs a project repository among the sources; `repositories` may name only project sources;
a delivery while every coding step's agent works in a `SEPARATE` directory is an error (nothing to deliver); a delivery
reachable from a decision on model output without a human gate is a warning, and so is one that pushes to a `DOCS` and
a `SOURCE` repository.

<a id="before-adr-0063"></a>
### Workflows stored before ADR 0063

Until ADR 0063 a coding step pushed itself (`push`, `branchTemplate`, `commitSubjectTemplate`, `mergeRequest*`), named
its own `repositories` (with `READ` / `PUSH_MR` access) and its own `setupCommand`, and a Spec Kit step had a `phase`.
Migration V309 rewrote stored workflows, library workflow versions and unfinished runs, and the same rules apply when a
workflow is read (imports, MCP clients):

- a step that pushed (its config or its template lineage had `push: true`) gets a `DELIVER_CHANGES` node
  `<step id>-deliver` right after it with the step's git settings; the step's `success` goes to it, its `success`
  goes where the step's went and its `failure` where the step's `failure` went;
- step `repositories` join the workflow's `sources`; step setup commands are appended to the workflow's
  `setupCommand` (` && `);
- steps of `spec-kit-specify`, `spec-kit-plan`, `spec-kit-tasks` (or with `phase` SPECIFY / PLAN / TASKS) use
  `write-spec`, `write-plan`, `write-task-list`; the former keys still resolve (deprecated templates that extend the
  replacements unchanged);
- `push`, `phase`, `repositories`, `setupCommand` and the git keys leave the step config; `collectArtifacts` stays and
  is read as extra output files.

## CI waits and signals

A `WAIT_FOR_CI` node pauses the run until your CI calls `backoffice-ci signal <name> --status success|fail`
(`docs/guides/ci-integration.md`). Matching: the signal names the run explicitly (`--run`, taken from the
`Back-Office-Run` git trailer that the runner writes into every commit), else the newest waiting run of
the project with the same signal whose pushed branch (`mr.branch`) equals the CI branch, else the only
waiting candidate. `success` and `fail` are the ports; `timeoutSeconds` leaves through `timeout`. A project
member with `run.signal` can unblock a run by hand with `POST /api/v1/runs/{id}/signals`.

Back Office never triggers CI: pushing the branch does. The **Deliver changes** action pushes with
`-o merge_request.create` so GitLab opens the MR and your pipeline runs; put the `WAIT_FOR_CI` node after it.

## Loops and `maxVisits`

Loops are ordinary edges back to an earlier node (`review-ok.changes -> implement`,
`spec-gate.revise -> specify`). Every node counts its visits per run; when a node would start for the
`maxVisits + 1`-th time the run fails with `node x exceeded maxVisits=n` (retryable). Default is 3;
set it per node. Validation lists the nodes on cycles as information, not as an error.

## The default workflows

Four workflows are created for every new project with at least one repository (and can be edited,
duplicated or deleted), together with the CI entrypoint `code-review`; the creator is stamped as
`updatedBy`. Every seeded gate names `owner` as the accountable role.

A project created **without a repository** gets instead one starter workflow, **LLM task with review**
(`MANUAL`, `API`): `start -> llm-task` (template `llm-task`; write the task into the node's parameter **Task**,
`overrides.parameters.task`; the run parameter `input` is given to the model as data, revision comments as
`{{feedback}}`)
`-> review` ("Result ok?"): `approve -> done` (`END SUCCESS`), `revise -> llm-task`, `reject -> rejected`
(`END FAILURE`). No CI entrypoint is created. When the project gets its first repository, the
workflows below and the `code-review` entrypoint are added once (skipped when workflows with those names
already exist).

**Issue to reviewed MR** (`MANUAL`, `API`): `start -> fetch-issue` (action `FETCH_ISSUE`) `-> specify` (template
`write-spec`: the coding agent writes `specs/<feature>/spec.md`) `-> clarify -> confident?`.
`clarify` (template `clarify-spec`) returns `{confidence, questions}`; the decision takes `yes`
(`confidence >= 0.8`) straight to `plan`, otherwise `no` to the `spec-gate` ("Spec ok?"): `approve ->
plan`, `revise -> specify`, `reject` cancels; its Definition of Ready: acceptance criteria are testable,
open questions are answered or deferred with an owner, scope is stated. Then `plan` (`write-plan`) `-> tasks`
(`write-task-list`) `-> implement -> implement-deliver` (`DELIVER_CHANGES` "Deliver changes": branch
`feature/<issue key>`, merge request; its `failure` is unconnected and fails the run) `-> ci-tests`
(`WAIT_FOR_CI` on signal `tests`, 2 h timeout): `success -> code-review`, `fail -> tests-failed`
(`END FAILURE`). `code-review -> review-ok?` (`review.verdict != 'REQUEST_CHANGES'`): `approved ->
merge-gate`, `changes -> implement` (rework loop, `implement`, `implement-deliver` and `code-review` allow 3 visits;
each pass delivers the rework to the same branch).
`merge-gate` ("Merge?"): `approve -> transition` (action `TRANSITION_ISSUE`, Jira "In Review") `-> done` (`END SUCCESS`),
`revise -> implement`; it is a four-eyes gate (`forbidSelfApproval`) with the Definition of Done "tests
green in CI / review findings addressed / spec updated". People are involved at most twice.

**CI code review** (`CI`, `MANUAL`), registered as entrypoint `code-review` in `BLOCKING` mode:
`start -> code-review -> verdict?` with `approved -> passed` (`END SUCCESS`) and `changes -> failed`
(`END FAILURE`). The CI job uploads the MR diff, which the `code-review` task reads as
`{{artifacts.diff}}`; the END outcome becomes the gate result (`PASS`/`FAIL`, always `PASS` in
`ADVISORY` mode).

**Change request (delta)** (`MANUAL`, `API`; ADR 0019) for changing an existing system:
`start -> fetch-issue` (action `FETCH_ISSUE`) `-> impact-analysis` (template `impact-analysis`) `-> impact-gate` ("Approve impact analysis?",
checklist: affected artefacts listed / estimate given / options compared; `revise -> impact-analysis`)
`-> user-story` (`write-user-story`) `-> plan-tasks` (`plan-task-list`, planned from the approved impact
analysis) `-> task-list-gate` ("Approve task list?", shows `user-story` and `tasks`, checklist: every task has verification and test_command / the last task is
reformat, lint, and test; `revise -> plan-tasks`) `-> implement` (`implement-task-list`, TDD, stops on a
red verification) `-> implement-deliver` (`DELIVER_CHANGES`) `-> ci-tests` (`WAIT_FOR_CI tests`: `fail -> tests-failed`) `-> code-review -> verdict?`
(`changes -> implement`, whose prompt then carries the `review` artefact) `-> merge-gate` (four-eyes,
owner accountable, Definition of Done as above) `-> done`. `reject` on any gate cancels the run. Projects
created before the `fetch-issue` step was added keep their stored workflow; add the step in the designer.

**Reference: one Claude Code session** (`MANUAL`, `API`; ADR 0059), the yardstick: `start -> fetch-issue` (action
`FETCH_ISSUE`) `-> implement` ("Claude Code: implement the issue": template `agent-task` with the agent `claude-code`,
parameter **Task**: read the repository, implement the issue end to end, add or update the tests, run the build and
the tests until they pass, summarise what changed, how it was verified and what is left open) `-> implement-deliver`
(`DELIVER_CHANGES`: branch and merge request) `-> merge-gate` ("Merge?", four-eyes, owner accountable, Definition of Done as above): `approve ->
done` (`END SUCCESS`), `revise -> implement` (your comment goes back to the step, at most 3 visits), `reject ->
rejected` (`END FAILURE`). One agent session does everything a cheaper workflow splits into steps; the cheaper
workflow is better only when its cost of an accepted result is lower without more human wait or rework.

Projects created before a default workflow existed get it with **Add missing default workflows (N)** in the
project's workflows list (`POST /api/v1/projects/{projectId}/default-workflows`, `workflow.edit`; since ADR 0063 this
includes **Issue to reviewed MR**, which used to need Spec Kit). Existing workflows with the same name are never
replaced.

### Comparing workflows and agents on the same issues

1. Start the same issues (`start_workflow` over MCP, `POST /api/v1/workflows/{id}/runs`, or the start dialog) through
   **Reference: one Claude Code session** and through the workflow you want to make cheaper, or through copies of one
   workflow that differ only in the **Agent** and **Model profile** of their task steps.
2. Compare the whole workflows in their metrics (chart icon in the project's **Workflows** tab): **Cost per accepted
   result** (the cost of every run / successful runs), **First-pass rate** and **Human wait**.
3. Compare the steps in **Insights** → **Agents** (`GET /api/v1/insights/agents`, MCP `get_agent_insights`),
   **Per template**: the cost of an accepted step per agent and model on the same template.
4. Keep the cheaper variant only where it holds up on enough runs (the workflow metrics mark versions with fewer than
   5 finished runs). Definitions: [11-governance.md](../architecture/11-governance.md#agent-insights-get-insightsagents).

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
      "task": { "templateKey": "code-review", "overrides": { "config": {} } } },
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
| Validation or run start reports an unknown repository key or a missing primary repository | the project's repositories changed; fix the workflow's **Sources** or mark a repository primary ([Workflow sources](#workflow-sources)) |
| `nothing to deliver: the workflow's sources hold no project repository` | a **Deliver changes** node in a workflow whose sources are only library sources (or a project without repositories); add a project repository to the sources ([Deliver changes](#deliver-changes)) |
| a delivery succeeds with "nothing to deliver: no repository has changes" | the agents changed nothing, or worked in a **Separate directory**; check the implementing step's `diff` |
| `push refused: ...` on a delivery | the branch template renders to the default branch, `HEAD` or `refs/...`; change **Branch** on the **Deliver changes** node |
| Runner tasks fail immediately | `docs/runbooks/runner-job-failed.md` and `GET /api/v1/settings/integrations` (runner mode) |
| `Parameter x needs a value` | a required template parameter has no value on the step, in the template chain or as a default: set it under **Parameters** or use a preset ([task-library.md](task-library.md#2-a-template-with-parameters)) |
| `Parameter x needs the name of the artefact it reads` | a parameter bound **Result of an earlier step** has no artefact name; type the name an earlier step produces ([The run start form](#start-form)) |
| a step fails with `the agent asked more than N rounds of questions` | the agent kept asking after `maxQuestionRounds`; answer more completely, raise **Question rounds** or clarify the instructions ([Agent questions](#agent-questions)) |
