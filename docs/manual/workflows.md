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
| Header | Project key and version, the workflow name, a status chip (**valid**, errors, warnings, or **read-only**), the **Designer** / **JSON** switch, **Template** (new workflows only), undo and redo, **Tidy layout**, **Validate**, **Agents in this workflow** with a badge counting its problems ([Agents in this workflow](#agent-audit)), and the buttons that show or hide the palette and the properties. |
| Palette (left) | **Control** nodes, **Actions** (Jira and Back Office operations without a model, see [Action nodes](#actions)) and **Tasks** from the task library, grouped by category (Analysis, Specification, ...), with a search field. A project's own template has a project icon; a template that needs a coding agent has the tag **coding agent**. Hovering a task shows its description, the kind of agent it needs and its **Required skills**. |
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

<a id="default-workflows"></a>
### The default workflows

Every new project with a repository gets these workflows (a project without a repository gets the starter
workflow **LLM task with review** instead, see [Projects without a repository](projects.md#no-repository)):

- **Issue to reviewed MR**: fetch the issue, write a spec, check it (a gate asks you only when the
  agent's confidence is below 0.8), plan, list tasks, implement, wait for the CI signal `tests`, review
  the code, then a four-eyes **Merge?** gate and the Jira transition **Move issue to In Review**, which
  asks a person to confirm the move ([Confirmations before external writes](#write-confirmations)).
- **CI code review**: started from your pipeline through the entrypoint `code-review`; reviews the
  uploaded diff and passes or fails the CI job.
- **Change request (delta)**: impact analysis, user story and task list, each important step behind a
  gate with a checklist, then implementation, CI, review and a merge gate.
- **Reference: one Claude Code session**: fetch the issue, then one task step (template **Agent task**, `agent-task`, with the
  agent **Claude Code**, push and merge request on) that reads the repository, implements the issue end to end,
  runs the build and the tests and opens the merge request, then a four-eyes **Merge?** gate (**revise** sends your
  comment back to the step). It is the yardstick: run the same issues through it and through the cheaper workflows
  and compare their cost of an accepted result ([Agents: cost and effectiveness](costs-insights.md#agent-insights)).

**Issue to reviewed MR** is created only when Spec Kit is on ([Spec Kit](projects.md#spec-kit)).

**Projects created before a default workflow existed** do not get it automatically. When a project with a
repository lacks default workflows, its **Workflows** tab offers **Add missing default workflows (N)**
(`workflow.edit`): it adds the missing ones by name, for example **Reference: one Claude Code session**, and never
replaces an existing workflow with the same name. The Spec Kit workflows are added from the **Spec Kit** card
instead ([Spec Kit](projects.md#spec-kit)).

<a id="nodes"></a>
## Node kinds

| Node | Purpose | Output ports |
|---|---|---|
| **Start** | Entry; exactly one per workflow. | `out` |
| **Task** | Runs a task template from the library with an agent (see below). | `success`, `failure` |
| **Action** | Runs a fixed operation without a model: Jira, starting workflows, project digest (see [Action nodes](#actions)). | `success`, `failure` |
| **Decision** | Routes on conditions over the run state (see [Decisions](workflows.md#decisions)). | one per branch, plus the default |
| **Human gate** | Stops the run until an approver picks an option (see [Gate settings](workflows.md#gates-config)). | one per option (default `approve`, `revise`, `reject`) plus `timeout` |
| **Wait for CI** | Stops the run until your CI sends a signal. | `success`, `fail`, `timeout` |
| **End** | Finishes the run with **Success** or **Failure**. For a CI-started run this decides the CI gate. | none |
| **Note** | A sticky note; never runs, cannot be connected. | none |

Every node has a **Name** and an **Id** (a slug; fixed once saved, because runs and logs refer to it).
Task, action, decision, gate and CI nodes also have **Max visits** (loop guard, default 3) and **Timeout (s)**.
When a node would start more often than **Max visits**, the run fails.

**Unconnected ports.** A required port must be connected: `success` of a task or action, every decision branch, the
first gate option, `success` of a CI wait, `out` of Start. If the run leaves through an unconnected
`failure`, `fail` or `timeout` port, the run fails; through an unconnected `reject`, it is cancelled. The
validation warns about other unconnected ports.

<a id="task-nodes"></a>
### Task nodes

A task step joins three things: a **template** (what to do), an **agent** (who works and how) and where the
template's **parameters** take their values from ([Task library](task-library.md#agents)). The template names no
agent and no model: the step chooses them (ADR 0061).

Pick the **Template** (deprecated templates are not offered; a step that already uses one shows the tag
**Deprecated**; templates tagged **needs parameters** expect values in the step). Under it you see the template's
lineage, the tag **Needs a coding agent** when it needs one, its category, and **Open in library**.

**The designer pre-selects an agent** when you drop a template on the canvas or pick another template for a step
that has no agent (or one of the wrong kind):

1. only agents of the right kind: a coding agent when the template needs one; otherwise any agent, model answer
   agents first;
2. the agent that has the most of the template's [Required skills](task-library.md#required-skills);
3. on a tie, the platform default of that kind (**OpenCode** for coding work, **Model answer** otherwise), then
   built-in agents, then by name.

A project's own agent replaces the global one with the same key. You can always choose another agent. A step stored
without an agent runs with **OpenCode** when its template needs a coding agent, else with **Model answer**; the
**Agent** field then shows **Default: OpenCode** or **Default: Model answer**. A step's agent is never chosen behind
your back at run time: what the field shows is what runs.

**Instructions for this step** (ADR 0062): the template says what to do; here you add to it for this step only.

- **Append**: notes added after the template's instructions, for example what to focus on in this workflow.
- **Replace (advanced)** (collapsed): text used instead of the template's instructions in this step, only when the
  step needs a different task. The platform still adds the issue, earlier results, the values given at start, the
  feedback and the answers ([Instructions and what the agent receives](task-library.md#instructions)).

**Parameters** (shown when the template has any) lists the template's [parameters](task-library.md#parameters).
For each, **Where the value comes from**:

| Source | Meaning |
|---|---|
| **Value** | A value set here: a text field, a number, a switch for **Yes / no**, a list for **Choice**. Empty means the template's value or the default, shown greyed out (**Default: ...**, **Inherited: ...**). |
| **Ask at start** | "The run start form asks for it, with this label and type." The field takes the parameter's label, type, **Required** and help. For a **Jira issue key** parameter: "The run start form asks for it; the value is the run's issue key." |
| **Result of an earlier step** | The content of an artefact an earlier step produces: pick its name (for example `spec`) from the artefacts earlier steps declare, or type it. Without a name the step shows "Parameter {name} needs the name of the artefact it reads". |

A required parameter without any value ("Required: set a value, ask at start or use a result") and a value that does
not fit the parameter's type or options are validation errors: the workflow can be saved only as a draft and the run
does not start. When a run has no value for a parameter asked at start or bound to a result, the template's value or
the default applies.

**What this step gets** (collapsed) shows the template with this step's overrides applied: the agent, the model
profile, the **Instructions** (with this step's notes), rules and skills (those of the agent), configuration and
what is **Asked at start**, exactly what the model or coding agent receives before the run fills in the values.
**Warnings** there do not stop the step, for example a skill the template requires (**Required skills**) that the
step's agent does not have.
**Overrides** change the template for this node only:

| Override | Effect |
|---|---|
| **Agent** | Who runs this step: **Model answer**, **Analyst**, **Reviewer**, **OpenCode**, **Claude Code** or another agent of your library ([Agents](task-library.md#agents)); pre-selected as above. Empty: **OpenCode** when the template needs a coding agent, else **Model answer**. With the agent come its instructions, rules, skills, MCP servers and access. The step shows a problem when the agent does not fit the template (a template that needs a coding agent with a model answer agent: "The template needs a coding agent: this step cannot run with a model answer agent."; or an agent with access **Read only** on a step that pushes). |
| **Agent may ask questions**, **Question rounds** | On by default, 2 rounds: the agent may stop with questions and the run waits for your answers ([Agents asking questions](#agent-questions)). |
| **Model profile** | Model for this node. Empty: the agent's model, else the defaults for its kind (project, starting user, platform). |
| **Config** | Settings for the step's agent such as the verify command, branch name or merge request options; merged over the template's config. |

The directory and the session are not step settings any more (ADR 0062): they belong to the agent (**Directory**,
**Session**; [The run directory and agent sessions](#run-directory)). The sandbox is set on the project
([Sandbox policy](projects.md#sandbox-policy)), never on a step or a template.

A step no longer adds or drops skills of its agent (ADR 0060): the agent's environment is set once, on the agent.
Steps saved with such a change run with the agent's skills; when a step needs other skills, choose or create an
agent that has them.

The coding agent used to be the config key `agent` (`CLAUDE_CODE`, `OPENCODE`); that key no longer exists. Stored
workflows keep working: a step's `agent` is read as its **Agent** (**Claude Code**, **OpenCode**), and the next save
writes it that way.

**Spec Kit templates** (**Spec Kit: specify**, **plan**, **tasks** and templates that extend them) appear in the
palette only when the project has [Spec Kit](projects.md#spec-kit) on; otherwise a Spec Kit step is a validation
error and runs refuse to start.

**Workflows stored before ADR 0061.** Templates used to name an agent and a model profile. On the upgrade every
stored step without an agent of its own got the agent its template (or the nearest parent) named, and the
template's model profile when the step had none and the agent has no model of its own; library workflow versions
and runs still waiting were updated the same way. Nothing changes in how those steps run, and the **Agent** field
now shows the agent explicitly.

**Workflows stored before ADR 0062.** The upgrade (database migration V308) rewrote stored steps, library workflow
versions and runs that had not finished:

- the step's former **System prompt append** and **User prompt** append became **Instructions for this step** →
  **Append** (joined by a blank line), a former **Replace** became **Replace (advanced)**;
- the step's former parameter values became **Value** sources;
- every former run input of the step's template became a parameter bound to **Ask at start** on that step, so the
  start form asks for the same fields as before;
- the step settings **Directory** and **Agent session** (with "continue the session of step") were removed: they
  are the agent's now. Steps that shared an agent role (from before ADR 0060) keep sharing their session.

In the library, each template's system and user prompt were joined into its **Instructions** (system first, a blank
line between), and its run inputs became parameters. The built-in templates were rewritten as instructions without
role sentences.

<a id="agent-audit"></a>
### Agents in this workflow

**Agents in this workflow** in the designer's header opens a panel that checks the agents of the draft on the
canvas, as it is, before any run (ADR 0061). No model is called and nothing is changed; the panel follows your
edits. The badge on the button counts its problems.

For each agent the draft's task steps use, the panel shows its kind (**Model answer**, **OpenCode**, **Claude
Code**), **Global** or **This project**, and:

| Row | Content |
|---|---|
| **Steps** | the steps it runs; click one to select it on the canvas |
| **Rules** | its rules, automatic ones included |
| **Skills** | its skills |
| **Required skills** | the skills its steps' templates require, with the steps that need them; missing ones are marked and have **Add to agent** |

Findings, each with the steps concerned (**ERROR** stops the step from running, **WARNING** does not):

| Finding | Severity | Means |
|---|---|---|
| **Missing skill** | warning | a step's template requires a skill the agent lacks |
| **Needs a coding agent** | error | a step whose template needs a coding agent is run by a model answer agent |
| **Read-only agent on a pushing step** | error | an agent with access **Read only** runs a step that pushes |
| **Unknown rule**, **Unknown skill** | error | the agent lists a rule or skill that no longer exists |
| **Unknown agent** | error | a step names an agent that no longer exists |
| **Two skills with the same name** | warning | two of the agent's skills share a name, so the agent cannot tell them apart |

**Add to agent** adds the missing skill to the agent once, for every step and run (needs
`library.project.manage` on the project). A project agent gets the skill directly. A global agent is first copied
into the project: the confirmation **Copy {agent} into this project?** explains that the project gets its own copy
with the same key and the skill, which replaces the global agent in this project's steps while other projects keep
theirs; **Copy and add** does it. Skills are never added at run time.

<a id="coherence-check"></a>
#### Check coherence

**Check coherence** (per agent) asks a model to read the agent's whole environment and list where it contradicts
itself. It is a model call, so it runs only when you ask and after a confirmation (**Check the coherence of
{agent}?**); its cost is booked as usage and shown with the result. The model is the platform default for model
answers; without one the check answers "No model is set for model answers: an administrator sets the platform
default under Models, then try again." (HTTP `409`).

The model reads the platform preamble, the agent's instructions, the project constitution, the automatic and the
agent's rules, the names and descriptions of its skills and, when Back Office can read them, the primary
repository's `AGENTS.md` / `CLAUDE.md`; the result lists what was read. Each finding is a **Contradiction**, an
**Ambiguity** or a **Redundancy**, with quotes from the sources involved and, where the model has one, a
**Suggestion:**. "No contradictions found." means the model found none. Fix contradictions where they live (the
rule, the skill, the agent's instructions or the repository file); the order the agent follows meanwhile is in
[When sources disagree](task-library.md#precedence).

<a id="actions"></a>
### Action nodes

Actions are fixed operations that never call a model (ADR 0056). The palette lists them in their own group
**Actions**; each has its settings, defaults, the run inputs it asks for and the ports `success` and `failure`.

| Action | Does |
|---|---|
| **Fetch issue** | Loads the run's Jira issue into the `issue` artefact and `state.issue`; asks for the **Jira issue key** when a run starts. |
| **Comment on issue** | Posts a comment on the issue, after a person confirmed it ([Confirmations before external writes](#write-confirmations)). |
| **Transition issue** | Moves the issue to a status, after a person confirmed it. |
| **Start workflow** | Starts other workflows of the project (see below). |
| **Project digest** | Writes the `digest` artefact (see below). |

The Jira actions have a **Jira connection** section with **Act as**: the project's system connection, a specific
connection, or the person who approves the write or starts the run ([Act as](integrations-jira.md#jira-act-as));
the palette shows the capabilities each needs and marks the two that write to Jira. Their settings are described
in [Jira actions in workflows](integrations-jira.md#jira-tasks).

These used to be task templates (`fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow`,
`project-digest`). Stored workflows that use them keep working: such a step is read as the action with the former
template's defaults and the step's configuration (a replaced prompt of a **Notify Jira** step becomes the
comment), and the next save writes the action.

Two actions are Back Office operations (ADR 0052):

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

Instructions and text settings are templates: `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}` (this
step's revision comments),
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

Coding steps (task steps run by a coding agent, **OpenCode** or **Claude Code**, Spec Kit phases included) work in
Git repositories of the project ([Repositories](projects.md#repositories)). All other steps (model answers, actions,
gates, CI waits) use no repository, so their properties have no repository section.

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

**Folders.** Each repository is cloned into its own folder named after its key: `/workspace/service`,
`/workspace/docs`, ... (in the [run directory](#run-directory), or in the step's own directory when its agent works
in a **Separate directory**). The agent starts in the step's main repository (the project's primary one if the step
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

<a id="run-directory"></a><a id="workspaces"></a>
## The run directory and agent sessions

Every run has one **run directory** (ADR 0060). The coding steps of the run work in it one after another: each step
sees what the earlier steps left there, uncommitted work included, and the agents keep their sessions there. You do
not define or name anything: *implement* → *verify* → back to *implement* simply continue each other's work.

**Setup command.** In the workflow properties (click the empty canvas) **Setup command**, for example `npm ci`, runs
once in the run directory before the first coding step, and again only when you change it. Empty = no setup. A step's
own `setupCommand` in **Config** still runs before that step.

**Directory of the agent.** Whether a coding step works in the run directory is a setting of its agent, **Directory**
([Access of an agent](task-library.md#agent-access), ADR 0062):

| Choice | Meaning |
|---|---|
| **Run directory (shared)** (default) | The step works in the run directory. Its repositories are cloned there once; later steps reuse the clones and everything left in them. |
| **Separate directory** | A fresh copy of only this step's repositories, thrown away after the step. Use it for isolation: an agent that must not see the rest of the run's work (or, with **Repositories** set to other repositories, not the code at all). Such a step always starts a new agent session. |

To run one step in isolation, choose an agent with **Separate directory** for it.

**Agents taking turns.** For example *implement* (push on) → *verify* (push off) → a decision → back to *implement*.
Each agent continues its own session and sees what the other left: the reviewer reads the implementer's uncommitted
changes, the implementer finds the reviewer's notes. A step without push leaves its changes uncommitted for the next
step; a pushing step commits everything in its repositories and pushes the run branch.

**What happens on the runner.** The run directory lives on the runner that ran the first coding step, as a Docker
volume or a Kubernetes volume claim; the run's coding steps run there, one after the other. Before each step the
runner checks every reused repository and rewrites its git configuration, so nothing a previous agent planted in
`.git` runs.

**When the run ends** (succeeded, failed or cancelled) the run directory is removed; after a failed run it is kept
for 24 hours so **Retry** continues in it. A run directory used after that starts empty (the pushed run branch is
still there).

**Mind the trust.** All steps of a run can read and change everything in the run directory, including the other
agents' homes and session history. For an independent check use an agent with **Separate directory**.

**Where an agent's own files live.** Each agent of a run has a **home** in the run directory, outside every
repository: its environment document, its skills, its MCP configuration and its sessions
([The environment of the agent](task-library.md#environment)). Nothing of an agent is written into a repository, so
two agents in one run directory never see each other's skills or memory. The repository's own `AGENTS.md` and
`CLAUDE.md` are the project's rules for every agent.

**Former run workspaces.** Until ADR 0060 a project defined run workspaces (the project tab **Workspaces**), steps
picked one and the canvas drew a region around them. They are gone, and existing workflows were migrated: a
workspace's setup command became the **Setup command** of the workflows whose steps used it, and its sources became
the **Repositories** of those steps that had none of their own. A stored step's workspace is ignored (every step
works in the run directory); steps that shared an agent role keep sharing their session. The built-in task **Prepare
workspace** (`prepare-workspace`, deprecated) clones the step's repositories into the run directory and runs the
setup without an agent; it is not needed, the first coding step prepares the run directory too.

<a id="continue-session"></a>
### Agent sessions

A session is a coding agent's conversation, so it is a setting of the agent, **Session** (ADR 0062), in
**Library** → **Tasks** → **Agents**:

- **Per step (repeats continue)** (default): the first execution of a step starts a session; when the step runs again
  in the same run (rework after a review, a gate's **Revise**, answers to its questions), the agent continues its own
  conversation and remembers what it did.
- **One per run**: all steps of this agent in a run are one conversation, for example *implement*, then *fix what the
  review found*, with the agent keeping its context without re-reading the repository (ADR 0057).
- **Always fresh**: every execution starts a fresh conversation.

The session is kept in the agent's home, so only steps of the **same agent** share it; an agent with **Separate
directory** never continues a session. The agent resumes its conversation (Claude Code `--resume`, OpenCode
`--continue`) and sees the files it left. Its rules and instructions are in its environment, not in the task
message, so it does not get them a second time. In the **JSON** view of an agent the settings are `session`
(`PER_STEP`, `PER_RUN`, `FRESH`) and `access.directory` (`RUN`, `SEPARATE`).

Until ADR 0062 the session was a setting of the step (**Agent session**: **Own**, **New each time** or **Continue the
session of step …**, keys `config.session` and `config.continueSession`) and so was `config.directory`. The upgrade
removed them from stored steps, and steps that still carry them are read without them. To keep several steps in one
conversation, give their agent **One per run**.

<a id="agent-questions"></a>
### Agents asking questions

When an agent cannot do a step well without your answers, it stops with questions instead of guessing (ADR 0060).
A coding agent writes them to `agent-questions.json` in its working directory (collected, never committed); a model
answer replies with `{"agentQuestions": [...]}` instead of its answer. Every agent is told how to ask.

The run then waits: the step shows **waiting for your answers**, and **Your turn** gets the card **The agent asks**
with the questions (each with the agent's reason, **Why:**) and the round, for example **Round 1 of 2**
([Answering an agent's questions](your-turn.md#agent-questions)). **Answer and continue** runs the step again: your
answers are added to its prompt (section "Answers of the owner") and a coding agent continues its own session.
**Cancel the step** fails the step, and the run follows its `failure` path.

| Setting (under **Overrides**) | Meaning |
|---|---|
| **Agent may ask questions** | On by default. Off: the agent is told it cannot ask and must record open questions in its output instead. Runs started by CI keep it on and wait like at a gate. |
| **Question rounds** | Default 2 (1-10): how often the step may stop with questions. An agent that asks again after the last round fails the step; its questions are written to the step log. |

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

Action nodes that write outside Back Office ask a person to confirm the exact write before it happens. Today
these are the Jira actions **Comment on issue** and **Transition issue** (a status change); the
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
2. Fill in the form. Its fields are the parameters the workflow's task steps **Ask at start** (label, type,
   **Required** and help from the parameter; see [Where the value comes from](task-library.md#parameter-sources))
   and the run inputs of its action nodes, for example the **Jira issue key** of **Fetch issue**. The same name in
   several steps is one field: required when any step requires it, the first label wins. A text area takes longer
   text such as **Input material**, a single line short values; a number, yes / no or choice parameter is a single
   line whose value is checked against the parameter (a choice field lists its options in the help). Fields marked
   `*` are required; every field takes at most 20 000 characters. A **Jira issue key** field (for example
   `SHOP-123`) is the run's issue key.
3. Optional: open **Advanced (JSON)** to add extra parameters as a JSON object, for example `{"depth": 2}`.
   It is for values no step declares; the issue key and the form's fields are set in the form.
4. Click **Start run**. The run page opens.

Expressions read the values as `input.parameters['name']`; the issue key is `input.issueKey`. A start through
the REST API (`POST /api/v1/workflows/:id/runs`, also with a personal access token) is checked the same way: a
missing required input, or an issue key not shaped like `ABC-123`, is refused with a message that names the field.
A Jira step that reads the key from another field (**issueKeyFrom**) does not make the issue key required. Starts by CI, the Jira intake watcher, your
personal intake and the MCP tool `start_workflow` are not checked.

In the designer, the **Start** node lists the start form read-only ("The start form asks for the parameters the
steps take from the start and for the run inputs of the actions (saved version). Change them on the steps, not
here."): to add a field, set a parameter of a step to **Ask at start**.

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
