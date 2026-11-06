# Task library

The task library holds what agents do and how they are prompted, separately from the workflows that use
it. **Library** has three groups (ADR 0055, 0056):

- **Tasks**: **Templates**, **Skills**, **Rules** and **Agents**, this chapter;
- **Workflows**: workflows shared between projects ([Workflow library](workflow-library.md));
- **Sources**: material steps can attach ([Sources](#sources)).

## How the pieces fit

| Piece | Think of it as | You use it to |
|---|---|---|
| Task template | a class | describe the work: category, prompts, skills, rules, config, outputs, run inputs and the agent that does it by default; it is based on one parent |
| Rule | a standing order | state something the agent must always follow; always in the agent's context |
| Skill | a handbook chapter | give the agent knowledge or a procedure it opens when the task needs it (a `SKILL.md` file) |
| Agent | the one who does the work | decide who runs a task: a single model answer, or a coding agent (OpenCode or Claude Code) in a sandbox, optionally with its own model |
| Resolved task | an instance | what the agent finally receives: the template with its ancestors, project overrides and node overrides merged |

Work that needs no model (fetching or commenting on a Jira issue, changing its status, starting workflows, the
project digest) is not in the library: it is an **action** node in the workflow designer
([Actions](workflows.md#actions)).

You never type keys: a template, skill, rule or agent gets one from its name when you create it (for example
**Analiza zgłoszenia** becomes `analiza-zgloszenia`), and it stays when you rename the entry. Workflows, MCP
and the API use the key; the editors show it small in **Advanced**.

A workflow's task node refers to a template by its key and never holds a prompt itself. Editing a template
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
ones, which can be a base for others but never run. Filter by text, category and agent, and with **Global
templates** / **Global + KEY** to include a project's own templates.

A template describes the work; its **Agent** says who does it by default (see [Agents](#agents)): **Model answer**
(one model call inside Back Office, no repository; can return structured JSON and a confidence), **OpenCode** or
**Claude Code** (a coding agent in a sandbox on a runner, with the repository; may push a branch and open a merge
request; a template with a Spec Kit `phase` runs that Spec Kit phase). A workflow step can choose another agent.

### Steps: create a template

1. Click **New template** (needs `library.manage`, or `library.project.manage` for a project template).
2. **Type**: **Name**, **Description**, **Based on** (one parent: the template inherits its prompts, skills, rules
   and configuration and changes only what you set), **Category** (empty means inherited), **Agent** (empty means
   inherited; empty all along the chain means **Model answer**), **Scope** (**Global** or **Project** with the
   **Project**), **Model profile**.
3. **Prompt**: choose **Append to parent** or **Replace parent**, then write the **System prompt** and
   the **User prompt**. Template values such as `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}`
   and `{{project.constitution}}` are filled at run time. A section `{{#artifacts.review}}...{{/artifacts.review}}`
   keeps its text only when the value is there (`{{^path}}...{{/path}}` only when it is not), so a prompt
   shows no empty "Issue" or "Feedback" heading in a run without an issue or on a first attempt.
   `{{feedback}}` holds only the revision comments given for this step: the comment of a gate option that
   sends the work back (**Revise**, **Retry** or any option other than **Approve** and **Reject**) goes
   to the next step that runs, stays with that step for its later attempts, and never reaches the steps
   after it (all comments of the run: `{{feedbackHistory}}`). An **Approve** comment reaches later prompts as
   `{{decision.comment}}`.
4. **Skills and rules**: add skills and rules. The automatic rules that apply anyway are listed read-only. Under
   **Advanced**, **Remove inherited skills** and **Drop inherited or automatic rules** take entries away for this
   template and the ones based on it.
5. **Configuration**: a form with the settings for the template's agent (for a coding agent: verify and setup
   command, push, merge request, artefacts to collect, agent tools, workspace, Spec Kit phase, ...). Set only what differs from the
   template it is based on; empty fields are inherited. **Raw JSON** shows the same settings as JSON, also keys the
   form does not know.
   **Advanced: contracts, artefacts, output schema, sandbox** (collapsed; most templates need nothing here):
   **Abstract** (only a base for other templates, never a step), **Produces** and **Consumes** (artefact names the step writes and reads; the workflow
   check warns when a step reads something nobody writes), **Confidence field** (a field of the model answer copied
   to `state.confidence` for decisions such as `confidence < 0.7`), **Tags**, **Output schema** (Model answer only)
   and **Sandbox** (step 7).
6. **Run inputs**: the fields this task asks for when a run starts (see [Run inputs](#run-inputs)). **Add run
   input**, then set **Name**, **Label**, the type (**Single line**, **Multi-line text** or **Jira issue key**),
   **Required** and an optional help text; move fields with the arrows or remove them. A name starts with a letter
   and uses only letters, digits, `-` and `_` (at most 64); a label has at most 200 characters, a help text 1 000.
7. **Sandbox** (runner tasks): narrow the project's sandbox policy for jobs of this template, for example
   stronger isolation or fewer hosts. The project and pool ceilings still apply (see
   [Sandbox policy](projects.md#sandbox-policy)).
8. Click **Create template** (or **Save template** for an existing one).

### Merging with the parent

| Field | Rule |
|---|---|
| Agent, category | The nearest one in the chain wins. |
| Model profile, output schema, confidence field | The child's value wins when set. |
| System and user prompt | Per field: **Append to parent** adds the child's text after the parent's; **Replace parent** uses only the child's. An empty field keeps the parent's. |
| Skills, rules | Union along the chain, minus removed ones. Automatic rules come first. |
| Config | Merged key by key; the child wins; `null` deletes a key. |
| Produces, consumes, tags | Union. |
| Run inputs | Union by name; a child's field replaces the parent's field with the same name. |

<a id="run-inputs"></a>
### Run inputs

A task declares the run inputs it needs, for example the built-in `base-agent` declares **Input material**
(multi-line, optional); the **Fetch issue** action in the designer asks for the **Jira issue key** (required). Every
task that extends a template inherits its fields. When a run starts, the start form shows the fields of all steps of the workflow: a field
is required when any step requires it, and when two steps use the same name the first label wins (the
designer warns about it). The values reach the step's prompt in a **Run input** section, as data; templates
can also read `{{runInputText}}` and, as before, `{{parameters.name}}`. A **Jira issue key** field also sets the
run's issue key.

### The resolved preview

The right side of the editor shows **Resolved preview**: exactly what an agent receives after inheritance
and project shadowing, for the global library or for a project you pick. It shows the lineage, agent and
category, and tabs **System prompt**, **User prompt**, **Skills**, **Rules**, **Config** and **Run inputs** (the
merged fields, with the template each one is inherited from; automatic rules are marked). If a skill or rule the
template lists does not exist, the preview lists it, and the task cannot run until it is fixed. The preview reflects the saved version; unsaved changes are listed as deltas.

### Steps: change a task for one project

1. On the template's row, click **Customize for project**.
2. A new template opens with scope **Project** and the global template as parent; it keeps the global template's
   key, so it replaces the global one in that project. Pick the
   project and change only what differs, for example append a rule to the system prompt.
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

How a rule reaches the agent:

- Steps run by **Model answer** get the rules in the system prompt, before the skills.
- Steps run by a coding agent (**OpenCode**, **Claude Code**, Spec Kit phases included) get them at the start of
  their prompt, right after the template's system part. Nothing is written into the repository.

Which rules a step gets:

| Rule | Applies to |
|---|---|
| **Apply automatically to every step**, global | every step of every project |
| **Apply automatically to every step**, project | every step of that project |
| Not automatic | the templates that list it under **Rules**, and the templates based on them |

A template can drop a rule it inherits or an automatic one with **Drop inherited or automatic rules**. A
project rule with the key of a global rule replaces it for that project (made with **Customize for project**);
when the project's copy is not automatic, the global automatic rule is off in that project.

### Steps: create a rule

1. Click **Create rule**.
2. Enter **Name**, **Description**, **Scope** (**Global** or **Project**), **Tags** and, if every step of the
   scope must follow it, switch on **Apply automatically to every step**.
3. Write the rule in markdown: short, concrete, checkable sentences.
4. Click **Create rule**.

A rule a template lists cannot be deleted. Built-in rules: `agent-ground-rules` and `ask-dont-guess` (every
agent gets them through the built-in `base-agent` template), `kotlin-hexagonal` and `angular-ng-zorro`
(the conventions of the Back Office code base itself: copy them into a project rule for your stack and make
that one automatic).

<a id="agents"></a>
## Agents

**Library** → **Tasks** → **Agents** (`/library/agents`) lists who can do the work of a task (ADR 0056). An agent
is a model, a **harness** that runs the conversation with it (tools, context, questions) and the place it works in.
Today an agent has a harness and optionally a model; the place it works in comes from the step (its workspace,
repositories, rules and skills). Later stages will add a project environment and sessions that continue across
steps.

| Field | Meaning |
|---|---|
| **Kind** (the harness) | **Model answer**: one model call inside Back Office without tools or a repository. **OpenCode** or **Claude Code**: a coding agent session in the runner sandbox with the step's workspace. |
| **Model** | Optional. Empty means the step's model or the defaults for its kind: the project's, then the starting user's, then the platform's. |
| **Name**, **Description**, **Scope**, **Tags** | As for rules; the key is derived from the name. |

Built-in agents: **Model answer** (`model-answer`), **OpenCode** (`opencode`) and **Claude Code** (`claude-code`).
Agents have the same permissions, scopes and audit as rules; **Customize for project** makes a project copy. An
agent a template uses cannot be deleted: choose another agent in those templates first.

**Who runs a step**: the **Agent** chosen on the step in the designer, else the template's **Agent** (inherited,
the nearest in the chain wins), else **Model answer**.

**Which model**: the step's **Model profile**, else the agent's, else the template's, then the defaults (project,
starting user, platform).

**How the step runs** follows from the agent and the template:

| Agent | Template | The step |
|---|---|---|
| Model answer | any that needs no coding agent | one model call: structured output, confidence and injection guard |
| OpenCode or Claude Code | with a Spec Kit `phase` in its configuration | the Spec Kit phase |
| OpenCode or Claude Code | any other | an agent job: pushes and opens a merge request per configuration; without a push its final answer becomes the `result` artefact |

The step cannot run, and the designer and the preview say why, when a template needs a coding agent (a Spec Kit
`phase`, `push: true`, `prepareOnly`) but gets **Model answer**, or has an **Output schema** (a structured answer)
but gets a coding agent.

### Steps: create an agent

1. Click **Create agent**.
2. Enter **Name**, **Description**, **Scope** (**Global** or **Project**) and **Tags**.
3. Pick the **Kind** (**Model answer**, **OpenCode** or **Claude Code**) and, if the agent should always use one
   model, the **Model**.
4. Click **Create agent**.

To compare harnesses or models on the same task, make an agent for each combination (for example "Claude Code
with model X") and choose it on the step; the template stays the same.

<a id="skills"></a>
## Skills

**Library** → **Tasks** → **Skills** (`/library/skills`) lists knowledge and procedures an agent opens when the
task needs them, with **Scope**, **Tags**, **Size** and **Updated**. A skill follows the Agent Skills convention: a
frontmatter with `name` and `description`, then short, concrete, numbered steps.

How a skill reaches the agent:

- Steps run by **Model answer** get the skill appended to the system prompt (a single model call cannot open files).
- Steps run by a coding agent (Spec Kit phases included) get it written as `.claude/skills/<key>/SKILL.md` and
  `.opencode/skills/<key>/SKILL.md`, which Claude Code and OpenCode load on their own.

### Steps: create a skill

1. Click **Create skill**.
2. Enter **Name**, **Description**, **Scope** (**Global** or **Project**) and **Tags**.
3. Write the content in the **SKILL.md** tab; check it in **Preview**.
4. Click **Create skill**.

A project skill with the key of a global skill replaces it for that project's templates, without changing
any template. A skill that a template uses cannot be deleted; built-in skills cannot be deleted.

Write the **Description** as "Use when ...": agents decide by it whether to open a skill. What a task must
always follow belongs in a [rule](#rules), not in a skill. A runner agent finds every skill in its
workspace but reads only the ones it is told to, so the prompts of the built-in runner tasks
name the skills they use ("Apply the skill ..."); when you attach another skill to such a task, name it in the prompt too.

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
`implement-task-list` and `review-against-spec`, plus their skills and rules and the three built-in
[agents](#agents). You can edit built-ins; Back Office never overwrites an edited entry. The former templates
**Fetch issue**, **Notify Jira**, **Transition issue**, **Start workflow** and **Project digest** are no longer in
the library: they are [actions](workflows.md#actions) in the designer, and workflows that used them keep working.

A template tagged `deprecated` keeps working in the steps that use it, but the designer, the MCP palette and the
list (unless **Show deprecated**) no longer offer it. Deprecated now (ADR 0054): `implement-claude-code` (it is
`implement-opencode` with the agent Claude Code; use `implement-opencode`, `implement-task-list` or `agent-task`
and choose the agent **Claude Code** on the step) and `analyse-issue` (use `impact-analysis`).

<a id="built-in-updates"></a>
### Updates of the built-in library

On every start Back Office compares each built-in template, skill and rule with the version it
ships, field by field. A built-in nobody edited (still saved by the system, and for templates still
version 1) is replaced by the shipped version when they differ; an entry a person saved, or a copy with
the same key that is not built-in, is left as it is. To get the shipped version of an entry you changed,
compare it with the **Resolved preview** of a fresh installation and copy what you need, or delete your
non-built-in copy and restart.

### How the built-in tasks are put together

- Three abstract templates carry the shared rules: `base-agent` (role, constitution, honesty, scope,
  safety, the two ground rules), `base-llm` for the model answer tasks (no repository; with an
  output schema the answer is JSON only) and `base-coding-agent` for the coding agent tasks (work in the
  repository, quote file paths, never commit or push: the runner commits and pushes after the agent).
- **Spec Kit** tasks: the specify prompt starts with the line `KEY: summary`, then the issue description
  and acceptance criteria, then the instructions. Plan and tasks do not paste the specification or the plan,
  which Spec Kit reads from the repository; they pass the step's feedback and, for the plan, the optional
  run input **Technical hints** (`techHints`).
- **Implementers** (`implement-opencode`, `implement-claude-code`): the runner runs `verifyCommand` from
  **Config** when you set one (none by default) and fails the step when it exits non-zero; the merge request
  uses `mergeRequest` (true/false), `mergeRequestTitleTemplate` and `mergeRequestDraft`.
- **Rework after a review**: when the run has a `review` artefact, the implementers' prompt contains it
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
### Tasks with any prompt

Two built-in templates exist for work you describe yourself in the node: drop the block on the canvas, open
**Overrides** → **Replace** and write the task there (it replaces the template's user prompt).

| Template | What runs it | Good for |
|---|---|---|
| **LLM task** (`llm-task`) | the agent **Model answer**: one model call, no repository | summaries, drafts, answers about the run's input material |
| **Agent task** (`agent-task`) | a coding agent on a runner (**Claude Code** by default; choose another **Agent** on the step), with a clone of the project's repository | analysing code, reviewing, answering questions about the repository; with `push: true` in **Config** also changes on a branch with a merge request |

Both put the issue (when the run has one), the run input **Input material** and the reviewer's feedback into the
system prompt (each only when it is there), so the replaced prompt can be just the task. The **Agent task** does not push by default; in a
project without repositories it runs in an empty workspace and its final answer becomes the `result` artefact. The
**LLM task** runs on the Back Office server and needs a configured model provider (an API key); the **Agent task**
runs on a runner, so on your [personal runner](personal-runner.md) it can use your own Claude login instead.

Use the MCP tool `search_task_library` to search the library from your IDE (see
[IDE and MCP](integrations-ide.md#mcp-tools)).
