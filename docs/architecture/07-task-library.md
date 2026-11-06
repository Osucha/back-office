# 07 — Task Library

The Task Library is a bounded context (`tasks-core`, `tasks-adapters`) that owns what agents do and
how they are prompted, independent of any single workflow (ADR 0015). Projects use it through the
`TaskCatalog` port; benchmarks may reference templates in a later iteration.

## Concepts

| Concept | Analogy | Content |
|---|---|---|
| `TaskTemplate` | class (what to do) | `key`, `name`, `description`, `abstract`, `extendsKey`, `category`, `agentKey` (ADR 0056), `scope` (`GLOBAL` or `PROJECT(projectId)`), `modelProfileId`, `prompt {system, user, mode APPEND|REPLACE}`, `parameters[]` and `parameterValues{}` (ADR 0058), `config{}`, `outputSchema`, `confidenceField`, `produces[]`, `consumes[]`, `tags[]`, `runInputs[]` (fields of the run start form, ADR 0053), `builtIn`, `version`; no skills or rules since ADR 0057 |
| `TemplateParameter` | typed slot | `name` (`^[A-Za-z][A-Za-z0-9_]{0,63}$`), `label`, `type` `TEXT|MULTILINE|NUMBER|BOOLEAN|CHOICE` (+ `options`), `required`, `default`, `help`; read by prompts and text config as `{{params.<name>}}` (ADR 0058) |
| `TemplateCategory` | grouping | `ANALYSIS`, `SPECIFICATION`, `PLANNING`, `IMPLEMENTATION`, `REVIEW`, `JIRA`, `OPERATIONS`, `GENERAL`; shared kernel; groups the library list, the designer palette and MCP results (ADR 0055) |
| `RunInputField` | form field | `name` (key in the run parameters), `label`, `type` `TEXT|MULTILINE|ISSUE_KEY`, `required`, `help`; shared kernel, so the Projects context reads it from `ResolvedTask.runInputs` |
| `Skill` | handbook chapter | `key`, `name`, `description`, `content` (markdown, Agent Skills `SKILL.md` convention), `scope`, `tags`, `builtIn`; knowledge or a procedure the agent opens when the task needs it |
| `Rule` | standing order | `key`, `name`, `description`, `content` (markdown, no frontmatter), `scope`, `automatic`, `tags`, `builtIn`; a norm that is always in the agent's context (ADR 0055) |
| `Agent` | worker (who and how) | `key`, `name`, `description`, `scope`, `harness` (`OPENCODE`, `CLAUDE_CODE` or none = model answer), `modelProfileId`, `instructions` (markdown, may use `{{project.*}}`), `rules[]`, `skills[]`, `mcpServers[] {name, url}`, `tags`, `builtIn` (ADR 0056, 0057); `AgentSnapshot` in the shared kernel carries it to the Projects context |
| `ResolvedTask` | instance | the merged result the workflow engine runs: the resolved `agent` (with its instructions and MCP servers), the step's `rules` and `skills`, and `parameters` (definition, value, source `STEP`/`TEMPLATE`/`DEFAULT`/`NONE`) |

Until ADR 0056 a template had an **executor** (`LLM`, `SPEC_KIT`, `IMPLEMENT`, `FETCH_ISSUE`, `NOTIFY`,
`TRANSITION_ISSUE`, `START_WORKFLOW`, `PROJECT_DIGEST`) that mixed what thinks, how it works and whether it is AI work
at all. It was removed: the template describes the work, the **agent** says who does it, and the five deterministic
integrations are workflow nodes of kind `ACTION` in the Projects context
([03-pipelines-and-agents.md](03-pipelines-and-agents.md)), not library entries. `V304` creates the `agent` table,
sets `agent_key` of templates from their executor and config key `agent`, removes the key `agent` from template
config, deletes the five built-in action templates and drops `executor`.

Until ADR 0055 the library also had task interfaces (`implements`, inputs, outputs, ports). No workflow ever
referred to one: they only re-checked `consumes`/`produces`, never added a port and labelled templates in the
palette. They were removed (V303 drops the `task_interface` table); the grouping they gave is now the template's
`category`.

### Keys (ADR 0055)

Keys match `^[a-z][a-z0-9-]{1,63}$` and are what workflows, MCP and the API refer to; people do not type them.
On create a template, skill, rule or agent without a key gets one from its name (`Keys.fromName`: diacritics dropped,
everything but letters and digits becomes `-`, a `task-`/`skill-`/`rule-` prefix when the name starts with a digit
or yields nothing) made unique by `Keys.unique` (`-2`, `-3`, ...). Template keys are unique across scopes, so a new
template never shadows anything by accident; skills and rules are unique by `(key, scope)`. An update without a key
keeps it, so renaming changes the name only; the key and scope of an entry other templates use (extend, list as a
skill or rule) cannot change. An explicit key on create is still accepted: a `PROJECT` template with a global
template's key and the same `extendsKey` customises that template for the project (UI: **Customize for project**).

### Rules (ADR 0055, 0057)

A rule applies to the steps whose agent lists it in `rules` or, when `automatic`, to every step of its scope: a
global automatic rule to every step of every project, a project one to every step of that project. Nothing can drop
an automatic rule for one task (template-level `removeRules` is gone with ADR 0057). A `PROJECT` rule with a global
rule's key replaces it for that project; a non-automatic project copy of a global automatic rule switches it off for
that project. Rules are part of the agent's **environment document** (see [Agents](#agents-adr-0056-0057)), not of
the task prompt: a model answer gets them in the system prompt, a coding agent in its `AGENTS.md` / user `CLAUDE.md`.
The former `RulesPrompt` (a `<rules>` block at the start of a coding prompt) was removed, so a continued session does
not receive the rules twice. Skills, by contrast, reach runner steps as `.claude/skills/<key>/SKILL.md` and
`.opencode/skills/<key>/SKILL.md`, which the agent opens on demand.

### Agents (ADR 0056, 0057)

| | **Agent: who and how** | **Task template: what** |
|---|---|---|
| Holds | harness, model (optional), instructions, rules, skills, MCP servers | prompt with parameters, run inputs, output schema, produces/consumes, config, category |
| Goes to | the session's environment | the session's prompt |
| Changes when | you look for a cheaper or better way to work | the work changes |

A **step** is a template, an agent (the step's, else the template's, else `model-answer`) and parameter values; it
may still add or remove skills for itself (`overrides.addSkills` / `removeSkills`). The split follows CrewAI
(`Agent` + `Task`), Claude Code subagents and OpenCode agents, and it is what evaluation needs: the agent is the
variable compared on the same task, so it must be a named, complete unit (ADR 0057, 0059). An agent is a library
entry in the **Tasks** group, global or project scope, built in or not, with the permissions, audit and project
shadowing of rules.

- harness empty: **model answer**, one in-process model call without tools or repository (the former `LLM`);
- `OPENCODE` or `CLAUDE_CODE`: a **coding agent** session in the runner sandbox with the step's workspace.

Built-in agents (no model of their own; all list `agent-ground-rules` and `ask-dont-guess`):

| Key | Name | Harness | Skills | Instructions |
|---|---|---|---|---|
| `model-answer` | Model answer | none | none | none |
| `analyst` | Analyst | none | `user-story-format`, `use-case-format`, `adr-format`, `definition-of-ready-done`, `build-guardrails` | analyst role |
| `reviewer` | Reviewer | none | `security-review-checklist`, `definition-of-ready-done` | reviewer role |
| `opencode` | OpenCode | `OPENCODE` | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | none |
| `claude-code` | Claude Code | `CLAUDE_CODE` | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | none |

Deleting a rule or skill an agent lists is refused (`InvalidStateException`), as is deleting an agent a template
names.

**The environment document** (`AgentEnvironment`, Projects context) is rendered once per step:

1. the platform preamble for the kind of agent (fixed in code; platform facts, not norms): what Back Office is,
   honesty, scope, safety; for model answers "no repository, no tools, JSON only with a schema"; for coding agents
   "the runner commits and pushes, never weaken a test, quote file paths, your skills are in `.claude/skills/` and
   `.opencode/skills/`";
2. the agent's instructions, rendered with the step's template model (`{{project.*}}`; untrusted values become data
   blocks) under "Your role";
3. the project constitution, when the project has one (templates no longer place it);
4. the rules: automatic (global, then project), then the agent's.

| Agent | Where the document goes | Skills |
|---|---|---|
| model answer | system prompt = document + template system prompt + `<skills>`; PROMPT jobs (ADR 0050) get it as `systemPrompt` | in the system prompt |
| OpenCode | runner writes `/workspace/.backoffice-agent/AGENTS.md` and lists it under `instructions` of the generated `opencode.json` | `.opencode/skills/<key>/SKILL.md` |
| Claude Code | runner writes the user memory `$CLAUDE_CONFIG_DIR/CLAUDE.md` (default `/workspace/.claude-config`) | `.claude/skills/<key>/SKILL.md` |

Both files are outside every repository, rewritten on every job and removed when a job has none (run workspaces
persist between steps). The repository's own `AGENTS.md` / `CLAUDE.md` stay untouched and are read by the harness as
project memory. `RunnerJobSpec.instructions` carries the document and is covered by the spec digest.

**MCP servers** (`mcpServers`, `{name, url}`, name `^[a-z][a-z0-9-]{0,39}$`, `http(s)` only, no credentials): the
runner configures them for coding agents (OpenCode `mcp.<name> = {type: remote, url}`; Claude Code
`/workspace/.backoffice-agent/mcp.json` passed with `--mcp-config`, plus `mcp__<name>` in `permissions.allow` under a
tool policy). Reachability is the sandbox egress policy's call (runner controller, ADR 0024): a host it does not
allow is refused by the proxy. Credentials for MCP servers need sealed delivery like repository tokens (ADR 0051) and
get their own record.

| Question | Answer |
|---|---|
| Who runs a step | the step's agent (`overrides.agentKey`), else the template's `agentKey` (inherited, child wins), else `model-answer` |
| Which model | the step's model, else the agent's, else the template's, then the defaults (`ModelResolver`: project, starting user, platform) |
| How it runs | model answer: the in-process model call (structured output, confidence, injection guard); coding agent and a template with the config key `phase`: the Spec Kit job; coding agent otherwise: the agent job (former `IMPLEMENT`), push and merge request per config, without a push the final answer becomes the `result` artefact |

The resolver reports a problem in `check.problems` (the step cannot run) when a template needs a coding agent
(Spec Kit `phase`, `push: true`, `prepareOnly`) but gets a model answer. A coding agent with an `outputSchema` is
allowed since ADR 0057: it writes `structured-answer.json` (see
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#how-task-and-action-nodes-run)). The config key `agent` (`CLAUDE_CODE`/`OPENCODE`) no longer exists; a stored step's
`config.agent` is read as `overrides.agentKey` (`claude-code`, `opencode`). `GET /workflows/task-config-schemas`
returns the JSON Schema of the config keys of a model answer step and of a coding agent step (Spec Kit phase
included); it replaced `/workflows/task-executors`.

Stages of ADR 0056: stage 2 (the agent's environment, continued sessions, structured answers of coding agents) is
ADR 0057, stage 3 (typed template parameters) is ADR 0058; the agent as the benchmark dimension next to the workflow
is ADR 0059.

### Parameters (ADR 0058)

A template declares `parameters` (inherited, child wins by name) and may set `parameterValues` for the parameters of
its lineage; a step sets `overrides.parameters`. The value is the step's, else the nearest template's, else the
default. Unknown names, wrong types (a number for `NUMBER`, a boolean for `BOOLEAN`, one of `options` for `CHOICE`) and
a required parameter without a value are resolution problems: the step cannot run, the preview still works. The
template model of prompts has `params` (trusted like the prompt: the designer sets them). A template is a **ready
block** when every required parameter has a value without the step; `PaletteTask.needsParameters` marks the others.
A **preset** is a template that extends another and only sets `parameterValues` (UI: **Create preset**). Parameters
are fixed at design time, run inputs (ADR 0053) are asked at run start; they stay separate.

## Resolution rules (`TaskResolver`, pure domain service)

1. Find the template by key: a `PROJECT` template for the calling project shadows a `GLOBAL` one (same for skills
   and rules).
2. Walk `extendsKey` up to the root (max depth 8, cycles are a `ValidationException`). Each ancestor is
   resolved with the same project-shadowing rule; a project template that extends its own key extends the global one.
3. Merge root → leaf:
   - `agentKey`, `category`: nearest defined wins;
   - `modelProfileId`, `outputSchema`, `confidenceField`: child wins when set;
   - `prompt.system` / `prompt.user`: child `APPEND` = parent + blank line + child; `REPLACE` = child only;
   - `parameters`: union by `name`, child wins; `parameterValues`: nearest template that sets a value wins;
   - `config`: deep merge, child keys win, `null` deletes;
   - `produces`, `consumes`, `tags`: union;
   - `runInputs`: union by `name`; a child field replaces the parent's field of the same name in its place, new
     fields go last (ADR 0053). Node overrides do not change run inputs.
4. Apply node overrides (`TaskOverrides`): agent, model, prompt append/replace, parameter values, add/remove skills,
   config merge; then resolve the agent (step, template, `model-answer`) and check that it fits the template (see
   Agents) and that every required parameter has a value.
5. Look up skills and rules from the agent: the agent's skills plus the step's `addSkills` minus its `removeSkills`
   must exist (with project shadowing); a skill key that names a rule counts as that rule, so steps whose `addSkills`
   name a former skill keep working. The rules are the automatic ones the project sees (global, then the project's
   own, with the shadowing above), followed by the agent's rules that are not automatic anyway. A missing skill or
   rule is reported in `check.problems` and makes `resolve` throw. Every task node has the ports `success` and
   `failure`.
6. An `abstract` leaf cannot be resolved for execution (it can be previewed).

`GET /task-templates/resolve/{key}?projectId=` returns the merged view with `lineage`, `category`, `agent`, `rules`
(automatic ones first and marked), `skills`, `parameters` and `check`, so the owner can see what an agent will
actually receive; `POST` on the same path applies a step's overrides (ADR 0054).

### Migration V305 (ADR 0057, 0058)

`V305__agent_environment.sql` adds `instructions`, `rules`, `skills` and `mcp_servers` to `agent`. A template that is
not a pristine built-in and has skills or rules of its own gets an agent of its own (`<key>-agent`, same scope, the
harness of its current agent, the ground rules plus its rules and skills) and names it; then `skills`,
`remove_skills`, `rules` and `remove_rules` leave `task_template`, which gets `parameters` and `parameter_values`.
Pristine built-ins are reshaped by the seed. Library imports ignore template skills and rules with a warning.

## Seeded content

Agents (five, `GLOBAL`, `builtIn`): see the table under [Agents](#agents-adr-0056-0057). Built-in agents nobody
edited are upgraded by the seed like templates.

Templates (all `GLOBAL`, `builtIn`, stamped `updatedBy = system`; twenty-one in total, the fourteen below plus the
seven SDLC templates). Since ADR 0058 they are flat: only `implement-task-list` and the deprecated
`implement-claude-code` extend `implement-opencode`; how to work (preamble, ground rules, format skills) comes from
the agent. Every concrete template has a category. "agent" is the template's own `agentKey`:

| Key | extends | category | agent |
|---|---|---|---|
| `base-agent` (abstract, deprecated) | — | — | — (emptied: its text is the platform preamble now; keeps only the "Run input" section and the run input `input` for user templates that extend it) |
| `base-llm` (abstract, deprecated) | `base-agent` | — | — (empty) |
| `base-coding-agent` (abstract, deprecated) | `base-agent` | — | `opencode` (empty otherwise) |
| `spec-kit-specify` | — | `SPECIFICATION` | `opencode` (config `phase` SPECIFY; user prompt starts with `KEY: summary`, the issue text and acceptance criteria, then the instructions) |
| `clarify-spec` | — | `SPECIFICATION` | `analyst` (schema with `confidence`, `clarifications` as markdown, `summary`) |
| `spec-kit-plan` | — | `PLANNING` | `opencode` (PLAN; the spec is read from the repository, the prompt carries feedback and the optional run parameter `techHints`) |
| `spec-kit-tasks` | — | `PLANNING` | `opencode` (TASKS; the plan is read from the repository) |
| `implement-opencode` | — | `IMPLEMENTATION` | `opencode` (push, MR push options from `mergeRequest`, `mergeRequestTitleTemplate`, `mergeRequestDraft`; `verifyCommand` from config, none by default; on a rework the prompt carries the latest `review` artefact) |
| `implement-claude-code` (deprecated) | `implement-opencode` | (inherited) | `claude-code` (otherwise `implement-opencode`) |
| `code-review` | — | `REVIEW` | `reviewer` (findings schema; instructions before the data, the diff last; the issue or, in CI runs, the merge request title and description as context; one call, no chunking) |
| `analyse-issue` (deprecated) | — | `ANALYSIS` | `analyst` (the whole JSON answer is the `analysis` artefact) |
| `llm-task` | — | `GENERAL` | `model-answer` (generic task for any LLM work, ADR 0034: the task is the parameter `task` (`MULTILINE`, default "summarise the input material in at most five bullet points and list open questions"), the user prompt is `{{params.task}}`; the run input `input` reaches the model as a data block, revision feedback is appended; used by the starter workflow **LLM task with review** of projects without a repository) |
| `agent-task` | — | `GENERAL` | `claude-code` (generic coding-agent task: the task is the parameter `task` (default "describe the repository"); issue, run input `input` and feedback are in the system prompt; `push` false by default, true for a branch and merge request; `repositoryOptional`: in a project without repositories an empty workspace, the final answer becomes the `result` artefact) |
| `prepare-workspace` | — | `OPERATIONS` | `opencode` (ADR 0044: `prepareOnly`, `workspace` = `main`: clones the step's repositories and sources into the run workspace and runs `setupCommand`; starts no agent, never pushes) |

Steps that replaced the prompt of `llm-task` or `agent-task` (`promptReplace`) keep working: the replaced prompt
wins over `{{params.task}}`.

The former built-in templates `fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow` and `project-digest`
are gone (V304 deletes them; the seed removes them too). Their work is the node kind `ACTION` with `action.type`
`FETCH_ISSUE`, `COMMENT_ISSUE`, `TRANSITION_ISSUE`, `START_WORKFLOW` or `PROJECT_DIGEST`; their former template
defaults are the action defaults (for example `messageTemplate` =
`Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}` for `COMMENT_ISSUE`, `toStatus` =
`In Review` and `failIfMissing` = `false` for `TRANSITION_ISSUE`). A stored `TASK` node naming one of them is read as
the `ACTION` node with those defaults, the step's `config` and its `promptReplace` as the comment message.
`COMMENT_ISSUE` renders `messageTemplate` over the template model (variables:
[designing-a-workflow.md](../guides/designing-a-workflow.md#template-variables)). `TRANSITION_ISSUE` fails the node
when the issue's workflow offers no transition to `toStatus` (e.g. it is already there) unless `failIfMissing` is
`false`: then the node succeeds with a note in the run log; an absent `failIfMissing` counts as `true`.

SDLC templates (ADR 0019, derived from a reference SDLC framework, written in English):

| Key | extends | category | agent, how it runs | consumes → produces |
|---|---|---|---|---|
| `write-user-story` | — | `SPECIFICATION` | `analyst`; model answer, schema `user-story`, `openQuestions[]`, `confidence` (confidenceField) | `issue` → `user-story`, `confidence` |
| `write-use-case` | — | `SPECIFICATION` | `analyst`; model answer, schema `use-case`, `openQuestions[]`, `confidence` | `user-story` → `use-case`, `confidence` |
| `write-adr` | — | `PLANNING` | `analyst`; model answer, schema `adr`, `options[]`, `decision`, `openQuestions[]`, `confidence` | `issue` (+ `analysis`) → `adr`, `confidence` |
| `impact-analysis` | — | `ANALYSIS` | `analyst`; model answer, CR sections: description, business justification, impact (artefacts, components, new stories, specification), estimate, options, recommendation, `openQuestions[]`, `confidence` | `issue` → `impact-analysis`, `confidence` |
| `plan-task-list` | — | `PLANNING` | `analyst`; model answer, schema `tasks[] {title, description, verification, test_command, completed: false}` + `finalTaskTitle` enum `reformat, lint, and test` | `plan`, `issue` (+ `impact-analysis`, `user-story`) → `tasks` |
| `implement-task-list` | `implement-opencode` | (`IMPLEMENTATION`, inherited) | `opencode` (inherited); agent job; user prompt replaced: tasks in order with TDD, run each `test_command`, stop on the first red verification, no commits by the agent, work log in `work-log.md` (collected as `work-log`, kept out of the commit) ending with `RESULT: GREEN` or `RESULT: STOPPED at <task>`, which `verifyCommand` checks; "Noticed out of scope" list without fixes | `tasks` → `diff`, `mr`, `work-log` |
| `review-against-spec` | — | `REVIEW` | `reviewer`; model answer, review schema plus `coverage[] {requirement, status MET/PARTIAL/MISSING/NOT_APPLICABLE, evidence}`; the matrix stays in the `review` artefact and a MISSING requirement makes `ReviewVerdictPolicy` return REQUEST_CHANGES | `diff`, `user-story`, `tasks` → `review` |

The skills these tasks need come from their agent (`analyst`: the format skills, `definition-of-ready-done`,
`build-guardrails`; `reviewer`: `security-review-checklist`, `definition-of-ready-done`; `opencode`:
`definition-of-ready-done` among others).

A model answer step turns a structured field into an artefact only when the field name equals the
`produces` name, so the schemas name their document fields like the artefacts: `write-user-story`,
`write-use-case` and `write-adr` store the markdown of `user-story`, `use-case` and `adr`, `clarify-spec`
the markdown `clarifications` and `plan-task-list` the `tasks` array; `analyse-issue` and
`impact-analysis` store their whole JSON answer on purpose. A seed test checks that every `produces` name
of every built-in template is a schema field, a collected file (`work-log.md` becomes `work-log`) or an
output of the way it runs (model answer, Spec Kit phase or agent job). The review guard and the review model default recognise review steps by
`produces review` or category `REVIEW`.

Skills (eight): `spec-writing`, `conventional-commits`, `security-review-checklist`, and the SDLC skills `definition-of-ready-done` (binary DoR/DoD per user story, use case,
ADR, implementation task and release; failing DoR returns to the author; exceptions only with a recorded decision;
"DoD by vibe"), `user-story-format`, `use-case-format`, `adr-format` and `build-guardrails` (IDs `GR-A/S/Q/D`, a
rule the build does not enforce does not exist, every rule has a negative test). Each is a SKILL.md with a
frontmatter `name`/`description` ("Use when ...") and concrete instructions; agents list them (ADR 0057), and a few
built-in prompts name the skill the task relies on.

Rules (four, none automatic): `agent-ground-rules` (evidence, scope and safety: re-read before claiming, paths as
evidence, a hit outside scope is not a task, narrowest edit, report contradictions) and `ask-dont-guess` (source
hierarchy, one open-question marker per context: `[NEEDS CLARIFICATION: ...]` in Spec Kit specs, `[?]` elsewhere,
no invented owner or date, TODO/TBD with a reason, append not rewrite, never change status fields) are listed by
every built-in agent (and by the agents V305 creates); `kotlin-hexagonal` and `angular-ng-zorro` describe the Back Office
stack and are examples to copy into a project rule and make automatic there. Rules carry no frontmatter. Until
ADR 0055 all four were skills; `V303__rules_and_categories.sql` moves them from `skill` to `rule` in every scope,
moves their keys from templates' `skills`/`removeSkills` to `rules`/`removeRules`, and derives `category` from the
first implemented interface, else from the executor (removed later by ADR 0056; V305 then moved template rules and
skills to agents).

Seeding is idempotent by key. A built-in template, skill, rule or agent nobody edited (saved by the system
or before actor stamps existed, and for templates still version 1) is replaced by the shipped definition
when their content differs (for templates, skills and rules by a checksum over every editable field, including
category, parameters and parameter values, that normalises what the database changes: null config values, key
order, number types), so an unchanged entry is not rewritten on every start. Edited entries and non-built-in copies
are never overwritten (`seeded task library templates=... skills=... rules=... upgraded ...` in the startup log).

## Access and audit (ADR 0017, 0018)

| Operation | GLOBAL entry | PROJECT entry |
|---|---|---|
| list, get, resolve | `library.view` | `project.view` or `library.project.manage` on the project |
| create, update, delete | `library.manage` | `library.project.manage` on the project |

The same applies to templates, skills, rules and agents. Moving an entry between scopes needs the write permission of
both. `TaskCatalog` (used by the workflow engine and designer) is a system operation and checks nothing itself.
Every change is audited: `TEMPLATE_CREATE/UPDATE/DELETE` (update diff covers key, name, scope, `extendsKey`,
category, agent, prompt lengths and whether the prompt changed, parameters, config keys, schema length,
produces/consumes, run inputs), `SKILL_*`, `RULE_CREATE/UPDATE/DELETE` (`automatic`, content length), `AGENT_*`;
entries carry `updatedBy` (`V301__actor_stamps.sql`). Built-in templates cannot be deleted; a template another one
extends, an agent a template names, a skill or a rule an agent lists cannot be deleted, and their key and scope
cannot change.

## API

`/api/v1/task-templates` (+ `/resolve/{key}`, GET for the template, POST with a step's overrides),
`/api/v1/skills`, `/api/v1/rules`, `/api/v1/agents` (ADR 0056) — CRUD per `openapi.yaml`; `key` is optional on
create and update. `GET /api/v1/workflows/task-config-schemas` gives the config schema of model answer and coding
agent steps (it replaced `/workflows/task-executors`). Built-in
entries can be edited and shadowed per project; built-in templates cannot be deleted.

## UI

**Library** has three groups (ADR 0055, 0056): **Tasks** (Templates, Skills, Rules, Agents), **Workflows** (the workflow
library belongs to the Projects context, ADR 0037 and
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#workflow-library)) and **Sources**. The template list is
grouped by category and marks templates that **needs parameters**; the template editor shows the inheritance chain,
the field **Agent** with the read-only **Environment of the agent** (instructions, rules with automatic ones marked,
skills), **Parameters** and **Parameter values**, **Create preset**, an "effective prompt" preview (resolved, with
the agent's skills and rules and a **Parameters** tab) and the problems of the check. The agent editor has
**Instructions**, **Rules**, **Skills** and **MCP servers**. The old `/library/interfaces` link redirects to the
templates. The workflow designer palette lists concrete templates grouped by category with their agent and the
**needs parameters** mark, and the actions in a group of their own; a task step has **Parameters**
(`overrides.parameters`), **Agent** (`overrides.agentKey`) and, for coding agents, **Agent session**
(`config.continueSession`).
