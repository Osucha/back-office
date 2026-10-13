# Task library

The task library holds what agents do and how they are prompted, separately from the workflows that use
it: task templates, the interfaces they promise, and reusable skills. This chapter covers the three
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
   and `{{project.constitution}}` are filled at run time.
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

## The built-in library

Back Office ships with templates for the Spec Kit phases, implementation with OpenCode or Claude Code,
`clarify-spec`, `code-review`, `analyse-issue`, the Jira tasks, and SDLC tasks such as `impact-analysis`,
`write-user-story`, `plan-task-list`, `implement-task-list` and `review-against-spec`, plus their
interfaces and skills. You can edit built-ins; Back Office never overwrites an edited entry.

<a id="generic-tasks"></a>
### Tasks with any prompt

Two built-in templates exist for work you describe yourself in the node: drop the block on the canvas, open
**Overrides** → **Replace** and write the task there (it replaces the template's user prompt).

| Template | What runs it | Good for |
|---|---|---|
| **LLM task** (`llm-task`) | one model call, no repository | summaries, drafts, answers about the run's input material |
| **Agent task** (`agent-task`) | a coding agent on a runner (Claude Code by default, `agent` in **Config**), with a clone of the project's repository | analysing code, reviewing, answering questions about the repository; with `push: true` in **Config** also changes on a branch with a merge request |

Both put the issue (when the run has one), the run parameter `input` and the reviewer's feedback into the
system prompt, so the replaced prompt can be just the task. The **Agent task** does not push by default; in a
project without repositories it runs in an empty workspace and its final answer becomes the `result` artefact. The
**LLM task** runs on the Back Office server and needs a configured model provider (an API key); the **Agent task**
runs on a runner, so on your [personal runner](personal-runner.md) it can use your own Claude login instead.

Use the MCP tool `search_task_library` to search the library from your IDE (see
[IDE and MCP](integrations-ide.md#mcp-tools)).
