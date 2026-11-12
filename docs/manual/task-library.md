# Task library

The task library holds what agents do and how they work, separately from the workflows that use it.
**Library** has three groups (ADR 0055, 0056):

- **Tasks**: **Templates**, **Skills**, **Rules** and **Agents**, this chapter;
- **Workflows**: workflows shared between projects ([Workflow library](workflow-library.md));
- **Sources**: material steps can attach ([Sources](#sources)).

## How the pieces fit

| Piece | Think of it as | You use it to |
|---|---|---|
| Task template | the assignment: **what** to do | describe the work and nothing else: category, one **Instructions** text with [parameters](#parameters), config, outputs and the skills the work needs; it may be based on one parent |
| Agent | the worker: **who** works and **how** | name a way of working: the harness (**Model answer**, **OpenCode** or **Claude Code**), optionally a model, **Instructions**, **Rules**, **Skills** and **MCP servers** ([Agents](#agents)) |
| Rule | a standing order | state something the agent must always follow; always in the agent's context |
| Skill | a handbook chapter | give the agent knowledge or a procedure it opens when the task needs it (a `SKILL.md` file) |
| Workflow step | the job on the board | join the two: a template, the agent that does it (and optionally a model), where each of the template's parameters takes its value from, and notes for this step ([Task nodes](workflows.md#task-nodes)) |
| Resolved task | an instance | what the agent finally receives: the template with its ancestors, project overrides and the step's overrides merged, in the environment of its agent |

The template changes when the work changes; the agent changes when you look for a cheaper or better way to work.
Keeping them apart lets you run the same task with different agents and compare them
([Evaluation](evaluation.md)) without touching the task (ADR 0057).

Work that needs no model (fetching or commenting on a Jira issue, changing its status, starting workflows, the
project digest) is not in the library: it is an **action** node in the workflow designer
([Actions](workflows.md#actions)).

You never type keys: a template, skill, rule or agent gets one from its name when you create it (for example
**Analiza zgłoszenia** becomes `analiza-zgloszenia`), and it stays when you rename the entry. Workflows, MCP
and the API use the key; the editors show it small in **Advanced**.

A workflow's task node refers to a template by its key and holds at most notes for its step. Editing a template
therefore changes every workflow that uses it, from the next run on. Runs already in progress keep what
they resolved.

### Who may change what

| You want to | Global library | One project's own entries |
|---|---|---|
| See templates, skills, rules, agents and the preview | `library.view` (every **Member**) | `project.view` on the project |
| Create, edit or delete templates, skills, rules and agents | `library.manage` (role **Library curator**) | `library.project.manage` (project **Owner**, **Maintainer**) |

Without the right permission the editors open read-only. Every change is recorded in the audit log, and
the lists show who changed an entry last.

<a id="templates"></a>
## Templates

**Library** → **Tasks** → **Templates** (`/library/templates`) lists the templates grouped by **Category**:
Analysis, Specification, Planning, Implementation, Review, Jira, Operations and General (templates without a
category). The workflow designer groups its palette the same way. Built-in templates are marked; so are abstract
ones, which can be a base for others but never run. Filter by text, category and the kind of agent a template
needs (**Needs a coding agent** / **Any agent**; empty: **Any kind of agent**), and with **Global templates** /
**Global + KEY** to include a project's own templates.

A template is only the work (ADR 0061). It names no agent and no model: the [workflow step](workflows.md#task-nodes)
chooses who does the work, so the same template can run with different agents and be compared
([Evaluation](evaluation.md)). What a template still says about its worker:

- **Needs a coding agent** (a read-only tag in the list, the editor, the designer palette and the step): its
  configuration, with that of its parents, has a Spec Kit `phase`, `push` or `prepareOnly`. Such work needs an agent
  with the harness **OpenCode** or **Claude Code** (a coding agent in a sandbox on a runner, with the repository; may
  push a branch and open a merge request). You do not set the tag; it follows from the configuration. Without it any
  agent can do the work, including one with the harness **Model answer** (one model call inside Back Office, no
  repository; can return structured JSON and a confidence).
- **Required skills**: the [skills](#skills) the work needs (see [Required skills](#required-skills)).

A template holds no rules: rules are norms of behaviour and belong to the repository, the project and the agent.
What one task demands ("answer in Polish", "touch only the `docs` folder") belongs in the template's **Instructions**.
A fourth source of norms would only make contradictions more likely ([When sources disagree](#precedence)).

A template whose required [parameters](#parameters) have no value yet carries the tag **needs parameters**: it is a
task to complete in the workflow step. The others are ready blocks.

### Steps: create a template

1. Click **New template** (needs `library.manage`, or `library.project.manage` for a project template).
2. **Type**: **Name**, **Description**, **Based on** (optional, one parent: the template inherits its instructions,
   parameters and configuration and changes only what you set), **Category** (empty means inherited), **Scope**
   (**Global** or **Project** with the **Project**) and **Required skills** (skills the work needs, inherited and
   added up along the chain; see [Required skills](#required-skills)). There is no agent and no model profile here:
   the workflow step chooses them. When the configuration needs a coding agent, the editor's header shows the tag
   **Needs a coding agent** (hover it for the reason).
3. **Instructions**: one text that says what to do and when the result is good enough (the acceptance criteria).
   A template with a parent first chooses **Append to parent** (its text comes after the parent's) or **Replace
   parent** (only its own text counts). You do not write a role ("You are a reviewer"): the role is the agent's. You
   do not paste the data either: the platform adds the issue, the results of earlier steps, the values given at run
   start, the reviewer's feedback and the owner's answers itself (see
   [Instructions and what the agent receives](#instructions)). Write what this task demands into the instructions.
4. **Parameters**: the inputs of the work (see [Parameters](#parameters)). **Add parameter**, then set the name, the
   label, the type, **Required**, an optional default and help text. The workflow step decides where each value comes
   from: a value set in the step, a field of the run start form, or the result of an earlier step. A template based on
   another one also shows **Parameter values**: values for the parameters of its parents.
5. **Configuration**: a form with the settings of the work, for model answers and coding agents together (for a
   coding agent: verify and setup command, push, merge request, artefacts to collect, agent tools, Spec Kit phase,
   ...). A Spec Kit `phase`, `push` or `prepareOnly` (prepare only) makes the template need a coding agent. Set only what differs from the
   template it is based on; empty fields are inherited. **Raw JSON** shows the same settings as JSON, also keys the
   form does not know.
   **Advanced: artefacts, output schema** (collapsed; most templates need nothing here):
   **Abstract** (only a base for other templates, never a step), **Produces** and **Consumes** (artefact names the step writes and reads; the workflow
   check warns when a step reads something nobody writes), **Confidence field** (a field of the answer copied
   to `state.confidence` for decisions such as `confidence < 0.7`), **Tags** and **Output schema** (the shape of a
   structured answer: a model answers with it, a coding agent writes it to `structured-answer.json`, see
   [Agents](#agents)). A template has no sandbox settings: the sandbox is set on the project
   ([Sandbox policy](projects.md#sandbox-policy)).
6. Click **Create template** (or **Save template** for an existing one).

<a id="instructions"></a>
### Instructions and what the agent receives

A template has one **Instructions** text (ADR 0062). There is no system prompt and no user prompt any more: the
stable frame of a call (who works, which rules, where) is the agent's, and a template that wrote it again only
repeated or contradicted its agent. The platform puts every call together the same way:

| Agent | Frame (who, which rules, how to answer) | Task |
|---|---|---|
| Model answer | the system prompt: the [environment of the agent](#environment) (platform preamble with how to ask questions, the agent's instructions, the project constitution, the rules), its skills, then the answer format (the output schema) | the message: the template's **Instructions** with the step's notes, then the **standard data sections** |
| OpenCode, Claude Code | the environment document in the agent's [home](#agent-home), its skills there | the prompt: the same message, then the answer format (when the template has an output schema) and the list of the step's repositories |

The standard data sections, each only when it has content and the instructions do not place it themselves with its
placeholder:

| Section | Placeholder to place it yourself |
|---|---|
| the issue (key, summary, description, acceptance criteria) | `{{issue.*}}`, for example `{{issue.summary}}` |
| each artefact the template consumes (**Consumes**), a diff last | `{{artifacts.<name>}}`, for example `{{artifacts.spec}}` |
| each parameter value given at run start that the instructions do not use | `{{params.<name>}}` |
| the reviewer's feedback for this step | `{{feedback}}` |
| the owner's answers to the agent's questions | `{{answers}}` |

So the instructions can be just the task: "Review the change against the specification. Every requirement gets
MET, PARTIAL or MISSING; one MISSING makes the verdict REQUEST_CHANGES." A template that writes `{{issue.key}}` or
`{{artifacts.spec}}` somewhere gets no second copy of that section. Values from the run (the issue, artefacts,
values typed at start, feedback, answers) always reach the agent as marked data blocks, never as instructions
([Prompt injection](agent-execution.md#prompt-injection)).

More placeholders: `{{params.<name>}}` (a [parameter](#parameters)), `{{project.*}}`, `{{input.ci.*}}`,
`{{decision.comment}}` (the comment of an **Approve**), `{{feedbackHistory}}` (all revision comments of the run). A
section `{{#artifacts.review}}...{{/artifacts.review}}` keeps its text only when the value is there
(`{{^path}}...{{/path}}` only when it is not). `{{feedback}}` holds only the revision comments given for this step:
the comment of a gate option that sends the work back (**Revise**, **Retry** or any option other than **Approve**
and **Reject**) goes to the next step that runs, stays with that step for its later attempts, and never reaches the
steps after it. The project constitution and the rules are not placed by the template: every step gets them in its
agent's environment.

A workflow step can add **notes** to the instructions or, rarely, replace them
([Instructions for this step](workflows.md#task-nodes)).

### Merging with the parent

| Field | Rule |
|---|---|
| Category | The nearest one in the chain wins. |
| Output schema, confidence field | The child's value wins when set. |
| Instructions | **Append to parent** adds the child's text after the parent's; **Replace parent** uses only the child's. An empty text keeps the parent's. |
| Parameters | Union by name; a child's parameter replaces the parent's parameter with the same name. |
| Parameter values | The nearest template that sets a value wins. |
| Config | Merged key by key; the child wins; `null` deletes a key. |
| Produces, consumes, tags, required skills | Union. |

Rules and skills are not inherited from templates: the step gets those of its agent. Whether the template needs a
coding agent follows from the merged configuration.

<a id="required-skills"></a>
### Required skills

**Required skills** name the skills the work needs, for example `user-story-format` for **Write user story**. The
template does not give them to anyone: a step gets exactly the skills of its agent. They serve three purposes:

- the designer pre-selects, for a dropped template, the agent that has most of them
  ([Task nodes](workflows.md#task-nodes));
- a step whose agent lacks one gets a warning (**Missing skill**); it still runs;
- the designer's panel [Agents in this workflow](workflows.md#agent-audit) lists the missing ones per agent and adds
  them with **Add to agent**, once, before any run.

Skills are not added at run time on purpose: the agent's environment would then differ from step to step, and
comparing one agent across steps and benchmarks would no longer be fair.

<a id="parameters"></a>
### Parameters

Sometimes a template is a ready block ("write a user story"), sometimes a task with room for configuration
("summarise the material for this audience"). Parameters are the inputs of the work (ADR 0058, 0062): typed fields
read by the instructions and by text settings as `{{params.<name>}}`. A template only declares them; the workflow step
says where each value comes from.

| Type | Value |
|---|---|
| **Single line** | a short text |
| **Multi-line text** | a longer text, for example a whole task description |
| **Number** | a number |
| **Yes / no** | a switch |
| **Choice** | one of the options you list (the parameter needs at least one) |
| **Jira issue key** | an issue key such as `SHOP-123`; asked at start, it is the run's issue key |

A parameter has a name (starts with a letter; letters, digits and `_`, at most 64), a label (at most 200
characters), a type, **Required**, an optional default and an optional help text (at most 1 000 characters).

<a id="parameter-sources"></a><a id="run-inputs"></a>
#### Where the value comes from

The workflow step chooses, per parameter ([Task nodes](workflows.md#task-nodes)):

| Source on the step | The value |
|---|---|
| **Value** | the value set in the step, fixed when the workflow is designed |
| **Ask at start** | whoever starts the run types it into the start form; the field has the parameter's label, type, **Required** and help |
| **Result of an earlier step** | the content of an artefact an earlier step produced, by its name (for example `spec`) |
| none | the value of the nearest template in the chain (a preset, below), else the parameter's default |

When a run has no value for a parameter asked at start or bound to a result, the preset value or the default
applies. A required parameter without any value blocks the step: the workflow check names it and the run does not
start; the preview still works. Templates whose required parameters have no value carry the tag **needs
parameters** in the list and in the designer palette. The **Parameters** tab of the preview shows each parameter
with its value and where the value comes from (a template, the default, or "the step must set it").

Values that come from the run (typed at start or taken from a result) come from outside the workflow's design: the
agent gets them as marked data, never as instructions. Values asked at start that the instructions do not use reach
the agent anyway, each in a section under its label ([Instructions and what the agent receives](#instructions)).

**Templates no longer declare run inputs.** Until ADR 0062 a template declared its own "run inputs" next to its
parameters. A template cannot know whether a value is set by the designer or by whoever starts the run, so this is
now the step's choice: a parameter bound to **Ask at start** is what a run input was. The upgrade turned every run
input into a parameter of the same name (types **Single line**, **Multi-line text** or **Jira issue key**; a `-` in
the name became `_`) and bound it to **Ask at start** on every existing step of that template, so start forms
stayed the same. Most built-in templates have the parameter **Input material** (`input`, multi-line, optional),
which the default workflows ask at start.

**Create preset.** In the editor of a saved template, **Create preset** (at the bottom) opens a new template that
is based on this one and only sets **Parameter values**. Save it under a name of its own, for example "Summary for
the steering committee", and it appears in the palette as a ready block next to its parent.

### The resolved preview

The right side of the editor shows **Resolved preview**: exactly what an agent receives after inheritance
and project shadowing, for the global library or for a project you pick. It shows the lineage and category, and
tabs **Instructions** (merged along the chain), **Skills** and **Rules**, **Config** and **Parameters**. Since a template has
no agent, **Skills** and **Rules** show the environment of the agent a step without its own agent would get
(**OpenCode** when the template needs a coding agent, else **Model answer**; automatic rules are marked); a step
with another agent gets that agent's. If a required parameter has no value, the preview lists it, and the task
cannot run until it is fixed. The preview reflects the saved version; unsaved changes are listed as deltas.

### Steps: change a task for one project

1. On the template's row, click **Customize for project**.
2. A new template opens with scope **Project** and the global template as parent; it keeps the global template's
   key, so it replaces the global one in that project. Pick the
   project and change only what differs, for example append a paragraph to the instructions. (The agent is chosen
   on the workflow step, not here.)
3. Save. From now on, that project's workflows use the project template wherever the key appears,
   including as a parent of other templates. Other projects keep the global one.

Built-in templates cannot be deleted; extend them per project instead. A template another template
extends cannot be deleted either.

<a id="rules"></a>
## Rules

**Library** → **Tasks** → **Rules** (`/library/rules`) lists what agents must always follow: conventions of a
code base, things that are never allowed, how to report. A rule differs from a skill in one point: **a rule is
always in the agent's context, a skill is opened by the agent when it needs it.** Put a norm in a rule ("the
domain never imports Spring"); put knowledge or a procedure in a skill ("how to write an ADR").

How a rule reaches the agent: as part of the [environment of the agent](#environment), the document every step
gets before its task. A model answer finds it in its system prompt; OpenCode and Claude Code read it as their
standing instructions, outside the repository. The task message does not repeat the rules, so an agent that
[continues its session](workflows.md#continue-session) does not get them twice.

Which rules a step gets:

| Rule | Applies to |
|---|---|
| **Apply automatically to every step**, global | every step of every project |
| **Apply automatically to every step**, project | every step of that project |
| Not automatic | the steps run by an agent that lists it under **Rules** |

Automatic rules come first (global, then project), then the agent's. An automatic rule applies to every step of
its scope; no template or agent can drop it. A project rule with the key of a global rule replaces it for that
project (made with **Customize for project**); when the project's copy is not automatic, the global automatic rule
is off in that project.

### Steps: create a rule

1. Click **Create rule**.
2. Enter **Name**, **Description**, **Scope** (**Global** or **Project**), **Tags** and, if every step of the
   scope must follow it, switch on **Apply automatically to every step**.
3. Write the rule in markdown: short, concrete, checkable sentences.
4. Click **Create rule**.

A rule an agent lists cannot be deleted: remove it from those agents first. Built-in rules: `agent-ground-rules`
and `ask-dont-guess` (every built-in agent lists them), `kotlin-hexagonal` and `angular-ng-zorro`
(the conventions of the Back Office code base itself: copy them into a project rule for your stack and make
that one automatic).

<a id="agents"></a>
## Agents

**Library** → **Tasks** → **Agents** (`/library/agents`) lists who can do the work of a task (ADR 0056, 0057). The
page says it in one line: "An agent is who works and how: harness, model, instructions, rules and skills. A task
template is what to do; a workflow step joins the two."

**Why create agents.** An agent gives a way of working a name: "careful reviewer on a strong model", "cheap
implementer with our stack rules", "analyst who writes in our formats". Choose it on any step, whatever the
template. Because the agent is complete (harness, model, instructions, rules, skills, MCP servers), you can run
the same task with two agents and compare cost and quality on equal terms ([Evaluation](evaluation.md)); the
template stays the same.

| Field | Meaning |
|---|---|
| **Kind** (the harness) | **Model answer**: one model call inside Back Office without tools or a repository. **OpenCode** or **Claude Code**: a coding agent session in the runner sandbox, in the [run directory](workflows.md#run-directory). |
| **Model** | Optional. Empty means the step's model or the defaults for its kind: the project's, then the starting user's, then the platform's. |
| **Instructions** | The agent's role and way of working, in markdown, for example "You are a careful reviewer. Explain every finding with a file and a line." It may use `{{project.*}}` values and comes after the platform preamble. |
| **Rules** | [Rules](#rules) the agent always follows, after the automatic ones. |
| **Skills** | [Skills](#skills) the agent opens when it needs them. |
| **MCP servers** | Remote MCP servers (a name and an `http://` or `https://` URL) the agent may use. Coding agents only: a model answer has no tools. See [MCP servers of an agent](#agent-mcp). |
| **Access** | Coding agents only: what the agent may change in the repositories of its steps: **Full** (default), **Read only** or **Only these paths**. See [Access of an agent](#agent-access). |
| **Directory** | Coding agents only: **Run directory (shared)** (default) or **Separate directory**. See [Access of an agent](#agent-access). |
| **Session** | Coding agents only: **Per step (repeats continue)** (default), **One per run** or **Always fresh**. See [Agent sessions](workflows.md#continue-session). |
| **Name**, **Description**, **Scope**, **Tags** | As for rules; the key is derived from the name. |

The list shows each agent's **Kind**, **Model**, **Access**, **Session** and **Environment** (how many rules and skills it has).
A project's own agents and the global ones it can use are also on the project tab **Agents**
([Agents of the project](projects.md#project-agents)).

The environment is set once, on the agent (ADR 0060): a step chooses an agent but does not add or drop its skills.
For a variant, make another agent (or a project copy).

Built-in agents (none has a model of its own; all list the rules `agent-ground-rules` and `ask-dont-guess`):

| Agent | Kind | Skills | Good for |
|---|---|---|---|
| **Model answer** (`model-answer`) | Model answer | none | `llm-task`; a step without its own agent whose template does not need a coding agent |
| **Analyst** (`analyst`) | Model answer | `user-story-format`, `use-case-format`, `adr-format`, `definition-of-ready-done`, `build-guardrails` | the analysis, specification and planning templates (`write-user-story`, `write-use-case`, `write-adr`, `impact-analysis`, `plan-task-list`, `clarify-spec`) |
| **Reviewer** (`reviewer`) | Model answer | `security-review-checklist`, `definition-of-ready-done` | `code-review`, `review-against-spec` |
| **OpenCode** (`opencode`) | OpenCode | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | Spec Kit phases, `implement-opencode`, `implement-task-list`, `prepare-workspace`; a step without its own agent whose template needs a coding agent |
| **Claude Code** (`claude-code`) | Claude Code | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | `agent-task`, any coding work |

The built-in templates' **Required skills** match these agents, so the designer pre-selects them. **Analyst** and
**Reviewer** come with short instructions for their role. Built-in agents cannot be changed; **Customize for
project** makes a project copy you can change (for example another model or the project's stack rules). Agents
have the same permissions, scopes and audit as rules. Templates name no agent, so deleting an agent is never blocked
by a template; workflow steps that still name it show **Unknown agent** in
[Agents in this workflow](workflows.md#agent-audit) and cannot run until you choose another agent.

**Who runs a step** (ADR 0061): the **Agent** chosen on the step in the designer. A step without its own agent (for
example one stored before agents existed) runs with **OpenCode** when its template needs a coding agent, else with
**Model answer**; the designer shows this as **Default: {agent}**. When you drop a template on the canvas, the
designer pre-selects an agent for you ([Task nodes](workflows.md#task-nodes)).

**Which model**: the step's **Model profile**, else the agent's **Model**, then the defaults for the agent's kind
(project, starting user, platform). Templates have no model.

**How the step runs** follows from the agent and the template:

| Agent | Template | The step |
|---|---|---|
| Model answer | any that needs no coding agent | one model call: structured output, confidence and injection guard |
| OpenCode or Claude Code | with a Spec Kit `phase` in its configuration | the Spec Kit phase |
| OpenCode or Claude Code | any other | an agent job: pushes and opens a merge request per configuration; without a push its final answer becomes the `result` artefact |

The step cannot run, and the designer and the preview say why, when its template needs a coding agent (a Spec Kit
`phase`, `push: true`, `prepareOnly`) but the step's agent is a model answer agent ("The template needs a coding
agent: this step cannot run with a model answer agent.").

**Coding agents answering in a schema.** A template with an **Output schema** works with a coding agent too. The
agent is told to write its answer as one JSON object to `structured-answer.json` in its working directory. The
runner collects the file (artefact `structured-answer`) and keeps it out of the commit; Back Office checks it
against the schema like a model answer, copies the confidence field and stores the fields named in **Produces**
as artefacts. A missing or invalid file fails the step with the reasons.

<a id="environment"></a>
### The environment of the agent

Before its task, every step gets one document that describes where and how it works, in this order:

1. the **platform preamble**, fixed by Back Office for the kind of agent: what Back Office is, honesty, scope,
   safety; for a model answer "no repository, no tools, only JSON when there is a schema"; for a coding agent
   "the runner commits and pushes, never weaken a test, quote file paths, where your skills are"; when the step
   lets the agent ask ([Agents asking questions](workflows.md#agent-questions)), also how to ask;
2. the agent's **Instructions**;
3. the project's constitution, when the project has one;
4. the rules: the automatic ones (global, then project), then the agent's.

A model answer gets it at the start of its system prompt, then its skills (appended, because a single model call
cannot open files), then the answer format. The template adds nothing to the system prompt: its
[instructions](#instructions) are the task message.

<a id="agent-home"></a>A coding agent gets a **home** of its own in the [run directory](workflows.md#run-directory),
outside every repository: `/workspace/.agents/<agent key>/`. Everything of the agent lives there (ADR 0060):

| Agent | The home is | It holds |
|---|---|---|
| Claude Code | its configuration directory (`CLAUDE_CONFIG_DIR`) | `CLAUDE.md` (the environment, as Claude Code's user memory), `skills/<key>/SKILL.md`, `mcp.json` (its MCP servers), `settings.json` (tool rules, only with a tool policy or a restricted access) and Claude Code's own session files |
| OpenCode | its configuration directory (`OPENCODE_CONFIG_DIR`) | `opencode.json` (lists `AGENTS.md` under `instructions`, plus its MCP servers and edit permissions), `AGENTS.md` (the environment), `skills/<key>/SKILL.md`, and its sessions under `data/<session>` |

Nothing of an agent is written into a repository any more, so nothing ends up in a commit, and two agents working
in one run directory never see each other's skills or memory. The skills are written again on every job, so an
agent always works with its current environment. The repository's own `AGENTS.md` and `CLAUDE.md` stay untouched:
the harness reads them as usual, and they are the project's rules for every agent, next to Back Office's
environment. OpenCode also reads the repository's own skills (`.opencode/skills`, `.claude/skills`): they are project
skills, available to every OpenCode agent.

<a id="precedence"></a>
### When sources disagree

An agent hears from several sources: the project constitution, the repository's own `AGENTS.md` / `CLAUDE.md`, the
project's and the agent's rules, its skills and the task. The platform preamble states the order when they
contradict each other (ADR 0061):

1. the project **constitution**;
2. the **rules**: the repository's `AGENTS.md` / `CLAUDE.md`, then the project's rules, then the agent's;
3. the **skills**;
4. the **task** (the template's instructions and the step's notes).

When the agent cannot resolve a contradiction, it asks ([Agents asking questions](workflows.md#agent-questions))
instead of guessing. To find contradictions before a run, use **Check coherence** in
[Agents in this workflow](workflows.md#coherence-check).

<a id="agent-mcp"></a>
### MCP servers of an agent

A coding agent may list remote MCP servers: a name (lower case letters, digits and `-`, at most 40) and an
`http://` or `https://` URL. The runner configures them for the harness (Claude Code `--mcp-config`, OpenCode
`mcp` entries of type `remote`). Credentials are not supported yet: list only servers that need none. Whether a
server is reachable is the sandbox's call: its egress policy ([Sandbox policy](projects.md#sandbox-policy)) must
allow the host, otherwise the proxy refuses the connection and the agent sees the server as unavailable.

<a id="agent-access"></a>
### Access of an agent

**Access** says what a coding agent may change in the repositories of its steps (ADR 0060):

| Access | The agent may change |
|---|---|
| **Full** (default) | anything |
| **Read only** | nothing |
| **Only these paths** | only files matching the path globs you list, relative to a repository root, for example `docs/**` (`**` any directories, `*` within one name; a plain path such as `docs` covers everything below it) |

It is enforced twice:

1. **The harness's own tool rules**: Claude Code does not get its editing tools for other files, OpenCode's edit
   permission denies them, so the agent learns early that it may not.
2. **The runner, after the agent finished**: it snapshots every repository of the step right before the agent
   starts, and afterwards reverts every change outside the access (added files removed, changed or deleted files
   restored), whatever the agent did, shell commands included. The step's log lists each reverted path, and its
   summary ends with `[Back Office runner] reverted N change(s) outside the agent's access (...)`. Only then are the
   verify command, the commit and the push run. Files ignored by git are not covered (they are never committed
   either); artefacts the step collects are collected before the revert.

A **Read only** agent on a step that pushes is a problem: the step cannot run until you change the agent or turn the
push off. Whether a step commits and pushes stays a setting of the step; access only narrows what the agent may
change. A model answer has no repository, so access does not apply to it.

**Directory** says what the agent may see of the run (ADR 0062):

| Directory | The agent works in |
|---|---|
| **Run directory (shared)** (default) | the [run directory](workflows.md#run-directory): the coding steps of the run work there one after another and see what earlier steps left, uncommitted work included |
| **Separate directory** | a fresh copy of only the step's repositories, thrown away after the step; it does not see what other steps left, and its session is never continued |

Access does not hide anything: an agent that must not see the rest of the run's work needs **Separate directory**,
and one that must not see the code at all also a step with only the repositories it may see
([Repositories of a step](workflows.md#step-repositories)). For an independent check, make a coding agent with
**Separate directory** (for example a project copy of **Claude Code** with **Read only** access) and choose it on
the step. Until ADR 0062 the directory was a setting of the step; it is the agent's now (see
[Agent sessions](workflows.md#continue-session) for what the upgrade did with stored steps).

### Steps: create an agent

1. Click **Create agent** (in the library, or on the project tab **Agents** for a project agent).
2. Enter **Name** and **Description**.
3. Pick the **Kind** (**Model answer**, **OpenCode** or **Claude Code**) and, if the agent should always use one
   model, the **Model**.
4. Write the **Instructions**: the role and the way of working, short and concrete.
5. Choose **Rules** and **Skills**; for a coding agent optionally add **MCP servers** (**Add MCP server**) and set
   **Access** (for **Only these paths**, list the paths that the agent may change, for example `docs/**`),
   **Directory** and **Session**.
6. Set **Scope** (**Global** or **Project**) and **Tags**.
7. Click **Create agent**.

To compare harnesses, models or ways of working on the same task, make an agent for each variant (for example
"Claude Code with model X" and "OpenCode with model Y", same rules and skills) and choose it on the step. Measure
the variants as candidates of a [benchmark](evaluation.md#benchmarks) and on real runs in
[Agents: cost and effectiveness](costs-insights.md#agent-insights): every task step records the agent and model it
ran with.

<a id="skills"></a>
## Skills

**Library** → **Tasks** → **Skills** (`/library/skills`) lists knowledge and procedures an agent opens when the
task needs them, with **Scope**, **Tags**, **Size** and **Updated**. A skill follows the Agent Skills convention: a
frontmatter with `name` and `description`, then short, concrete, numbered steps.

A step gets the skills of its agent (**Skills** on the agent), nothing more and nothing less: a step cannot add or
drop skills (ADR 0060). A template can name the skills its work needs ([Required skills](#required-skills)); a step
whose agent lacks one gets a warning, and **Add to agent** in [Agents in this workflow](workflows.md#agent-audit)
fixes it on the agent.

How a skill reaches the agent:

- Steps run by **Model answer** get the skill appended to the system prompt (a single model call cannot open files).
- Steps run by a coding agent (Spec Kit phases included) get it written into the agent's home as
  `skills/<key>/SKILL.md` ([The environment of the agent](#environment)), never into a repository; Claude Code and
  OpenCode load it on their own, and the platform preamble tells the agent where its skills are.

### Steps: create a skill

1. Click **Create skill**.
2. Enter **Name**, **Description**, **Scope** (**Global** or **Project**) and **Tags**.
3. Write the content in the **SKILL.md** tab; check it in **Preview**.
4. Click **Create skill**.

A project skill with the key of a global skill replaces it for that project's agents, without changing
any agent. A skill that an agent lists cannot be deleted: remove it from those agents first; built-in skills
cannot be deleted.

Write the **Description** as "Use when ...": agents decide by it whether to open a skill. What a task must
always follow belongs in a [rule](#rules), not in a skill. Some built-in instructions also name the skill the task
relies on ("Apply the skill ..."); do the same in your own templates when one skill matters for the task.

<a id="sources"></a>
## Sources

**Library** → **Sources** (`/library/sources`) lists material that steps of every project can attach:
standards to follow, templates to start from, reference documentation. For now a source is a Git
repository. Everyone with `library.view` sees the list; `library.manage` adds, changes and deletes
sources.

| Field | Meaning |
|---|---|
| **Key** | 2-27 lower case letters, digits and hyphens, starting with a letter, for example `standards`. Steps refer to the source by it. It cannot change later. |
| **Name** | What people see in the designer and on the project tab **Sources**. |
| **Git URL** | The URL the runner clones. Private repositories need a read token on the runners, like project repositories ([Git access for agents](integrations-git.md#git-least-privilege)). |
| **Branch** | Default `main`. The branch that is cloned. |
| **Purpose** | **Standards**, **Templates**, **Documentation**, **Source code** or **Other**. Told to the agent. |
| **Description** | What to follow or reuse in it. Given to the agent as data, not as instructions. |

How a source reaches the agent: in the designer, a coding step ticks it in its section **Repositories**,
under **Library sources (read only)** ([Repositories of a step](workflows.md#step-repositories)). The job
clones it into `/workspace/lib-<key>` (for example `/workspace/lib-standards`), read only: nothing is ever
pushed to a library source, and the agent is told to follow it, not to change it.

Deleting a source does not change any workflow: steps that still name it fail validation (the workflow can
be saved only as a draft) until you untick it. When you publish a workflow to the workflow library, the
library sources its steps use go with it; projects that adopt it get them too.

## The built-in library

Back Office ships with templates for the Spec Kit phases, implementation, `clarify-spec`, `code-review`,
`analyse-issue`, and SDLC tasks such as `impact-analysis`, `write-user-story`, `plan-task-list`,
`implement-task-list` and `review-against-spec`, the generic `llm-task` and `agent-task`, plus skills, rules and
the five built-in [agents](#agents). You can edit built-in templates, skills and rules; Back Office never overwrites an edited entry. The former templates
**Fetch issue**, **Notify Jira**, **Transition issue**, **Start workflow** and **Project digest** are no longer in
the library: they are [actions](workflows.md#actions) in the designer, and workflows that used them keep working.

A template tagged `deprecated` keeps working in the steps that use it, but the designer, the MCP palette and the
list (unless **Show deprecated**) no longer offer it. Deprecated now (ADR 0054): `implement-claude-code` (it is
`implement-opencode` with the agent Claude Code; use `implement-opencode`, `implement-task-list` or `agent-task`
and choose the agent **Claude Code** on the step), `analyse-issue` (use `impact-analysis`) and, since ADR 0058, the
abstract `base-agent`, `base-llm` and `base-coding-agent`: their text is the platform preamble of the
[environment](#environment) now, the built-in templates no longer extend them, and they stay only for your own
templates that do.

<a id="built-in-updates"></a>
### Updates of the built-in library

On every start Back Office compares each built-in template, skill, rule and agent with the version it
ships, field by field. A built-in nobody edited (still saved by the system, and for templates still
version 1) is replaced by the shipped version when they differ; an entry a person saved, or a copy with
the same key that is not built-in, is left as it is. To get the shipped version of an entry you changed,
compare it with the **Resolved preview** of a fresh installation and copy what you need, or delete your
non-built-in copy and restart.

### How the built-in tasks are put together

- The built-in templates are flat (ADR 0058): one **Instructions** text each (ADR 0062) that says what to do and how
  the result is judged, without role sentences ("You are an analyst ..."): the role is the agent's. They name no agent
  (ADR 0061; the default workflows name the agent on each step) and leave the issue, the consumed artefacts, the
  input material and the feedback to the platform's data sections; only
  `implement-task-list` (and the deprecated `implement-claude-code`) extend `implement-opencode`. How to work
  (honesty, scope, safety, the ground rules, "the runner commits and pushes after the agent", the format skills)
  comes from the agent and its [environment](#environment).
- **Spec Kit** tasks: the specify instructions start with the line `KEY: summary`, then the issue description
  and acceptance criteria, then the instructions. Plan and tasks do not paste the specification or the plan,
  which Spec Kit reads from the repository; they pass the step's feedback and, for the plan, the optional
  parameter **Technical hints** (`techHints`, asked at start in the default workflows).
- **Implementers** (`implement-opencode`, `implement-claude-code`): the runner runs `verifyCommand` from
  **Config** when you set one (none by default) and fails the step when it exits non-zero; the merge request
  uses `mergeRequest` (true/false), `mergeRequestTitleTemplate` and `mergeRequestDraft`.
- **Rework after a review**: when the run has a `review` artefact, the implementers' instructions contain it
  under "Latest review findings", so the `changes` edge of **Review ok?** sends the findings back to the
  implementation instead of repeating the same prompt.
- `implement-task-list` executes the task list in order and ends `work-log.md` with `RESULT: GREEN` or
  `RESULT: STOPPED at <task>`; its `verifyCommand` checks that line, so a stop on a red test fails the step.
  The runner collects the file as the `work-log` artefact and keeps it out of the commit. To also run your
  full test suite after the agent, set `verifyCommand` in the step, for example
  `tail -n 3 work-log.md | grep -q "^RESULT: GREEN" && ./gradlew test`.
- `code-review` puts its instructions before the diff, so a diff cut at `maxInputChars` never removes them;
  in a CI run it uses the merge request title and description as context. The whole diff is reviewed in
  one call.
- `review-against-spec` keeps the requirement coverage table (MET, PARTIAL, MISSING, NOT_APPLICABLE) in the
  `review` artefact; one MISSING requirement makes the verdict REQUEST_CHANGES.
- `clarify-spec` writes `clarifications` as a markdown list (question, why it matters, suggested answer);
  `write-user-story` and `write-use-case` store their documents as the `user-story` and `use-case`
  artefacts. The **Fetch issue** action reads summary, description, acceptance criteria and labels, not comments
  or attachments.
- Open questions use one marker per document type: `[NEEDS CLARIFICATION: ...]` in a Spec Kit
  specification, `[?]` everywhere else. Agents never invent an owner or a date, leave IDs such as
  `US-<AREA>-<NN>` and ADR numbers `NNNN` as placeholders and never set a status such as Accepted.

The seeded workflows of a new project follow these templates; the **Change request (delta)** workflow
starts with **Fetch issue** and its task list is planned from the approved impact analysis. Projects
created earlier keep the workflows they stored; change them in the designer if you want the same.

<a id="generic-tasks"></a>
### Tasks you describe on the step

Two built-in templates exist for work you describe yourself in the node: drop the block on the canvas and write
the task into its parameter **Task** (multi-line) under **Parameters** in the step panel. Without a value the
step runs the default task (for **LLM task**: summarise the input material; for **Agent task**: describe the
repository). Steps that replaced the prompt before parameters existed keep working: the upgrade turned that text
into **Instructions for this step** → **Replace (advanced)**, which wins over the template's instructions.

| Template | Choose on the step | Good for |
|---|---|---|
| **LLM task** (`llm-task`) | a model answer agent, for example **Model answer**: one model call, no repository | summaries, drafts, answers about the run's input material |
| **Agent task** (`agent-task`) | a coding agent such as **Claude Code** or **OpenCode**: a runner job with a clone of the project's repository | analysing code, reviewing, answering questions about the repository; with `push: true` in **Config** also changes on a branch with a merge request |

**Agent task** needs a coding agent even without a push: its configuration says so (`codingAgent: true`), so the
designer pre-selects one.

Their instructions are just `{{params.task}}`: the platform adds the issue (when the run has one), the **Input
material** (when the step asks for it at start and someone typed it) and the reviewer's feedback as data sections
after it, so the **Task** can be just the task. The **Agent task** does not push by default; in a
project without repositories it runs in an empty directory and its final answer becomes the `result` artefact. The
**LLM task** runs on the Back Office server and needs a configured model provider (an API key); the **Agent task**
runs on a runner, so on your [personal runner](personal-runner.md) it can use your own Claude login instead.

Use the MCP tool `search_task_library` to search the library from your IDE (see
[IDE and MCP](integrations-ide.md#mcp-tools)).
