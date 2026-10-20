# Task library

The task library holds what agents do and how they are prompted, separately from the workflows that use
it: task templates, the interfaces they promise, reusable skills and sources. This chapter covers these
**Library** pages and how to change a task for one project only.

## How the pieces fit

| Piece | Think of it as | You use it to |
|---|---|---|
| Task interface | an interface | promise which inputs a task needs, which outputs it produces and which ports its node has |
| Task template | a class | define a task: executor, prompts, skills, config; it extends one parent and implements interfaces |
| Skill | a mixin | reuse a block of instructions (a `SKILL.md` file) in many templates |
| Resolved task | an instance | what the agent finally receives: the template with its ancestors, project overrides and node overrides merged |

A workflow's task node refers to a template by its key and never holds a prompt itself. Editing a template
therefore changes every workflow that uses it, from the next run on. Runs already in progress keep what
they resolved.

### Who may change what

| You want to | Global library | One project's own entries |
|---|---|---|
| See templates, interfaces, skills and the preview | `library.view` (every **Member**) | `project.view` on the project |
| Create, edit or delete templates and skills | `library.manage` (role **Library curator**) | `library.project.manage` (project **Owner**, **Maintainer**) |
| Create, edit or delete interfaces | `library.manage`; interfaces are always global | — |

Without the right permission the editors open read-only. Every change is recorded in the audit log, and
the lists show who changed an entry last.

<a id="templates"></a>
## Templates

**Library** → **Templates** (`/library/templates`) lists the templates with **Extends**, **Implements**,
**Executor**, **Scope** and **Updated**. Built-in templates are marked; so are abstract ones, which can be
extended but never run. Filter by text, by executor (**Any executor**), and with **Global templates** /
**Global + KEY** to include a project's own templates.

The executor decides where a task runs:

| Executor | Runs |
|---|---|
| **LLM** | One model call inside Back Office; skills go into the system prompt; can return structured JSON and a confidence. |
| **Spec Kit** | A Spec Kit phase (specify, plan, tasks) in an agent job on a runner. |
| **Implement** | A coding agent (OpenCode or Claude Code) in an agent job; pushes a branch and opens a merge request through push options. |
| **Fetch issue**, **Notify**, **Transition issue** | Jira calls: read the issue, post a comment, move the issue to a status (see [Jira](integrations-jira.md#jira-tasks)). |

### Steps: create a template

1. Click **New template** (needs `library.manage`, or `library.project.manage` for a project template).
2. **Type**: **Key** (a slug), **Name**, **Description**, **Extends** (one parent), **Implements**
   (interfaces), **Executor** (empty means inherited), **Abstract**, **Scope** (**Global** or **Project**
   with the **Project**), **Model profile**.
3. **Prompt**: choose **Append to parent** or **Replace parent**, then write the **System prompt** and
   the **User prompt**. Template values such as `{{issue.summary}}`, `{{artifacts.spec}}`, `{{feedback}}`
   and `{{project.constitution}}` are filled at run time. A section `{{#artifacts.review}}...{{/artifacts.review}}`
   keeps its text only when the value is there (`{{^path}}...{{/path}}` only when it is not), so a prompt
   shows no empty "Issue" or "Feedback" heading in a run without an issue or on a first attempt.
   `{{feedback}}` holds only the revision comments given for this step: a gate's **Revise** comment goes
   to the next step that runs, stays with that step for its later attempts, and never reaches the steps
   after it (all comments of the run: `{{feedbackHistory}}`).
4. **Skills**: add skills, and list inherited skills under **Remove inherited skills**.
5. **Contract and configuration**: **Produces** and **Consumes** (artefact or state names),
   **Confidence field**, **Tags**, **Config (JSON)** and, for LLM tasks, **Output schema**.
6. **Sandbox** (runner tasks): narrow the project's sandbox policy for jobs of this template, for example
   stronger isolation or fewer hosts. The project and pool ceilings still apply (see
   [Sandbox policy](projects.md#sandbox-policy)).
7. Click **Create template** (or **Save template** for an existing one).

### Merging with the parent

| Field | Rule |
|---|---|
| Executor | The nearest one in the chain wins. |
| Model profile, output schema, confidence field | The child's value wins when set. |
| System and user prompt | Per field: **Append to parent** adds the child's text after the parent's; **Replace parent** uses only the child's. An empty field keeps the parent's. |
| Skills | Union along the chain, minus removed skills. |
| Config | Merged key by key; the child wins; `null` deletes a key. |
| Produces, consumes, implements, tags | Union. |

### The resolved preview

The right side of the editor shows **Resolved preview**: exactly what an agent receives after inheritance
and project shadowing, for the global library or for a project you pick. It shows the lineage, executor,
interfaces and ports, and tabs **System prompt**, **User prompt**, **Skills** and **Config**. If an
implemented interface is not satisfied, **Interface check failed** lists what is missing, and the task
cannot run until it is fixed. The preview reflects the saved version; unsaved changes are listed as deltas.

### Steps: change a task for one project

1. On the template's row, click **Extend in a project**.
2. A new template opens with the same key, scope **Project**, and the global template as parent. Pick the
   project and change only what differs, for example append a rule to the system prompt.
3. Save. From now on, that project's workflows use the project template wherever the key appears,
   including as a parent of other templates. Other projects keep the global one.

Built-in templates cannot be deleted; extend them per project instead. A template another template
extends cannot be deleted either.

<a id="interfaces"></a>
## Interfaces

**Library** → **Interfaces** (`/library/interfaces`) lists the contracts with their **Inputs**,
**Outputs**, **Ports** and **Implemented by**.

An interface has a **Key**, **Name**, **Description**, inputs and outputs (each with a name, a kind and,
for inputs, **required**) and **Ports**: the output ports a node of this interface shows on the canvas. A
template that implements the interface must list every required artefact or state input under
**Consumes** and every output under **Produces**; the resolved preview checks it.

Click **Create interface**, fill in the fields (**Add input**, **Add output**) and click **Create
interface**. Changing interfaces needs `library.manage`. An interface that templates still implement
cannot be deleted; built-in interfaces cannot be deleted at all.

When you add a required input or an output to an interface, the templates listed under **Implemented
by** fail their interface check until you update their **Consumes** and **Produces**.

<a id="skills"></a>
## Skills

**Library** → **Skills** (`/library/skills`) lists reusable instruction blocks with **Scope**, **Tags**,
**Size** and **Updated**. A skill follows the Agent Skills convention: a frontmatter with `name` and
`description`, then short, concrete, numbered rules.

How a skill reaches the agent:

- **LLM** tasks get the skill appended to the system prompt.
- Runner tasks (**Spec Kit**, **Implement**) get it written as `.claude/skills/<key>/SKILL.md` and
  `.opencode/skills/<key>/SKILL.md`, which Claude Code and OpenCode load on their own.

### Steps: create a skill

1. Click **Create skill**.
2. Enter **Key**, **Name**, **Description**, **Scope** (**Global** or **Project**) and **Tags**.
3. Write the content in the **SKILL.md** tab; check it in **Preview**.
4. Click **Create skill**.

A project skill with the key of a global skill replaces it for that project's templates, without changing
any template. A skill that a template uses cannot be deleted or renamed; built-in skills cannot be
deleted. Every agent inherits the skills `agent-ground-rules` and `ask-dont-guess` through the built-in
`base-agent` template; remove them on a template with **Remove inherited skills** if a task must not see
them.

Write the **Description** as "Use when ...": agents decide by it whether to open a skill. Rules a task
must always follow belong in its prompt, not only in a skill. A runner agent finds every skill in its
workspace but reads only the ones it is told to, so the prompts of the built-in runner tasks end with
"Apply the skills: ..."; when you attach another skill to such a task, add its key to that line.
`kotlin-hexagonal` and `angular-ng-zorro` describe the Back Office code base itself: use them as examples
and copy them into a project skill for your own stack.

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

Back Office ships with templates for the Spec Kit phases, implementation with OpenCode or Claude Code,
`clarify-spec`, `code-review`, `analyse-issue`, the Jira tasks, and SDLC tasks such as `impact-analysis`,
`write-user-story`, `plan-task-list`, `implement-task-list` and `review-against-spec`, plus their
interfaces and skills. You can edit built-ins; Back Office never overwrites an edited entry.

<a id="built-in-updates"></a>
### Updates of the built-in library

On every start Back Office compares each built-in template, skill and interface with the version it
ships, field by field. A built-in nobody edited (still saved by the system, and for templates still
version 1) is replaced by the shipped version when they differ; an entry a person saved, or a copy with
the same key that is not built-in, is left as it is. To get the shipped version of an entry you changed,
compare it with the **Resolved preview** of a fresh installation and copy what you need, or delete your
non-built-in copy and restart.

### How the built-in tasks are put together

- Three abstract templates carry the shared rules: `base-agent` (role, constitution, honesty, scope,
  safety, the two ground-rule skills), `base-llm` for the in-process LLM tasks (no repository; with an
  output schema the answer is JSON only) and `base-coding-agent` for the runner tasks (work in the
  repository, quote file paths, never commit or push: the runner commits and pushes after the agent).
- **Spec Kit** tasks: the specify prompt starts with the line `KEY: summary`, then the issue description
  and acceptance criteria, then the instructions. Plan and tasks do not paste the specification or the plan,
  which Spec Kit reads from the repository; they pass the step's feedback and, for the plan, the optional
  run parameter `techHints`.
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
  artefacts. `fetch-issue` reads summary, description, acceptance criteria and labels, not comments or
  attachments.
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
| **LLM task** (`llm-task`) | one model call, no repository | summaries, drafts, answers about the run's input material |
| **Agent task** (`agent-task`) | a coding agent on a runner (Claude Code by default, `agent` in **Config**), with a clone of the project's repository | analysing code, reviewing, answering questions about the repository; with `push: true` in **Config** also changes on a branch with a merge request |

Both put the issue (when the run has one), the run parameter `input` and the reviewer's feedback into the
system prompt (each only when it is there), so the replaced prompt can be just the task. The **Agent task** does not push by default; in a
project without repositories it runs in an empty workspace and its final answer becomes the `result` artefact. The
**LLM task** runs on the Back Office server and needs a configured model provider (an API key); the **Agent task**
runs on a runner, so on your [personal runner](personal-runner.md) it can use your own Claude login instead.

Use the MCP tool `search_task_library` to search the library from your IDE (see
[IDE and MCP](integrations-ide.md#mcp-tools)).
