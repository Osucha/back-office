# Designing workflows

A workflow is the delivery process of a project drawn as a graph: tasks that agents do, decisions on the
results, gates where a person decides and waits for your CI. This chapter covers the designer, the node
kinds, the repositories of coding steps, decisions, gate settings, confirmations before external writes and
starting a run.

<a id="designer"></a>
## The workflow designer

Open it from a project: tab **Workflows** → **Edit** (or **View** without `workflow.edit`), or **New
workflow** in the page header (`/projects/:id/workflows/new`). An existing workflow opens at
`/workflows/:id/edit`.

| Area | What it holds |
|---|---|
| Header | Project key and version, the workflow name, a status chip (**valid**, errors, warnings, or **read-only**), the **Designer** / **JSON** switch, **Template** (new workflows only), undo and redo, **Tidy layout**, **Validate**, and the buttons that show or hide the palette and the properties. |
| Palette (left) | **Control** nodes and **Tasks** from the task library, grouped by interface, with a search field. A project's own template has a project icon. |
| Canvas | The graph. Zoom buttons, **Readable view from START**, **Fit everything**, and from eight nodes an overview map (**Show the overview** / **Hide the overview**). |
| Properties (right) | The selected node or edge; with nothing selected, the workflow's **Description**, the **Enabled** switch and **Triggers**. |
| **Validation** sheet (bottom) | Errors and warnings grouped by node; click one to jump to the node. |
| Action bar | **Close**, **Duplicate workflow**, and **Create workflow** or **Save as new version** (`Ctrl S`). |

### Steps: build or change a workflow

1. For a new workflow, name it and optionally pick a **Template**: **Issue to reviewed MR**, **CI code
   review** or **Empty (START + END)**.
2. Add nodes: drag them from the palette onto the canvas, or click (or press `Enter` on) a palette item.
3. Connect nodes: drag from an output port of one node to another node. Each output port has at most one
   edge. Loops back to earlier nodes are ordinary edges.
4. Select a node to edit it in **Properties** (see below). Select an edge to give it a **Label** or
   **Delete edge**.
5. Fix the errors in the **Validation** sheet, now or later. A workflow saved with errors is kept as a
   **draft** ("2 errors: saving keeps a draft, runs cannot start until fixed."): every save is a version as usual,
   but no run starts from a draft (by hand, from CI or the Jira watcher) and it cannot be published to the library.
   The project's workflow list marks it **draft (has errors)**. Warnings never block anything.
6. Click **Create workflow** or **Save as new version**.

Every save creates a new version. Runs keep the version they started with, so editing never disturbs work
in flight. Turning **Enabled** off stops new runs (**Start run** is disabled); runs in flight continue.
The **JSON** view shows the full definition; edits there apply as soon as the JSON parses (**Copy JSON**,
**Format JSON**).

### Designer shortcuts

| Keys | Action |
|---|---|
| `[` / `]` | Show or hide the palette / properties |
| `Ctrl Z`, `Ctrl Shift Z` or `Ctrl Y` | Undo, redo |
| `Ctrl D` | Duplicate the selected node |
| `Delete` | Delete the selected node or edge |
| Arrow keys | Move the selection along the edges |
| `Esc` | Clear the selection |
| `Ctrl S` | Save |

### The default workflows

Every new project with a repository gets three workflows (a project without a repository gets the starter
workflow **LLM task with review** instead, see [Projects without a repository](projects.md#no-repository)):

- **Issue to reviewed MR**: fetch the issue, write a spec, check it (a gate asks you only when the
  agent's confidence is below 0.8), plan, list tasks, implement, wait for the CI signal `tests`, review
  the code, then a four-eyes **Merge?** gate and the Jira transition **Move issue to In Review**, which
  asks a person to confirm the move ([Confirmations before external writes](#write-confirmations)).
- **CI code review**: started from your pipeline through the entrypoint `code-review`; reviews the
  uploaded diff and passes or fails the CI job.
- **Change request (delta)**: impact analysis, user story and task list, each important step behind a
  gate with a checklist, then implementation, CI, review and a merge gate.

<a id="nodes"></a>
## Node kinds

| Node | Purpose | Output ports |
|---|---|---|
| **Start** | Entry; exactly one per workflow. | `out` |
| **Task** | Runs a task template from the library (see below). | the template's ports, usually `success`, `failure` |
| **Decision** | Routes on conditions over the run state (see [Decisions](workflows.md#decisions)). | one per branch, plus the default |
| **Human gate** | Stops the run until an approver picks an option (see [Gate settings](workflows.md#gates-config)). | one per option (default `approve`, `revise`, `reject`) plus `timeout` |
| **Wait for CI** | Stops the run until your CI sends a signal. | `success`, `fail`, `timeout` |
| **End** | Finishes the run with **Success** or **Failure**. For a CI-started run this decides the CI gate. | none |
| **Note** | A sticky note; never runs, cannot be connected. | none |

Every node has a **Name** and an **Id** (a slug; fixed once saved, because runs and logs refer to it).
Task, decision, gate and CI nodes also have **Max visits** (loop guard, default 3) and **Timeout (s)**.
When a node would start more often than **Max visits**, the run fails.

**Unconnected ports.** A required port must be connected: `success` of a task, every decision branch, the
first gate option, `success` of a CI wait, `out` of Start. If the run leaves through an unconnected
`failure`, `fail` or `timeout` port, the run fails; through an unconnected `reject`, it is cancelled. The
validation warns about other unconnected ports.

### Task nodes

Pick the **Template**. Under it you see the template's lineage, executor, interfaces and ports, and **Open
in library**. **Overrides** change the template for this node only:

| Override | Effect |
|---|---|
| **Model profile** | Model for this node instead of the template's or the project default. |
| **User prompt** | **Inherited**, **Append** (added after the template prompt) or **Replace**. |
| **System prompt append** | Added to the template's system prompt. |
| **Add skills**, **Remove skills** | Adjust the template's skill list. |
| **Config** | Executor settings such as the verify command, branch name or merge request options; merged over the template's config. |

**Spec Kit templates** (**Spec Kit: specify**, **plan**, **tasks** and templates that extend them) appear in the
palette only when the project has [Spec Kit](projects.md#spec-kit) on; otherwise a Spec Kit step is a validation
error and runs refuse to start. **Jira templates** have a **Jira connection** section with **Act as**: the
project's system connection, a specific connection, or the person who approves the write or starts the run
([Act as](integrations-jira.md#jira-act-as)).

Two templates do not run a model or an agent (ADR 0052):

- **Start workflow** starts another workflow of the project, one run per entry of a JSON artefact (**Parameters
  from**, for example the `proposals` an agent wrote), with the entry's fields as run parameters (`title`, `idea`,
  ...). It must follow a gate directly: an agent proposes, you accept, Back Office starts. With **Selection**
  `DECISION` only the entries whose numbers you write in the gate's comment are started (`1, 3`; `all` starts every
  one); with `ALL` every entry. The runs are started on behalf of the person who decided at the gate (their
  permissions and personal model profiles apply) and are listed in its `started-runs` artefact.
- **Project digest** writes the `digest` artefact: what happened in the project since the run parameter `since`
  (a scheduled run gets it) - the runs of the other workflows with outcome, duration and cost, steps that ran more
  than once or failed with their errors, gate decisions with their comments, and the latest pipelines of
  **Pipeline branches** with their failed jobs (needs a repository token with CI_READ). An agent step after it reads
  `{{artifacts.digest}}`.

Prompts are templates: `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}` (all revision comments),
`{{project.constitution}}`, `{{mr.url}}`. A missing value renders empty and adds a warning to the run log.
Because nodes refer to templates by key, changing a template changes every workflow that uses it on its
next run (see [Task library](task-library.md#templates)).

### Wait for CI nodes

Set the **Signal** name (for example `tests`) and an optional **Timeout (seconds)**. Your pipeline sends
`backoffice-ci signal tests --status success|fail`; the run leaves through `success` or `fail`, or through
`timeout`. See [GitLab CI](integrations-gitlab-ci.md#ci-overview).

With **Watch pipeline** on, Back Office watches the pipeline itself and your CI sends nothing: it reads the latest
pipeline of the branch the run pushed (in the step's **Repository**, empty = the primary one) once a minute with the
project's read-only token that has CI_READ ([Repository access tokens](integrations-git.md#git-project-tokens)).
When the pipeline succeeds the run leaves through `success`; when it fails, through `fail`, and the next step receives
the failed jobs (except those allowed to fail) with the last lines of their logs as feedback. Connect `fail` back to
the implementing step and the agent fixes what CI reports; the step's **Max visits** bounds the loop. An implementing
step that ends its work log with `RESULT: PARTIAL` (everything it could check in its sandbox passed, the rest needs
CI) belongs in front of such a node.

<a id="step-repositories"></a>
## Repositories of a step

Coding steps (**Implement** and **Spec Kit** templates) run an agent that works in Git repositories of the
project ([Repositories](projects.md#repositories)). All other steps (model calls, Jira, gates, CI waits) use
no repository, so their properties have no repository section.

Select a coding step; its properties have the section **Repositories**:

| Setting | Meaning |
|---|---|
| **Use the primary repository** (default) | The step works in the project's primary repository, with **Read** access when **push** is off and **Push and merge request** when it is on. This is how every workflow behaves that was written for one repository. |
| Repository list | **Add repository** picks a project repository by key (shown with its purpose and URL); add as many as the step needs. Remove one with the delete button. |
| **Access** (per repository) | **Read**: the agent can clone and read it, nothing is pushed. **Push and merge request**: the agent's changes are pushed to a new branch and a merge request is opened against the repository's default branch. |

**Least privilege.** Give each repository the lowest access the step needs: **Read** for a contract,
reference or documentation the agent only consults, **Push and merge request** only where it must change
something. The runner gets a read-only token for **Read** repositories and a push token only for **Push and
merge request** ones ([Git access for agents](integrations-git.md#git-least-privilege)).

**Never merge.** Agents push a branch and open a merge request; they never merge, never push to the default
branch and never force-push. A person (or your Git server's approval rules) merges. When a step pushes to
several repositories, the same branch name is used in each and each gets its own merge request.

**Workspace.** Each repository is cloned into its own folder named after its key: `/workspace/service`,
`/workspace/docs`, ... The agent starts in the step's main repository (the project's primary one if the step
uses it, otherwise the first in the list) and is told which repositories exist, where they are, their purpose,
base branch and whether it may change them.

**Library sources.** Under **Library sources (read only)** the step ticks sources of the
[library](task-library.md#sources): standards, templates, reference material shared by all projects. Each is
cloned read only into `/workspace/lib-<key>` and the agent is told to follow it, not to change it. Ticking a
source while the step uses the primary repository switches the step to its own list, starting with the
primary repository; going back to **Use the primary repository** drops the ticked sources.

**Validation messages** (in the **Validation** sheet and when a run starts):

| Message is about | What to do |
|---|---|
| the project has no repository | Add a repository to the project, or use a non-coding step. |
| no primary repository and no repositories on the step | Add repositories to the step, or mark one project repository **Primary**. |
| an unknown repository key | The message lists the project's keys; pick one of them (the repository may have been removed or renamed). |
| a repository listed twice | Remove the duplicate. |
| an access other than read or push and merge request | Fix the value (in the **JSON** view: `READ` or `PUSH_MR`). |
| push and merge request while **push** is off | Turn **push** on in **Config**, or set the access to **Read**. |
| repositories on a step that is not a coding step | Remove them; only coding steps use repositories. |
| (warning) one step pushes to a documentation and a source-code repository | Allowed; check that one agent should really change both. |

Errors block saving. A run whose steps have such errors (for example a repository was removed from the project
after the workflow was saved) does not start; the message names the step. In the **JSON** view the setting is
`config.repositories: [{"key": "docs", "access": "READ"}]`.

<a id="workspaces"></a>
## Workspaces and agent sessions

Every coding step normally starts from nothing: a fresh `/workspace`, fresh clones, a new agent session. A **run
workspace** lets several steps of one run work in the same directory, and an **agent role** lets an agent continue its
own conversation instead of re-reading everything (ADR 0044).

**Define workspaces in the project.** The project tab **Workspaces** lists them; every workflow of the project can
use them. **Add workspace** (you need `project.manage`) and set:

| Field | Meaning |
|---|---|
| **Name** | Shown on the canvas around the workspace's steps. |
| **Key** | Lower case letters, digits, hyphens (up to 20), for example `main`. Steps pick the workspace by it; it cannot change later. |
| **Sources cloned into it** | Project repositories and [library sources](task-library.md#sources). Steps of the workspace that name no repositories of their own work on these: the project's primary repository among them gets the access the step's **push** gives it, the others are read only. A step's own **Repositories** list replaces them for that step. |
| **Setup command** | For example `npm ci`. Runs once per run, before the first step of the workspace; again only when you change it. |
| **Agent roles** | For example `implementer`, `reviewer`. Steps with the same role continue one agent session. Empty = any role. |

**Pick the workspace in the step.** A coding step (**Implement**, **Spec Kit**) has the section **Workspace** with
only the choice among the project's workspaces: the workspace (or **None: a fresh directory**), the **Agent role** (or a new session) and, on
**Implement** steps, **Only prepare the workspace** (clone and set up, no agent, no push). The built-in task **Prepare
workspace** (`prepare-workspace`) is such a step; it is optional, the first step of a workspace prepares it too.

**On the canvas** each workspace is a coloured, dashed region with its name around its steps, so you see at a glance
which steps share a directory. The region spans from the first to the last of its steps. The workflow properties (click
the empty canvas) list the project's workspaces with the number of steps of this workflow in each.

**Agents taking turns.** For example *implement* (role `implementer`, push on) → *verify* (role `reviewer`, push
off) → a decision → back to *implement*. Each agent continues its own session and sees what the other left in the
workspace: the reviewer reads the implementer's uncommitted changes, the implementer finds the reviewer's notes. A
step with read access leaves its changes uncommitted for the next step; a pushing step commits everything in the
repository and pushes the run branch.

**What happens on the runner.** The workspace lives on the runner that ran its first step, as a Docker volume or a
Kubernetes volume claim. Steps of the workspace run there, one after the other, also when branches of the workflow
run in parallel. Before each step the runner checks every reused repository and rewrites its git configuration, so
nothing a previous agent planted in `.git` runs.

**When the run ends** the workspace is deleted; after a failed run it is kept for 24 hours so **Retry** continues in
it. A workspace used after that starts empty (the pushed run branch is still there).

**Mind the trust.** All steps and roles of a run can read and change everything in their workspace, including the
other roles' session history. For an independent check use a step without the workspace.

Validation: a step that names a workspace the project does not have, a role the workspace does not list, or **Only
prepare** without a workspace is an error; a role without a workspace is a warning. Deleting a workspace turns the
workflows that use it into drafts until their steps pick another one. A workflow published to the library keeps the
workspace keys of its steps; the adopting project needs workspaces with those keys.

<a id="decisions"></a>
## Decisions

A **Decision** node has **Branches**, each with an id (its port), a label and an expression. Branches are
checked in order; the first that is true wins. If none is true, the run leaves through **Default branch**;
if that is empty, the run fails.

Expressions are read-only conditions over the run state. The editor checks each one as you type and
marks it valid or explains the problem. **State fields available in expressions** lists every field.

| Field | Holds |
|---|---|
| `confidence` | The last confidence a task reported (0–1), or empty. |
| `lastOutcome` | `SUCCESS` or `FAILURE` of the previous node. |
| `review.verdict`, `review.score`, `review.blockers` | The last code review. |
| `artifacts['spec']` | An artefact, or empty when not produced. |
| `issue.priority`, `issue.labels` | The Jira issue, after the fetch task. |
| `input.ci.mergeRequest.targetBranch` | The CI context of a CI-started run. |
| `ci.status`, `decision.option` | The last CI signal and the last gate decision. |

Examples: `confidence >= 0.8`, `review.verdict != 'REQUEST_CHANGES'`,
`review.blockers == 0 and review.score >= 70`, `input.ci.mergeRequest.targetBranch == 'main'`.

Fields are empty until something fills them: `confidence >= 0.8` is false before any task reported a
confidence.

<a id="gates-config"></a>
## Gate settings

Select a **Human gate** node to set:

| Setting | Meaning |
|---|---|
| **Question** | What the approver is asked; may use template values such as `{{issue.key}}`. |
| **Options (ports)** | The decision buttons and output ports, default `approve`, `revise`, `reject`. |
| **Show artefacts** | Artefact names shown, and editable, in the gate panel (for example `spec`, `review`, `diff`). |
| **Timeout (seconds)** | After this time the gate expires and the run leaves through `timeout`. |

**Who decides this step** sets who may decide, for every run of the workflow (the definition-level
assignment; the project's manager decides it when designing the workflow):

| Setting | Meaning |
|---|---|
| **Project roles** | Roles allowed to decide. Empty: anyone with `run.approve` in the project. |
| **Named people** | Only these people may decide; each also needs one of the project roles, if set. Empty: anyone in those roles. |
| **Accountable role** | The single accountable role, shown on the turn card and in the panel. Must be one of the project roles when those are set. |
| **Forbid self-approval** | The person who started the run cannot decide this gate. Runs started by CI have no human starter, so nobody is excluded. |

A project owner or maintainer can reassign a step that is already waiting, for that run only, with
**Assign…** on the gate ([Assigning a step](your-turn.md#assign-steps)); the workflow does not change.

**Checklist** holds Definition of Ready or Done items: an id, a text, and the **Required** switch. Use
**Add checklist item**. **Approve** stays disabled until every required item is ticked; revise and reject
need no ticks. Put a Definition of Ready on gates before work starts and a Definition of Done on gates
before merge.

When the run is sent back with **Send back for revision**, the comment is added to `{{feedback}}`, the
confidence is reset and the run follows the `revise` edge, usually to the task that wrote the artefact.

<a id="write-confirmations"></a>
## Confirmations before external writes

Task nodes that write outside Back Office ask a person to confirm the exact write before it happens. Today
these are the Jira tasks **Notify Jira** (a comment) and **Transition issue** (a status change); the
palette marks them as writing to Jira. A confirmation is built into the step, so you do not add a gate for
it.

Select such a node; its properties have the section **Approval before writing to Jira**:

| Setting | Meaning |
|---|---|
| **Approval** | **Approval required (recommended)**, the default, or **Write without approval**. Nodes saved before this setting existed count as required. |
| **Who approves** | The rule in effect. By default holders of `run.approve` with the project role **Owner** or **Maintainer**, accountable **Owner**, shown with "(default)". Change it with **Project roles**, **Named people**, **Accountable role** and **Forbid self-approval**, exactly as **Who decides this step** on a gate ([Gate settings](#gates-config)); a rule without roles and people means anyone with `run.approve`. **Back to the default** removes the node's own rule. |

At run time the step renders its comment or target status once, the run waits, and the people allowed
decide in **Your turn** ([Confirming external writes](your-turn.md#write-confirmations)). Owners and
maintainers can reassign a waiting confirmation with **Assign…**, as for a gate.

- **Approve**: the step writes exactly the text that was shown and leaves through `success`.
- **Reject**: nothing is written; the step leaves through **`failure`** with "write rejected by <name>,
  nothing was written ...". Connect `failure` (for example to an **End** with **Failure**, or back to the
  task that wrote the text); unconnected, the run fails.

**Validation**: with **Write without approval**, the node gets the warning "this step writes to Jira (...) without
anybody confirming the exact write first; text from issues or model output can reach it (prompt
injection) - keep approval REQUIRED unless the content is fixed". It does not block saving. Keep
approval required whenever the text contains model output or issue fields (`{{artifacts.*}}`, `{{issue.*}}`,
`{{feedback}}`). An unknown value of **Approval** in the JSON view is an error.

Details, audit records and the effect on the default workflow: [Approval before writing to
Jira](integrations-jira.md#jira-write-approval).

<a id="start-run"></a>
## Starting a run

You need `run.start` in the project (roles **Owner**, **Maintainer**, **Developer**), and the workflow
must be enabled.

1. Open the project, tab **Workflows**, and click **Start run** next to the workflow. Or press `Ctrl K`,
   choose **Start run…** and pick a workflow.
2. Enter the **Jira issue key (optional)**, for example `SHOP-123`. Workflows that fetch the issue need
   it.
3. Optionally enter **Parameters (JSON, optional)**; expressions read them as
   `input.parameters['name']`.
4. Click **Start run**. The run page opens.

Other ways to start a run: your pipeline through a CI entrypoint (see [Projects](projects.md#project-ci)),
the REST API (`POST /api/v1/workflows/:id/runs`), and the MCP tool `start_workflow` from your IDE (see
[IDE and MCP](integrations-ide.md#mcp-tools)).

The **Triggers** list of a workflow (**Manual (UI button)**, **API call**, **CI entrypoint**, **Jira
transition**) documents how the workflow is meant to start; Back Office does not enforce the first three.
The **Jira (intake watcher)** trigger (`JIRA_TRANSITION`) is used by the project's **Jira intake watcher**: with a **status** (for
example `Ready for AI`), an issue that enters that status starts a run with its key; without a status, a
new issue matching the project's JQL filter does. The watcher is switched on per project and can start
only a workflow that has this trigger ([Jira intake watcher](integrations-jira.md#jira-intake)).

The **Schedule** trigger (`SCHEDULE`, ADR 0052) starts the workflow by itself **every N minutes** (at least 5)
after its latest run began, never while one of its runs is still active and never while the workflow is disabled.
The run gets the parameter `since` (when the previous run started), so a watcher reports only what is new. It is
started on behalf of whoever last saved the workflow: their `run.start` permission and their personal model profiles
apply, so a schedule may use your own subscription model profile.

A run refuses to start (`409`) when a Jira step of the workflow has no usable connection; the message
names the step. It also refuses to start when a coding step names a repository the project does not have, or
needs a primary repository the project lacks ([Repositories of a step](#step-repositories)). Saving the workflow only warns about it
([Warnings and errors](integrations-jira.md#jira-tasks)).
