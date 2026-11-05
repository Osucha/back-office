# 07 — Task Library

The Task Library is a bounded context (`tasks-core`, `tasks-adapters`) that owns what agents do and
how they are prompted, independent of any single workflow (ADR 0015). Projects use it through the
`TaskCatalog` port; benchmarks may reference templates in a later iteration.

## Concepts

| Concept | Analogy | Content |
|---|---|---|
| `TaskTemplate` | class | `key`, `name`, `description`, `abstract`, `extendsKey`, `category`, `executor`, `scope` (`GLOBAL` or `PROJECT(projectId)`), `modelProfileId`, `prompt {system, user, mode APPEND|REPLACE}`, `skills[]`, `removeSkills[]`, `rules[]`, `removeRules[]`, `config{}`, `outputSchema`, `confidenceField`, `produces[]`, `consumes[]`, `tags[]`, `runInputs[]` (fields of the run start form, ADR 0053), `builtIn`, `version` |
| `TemplateCategory` | grouping | `ANALYSIS`, `SPECIFICATION`, `PLANNING`, `IMPLEMENTATION`, `REVIEW`, `JIRA`, `OPERATIONS`, `GENERAL`; shared kernel; groups the library list, the designer palette and MCP results (ADR 0055) |
| `RunInputField` | form field | `name` (key in the run parameters), `label`, `type` `TEXT|MULTILINE|ISSUE_KEY`, `required`, `help`; shared kernel, so the Projects context reads it from `ResolvedTask.runInputs` |
| `Skill` | handbook chapter | `key`, `name`, `description`, `content` (markdown, Agent Skills `SKILL.md` convention), `scope`, `tags`, `builtIn`; knowledge or a procedure the agent opens when the task needs it |
| `Rule` | standing order | `key`, `name`, `description`, `content` (markdown, no frontmatter), `scope`, `automatic`, `tags`, `builtIn`; a norm that is always in the agent's context (ADR 0055) |
| `ResolvedTask` | instance | the merged result used by executors |

Until ADR 0055 the library also had task interfaces (`implements`, inputs, outputs, ports). No workflow ever
referred to one: they only re-checked `consumes`/`produces`, never added a port and labelled templates in the
palette. They were removed (V303 drops the `task_interface` table); the grouping they gave is now the template's
`category`.

### Keys (ADR 0055)

Keys match `^[a-z][a-z0-9-]{1,63}$` and are what workflows, MCP and the API refer to; people do not type them.
On create a template, skill or rule without a key gets one from its name (`Keys.fromName`: diacritics dropped,
everything but letters and digits becomes `-`, a `task-`/`skill-`/`rule-` prefix when the name starts with a digit
or yields nothing) made unique by `Keys.unique` (`-2`, `-3`, ...). Template keys are unique across scopes, so a new
template never shadows anything by accident; skills and rules are unique by `(key, scope)`. An update without a key
keeps it, so renaming changes the name only; the key and scope of an entry other templates use (extend, list as a
skill or rule) cannot change. An explicit key on create is still accepted: a `PROJECT` template with a global
template's key and the same `extendsKey` customises that template for the project (UI: **Customize for project**).

### Rules (ADR 0055)

A rule applies to the templates that list it in `rules` (inherited and unioned like skills) or, when `automatic`,
to every step of its scope: a global automatic rule to every step of every project, a project one to every step of
that project. `removeRules` on a template drops inherited and automatic rules for it and its children. A `PROJECT`
rule with a global rule's key replaces it for that project; a non-automatic project copy of a global automatic rule
switches it off for that project. Rules are delivered by `RulesPrompt` as a `<rules>` block: model answer (`LLM`)
steps get it in the system prompt before the `<skills>` section, coding agent (`IMPLEMENT`) and `SPEC_KIT` steps at
the start of their prompt right after the template's system part. Nothing is written into the repository, so both
agents see the same text. Skills, by contrast, reach runner steps as `.claude/skills/<key>/SKILL.md` and
`.opencode/skills/<key>/SKILL.md`, which the agent opens on demand.

## Resolution rules (`TaskResolver`, pure domain service)

1. Find the template by key: a `PROJECT` template for the calling project shadows a `GLOBAL` one (same for skills
   and rules).
2. Walk `extendsKey` up to the root (max depth 8, cycles are a `ValidationException`). Each ancestor is
   resolved with the same project-shadowing rule; a project template that extends its own key extends the global one.
3. Merge root → leaf:
   - `executor`, `category`: nearest defined wins;
   - `modelProfileId`, `outputSchema`, `confidenceField`: child wins when set;
   - `prompt.system` / `prompt.user`: child `APPEND` = parent + blank line + child; `REPLACE` = child only;
   - `skills`: union in order, minus the child's `removeSkills`;
   - `rules`: union in order, minus the child's `removeRules`;
   - `config`: deep merge, child keys win, `null` deletes;
   - `produces`, `consumes`, `tags`: union;
   - `runInputs`: union by `name`; a child field replaces the parent's field of the same name in its place, new
     fields go last (ADR 0053). Node overrides do not change run inputs.
4. Apply node overrides (`TaskOverrides`): model, prompt append/replace, add/remove skills, config merge.
5. Look up skills and rules: every skill key must exist (with project shadowing); a skill key that names a rule
   counts as that rule, so steps whose `addSkills` name a former skill keep working. The rules are the automatic
   ones the project sees (global, then the project's own, with the shadowing above) minus every `removeRules` of the
   lineage, followed by the listed rules that are not automatic anyway. A missing skill or rule is reported in
   `check.problems` and makes `resolve` throw. Every task node has the ports `success` and `failure`.
6. An `abstract` leaf cannot be resolved for execution (it can be previewed).

`GET /task-templates/resolve/{key}?projectId=` returns the merged view with `lineage`, `category`, `rules`
(automatic ones first and marked) and `check`, so the owner can see what an agent will actually receive;
`POST` on the same path applies a step's overrides (ADR 0054).

## Seeded content

Templates (all `GLOBAL`, `builtIn`, stamped `updatedBy = system`; twenty-six in total, the nineteen below plus the
seven SDLC templates). Every concrete template has a category, set or inherited:

| Key | extends | category | executor |
|---|---|---|---|
| `base-agent` (abstract) | — | — | — (short shared system prompt: role, constitution placeholder, honesty, scope and safety rules; rules `agent-ground-rules`, `ask-dont-guess`, inherited by every agent) |
| `base-llm` (abstract) | `base-agent` | — | — (in-process LLM steps: no repository and no tools; with an output schema the answer is one JSON object, uncertainty goes into its fields) |
| `base-coding-agent` (abstract) | `base-agent` | — | — (runner steps: work in the repository clone, file paths as evidence, never commit or push (the runner commits), never disable tests, skills are named in the task) |
| `fetch-issue` | — | `JIRA` | `FETCH_ISSUE` (summary, description, acceptance criteria, labels; no comments or attachments, no config) |
| `spec-kit-specify` | `base-coding-agent` | `SPECIFICATION` | `SPEC_KIT` (phase SPECIFY; user prompt starts with `KEY: summary`, the issue text and acceptance criteria, then the instructions) |
| `clarify-spec` | `base-llm` | `SPECIFICATION` | `LLM` (schema with `confidence`, `clarifications` as markdown, `summary`) |
| `spec-kit-plan` | `base-coding-agent` | `PLANNING` | `SPEC_KIT` (PLAN; the spec is read from the repository, the prompt carries feedback and the optional run parameter `techHints`) |
| `spec-kit-tasks` | `base-coding-agent` | `PLANNING` | `SPEC_KIT` (TASKS; the plan is read from the repository) |
| `implement-opencode` | `base-coding-agent` | `IMPLEMENTATION` | `IMPLEMENT` (agent OPENCODE, push, MR push options from `mergeRequest`, `mergeRequestTitleTemplate`, `mergeRequestDraft`; `verifyCommand` from config, none by default; on a rework the prompt carries the latest `review` artefact) |
| `implement-claude-code` (deprecated) | `implement-opencode` | (inherited) | `IMPLEMENT` (agent CLAUDE_CODE) |
| `code-review` | `base-llm` | `REVIEW` | `LLM` (findings schema; instructions before the data, the diff last; the issue or, in CI runs, the merge request title and description as context; one call, no chunking) |
| `analyse-issue` (deprecated) | `base-llm` | `ANALYSIS` | `LLM` (the whole JSON answer is the `analysis` artefact) |
| `llm-task` | — | `GENERAL` | `LLM` (generic task for any LLM work, ADR 0034: the prompt is written per node with the override "replace prompt", the run parameter `input` reaches the model as a data block, revision feedback is appended; used by the starter workflow **LLM task with review** of projects without a repository) |
| `agent-task` | — | `GENERAL` | `IMPLEMENT` (generic coding-agent task: the prompt is written per node with "replace prompt"; issue, run parameter `input` and feedback are in the system prompt; agent CLAUDE_CODE, `push` false by default, true for a branch and merge request; `repositoryOptional`: in a project without repositories an empty workspace, the final answer becomes the `result` artefact; rules `agent-ground-rules`, `ask-dont-guess`) |
| `prepare-workspace` | — | `OPERATIONS` | `IMPLEMENT` (ADR 0044: `prepareOnly`, `workspace` = `main`: clones the step's repositories and sources into the run workspace and runs `setupCommand`; no agent, never pushes) |
| `project-digest` | — | `OPERATIONS` | `PROJECT_DIGEST` (ADR 0052: what happened in the project since the run parameter `since`; produces `digest`) |
| `start-workflow` | — | `OPERATIONS` | `START_WORKFLOW` (ADR 0052: one run of another workflow per entry of a JSON artefact; produces `started-runs`) |
| `notify-jira` | — | `JIRA` | `NOTIFY` (Jira comment; `config.messageTemplate` = `Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}`) |
| `transition-issue` | — | `JIRA` | `TRANSITION_ISSUE` (`config.toStatus` = `In Review`, `config.failIfMissing` = `false`) |

`NOTIFY` renders `config.messageTemplate`, else the task's user prompt, else
`Back Office run {{run.short}} of workflow {{run.workflow}}: {{node.name}}.` over the template model
(variables: [designing-a-workflow.md](../guides/designing-a-workflow.md#template-variables)).
`TRANSITION_ISSUE` fails the node when the issue's workflow offers no transition to `toStatus` (e.g. it is
already there) unless `config.failIfMissing` is `false`: then the node succeeds with a note in the run log.
The executor treats an absent `failIfMissing` as `true`; the built-in template sets `false`.

SDLC templates (ADR 0019, derived from a reference SDLC framework, written in English):

| Key | extends | category | executor | consumes → produces | skills (besides the inherited ground rules) |
|---|---|---|---|---|---|
| `write-user-story` | `base-llm` | `SPECIFICATION` | `LLM`, schema `user-story`, `openQuestions[]`, `confidence` (confidenceField) | `issue` → `user-story`, `confidence` | `user-story-format`, `definition-of-ready-done` |
| `write-use-case` | `base-llm` | `SPECIFICATION` | `LLM`, schema `use-case`, `openQuestions[]`, `confidence` | `user-story` → `use-case`, `confidence` | `use-case-format`, `definition-of-ready-done` |
| `write-adr` | `base-llm` | `PLANNING` | `LLM`, schema `adr`, `options[]`, `decision`, `openQuestions[]`, `confidence` | `issue` (+ `analysis`) → `adr`, `confidence` | `adr-format`, `definition-of-ready-done` |
| `impact-analysis` | `base-llm` | `ANALYSIS` | `LLM`, CR sections: description, business justification, impact (artefacts, components, new stories, specification), estimate, options, recommendation, `openQuestions[]`, `confidence` | `issue` → `impact-analysis`, `confidence` | `definition-of-ready-done` |
| `plan-task-list` | `base-llm` | `PLANNING` | `LLM`, schema `tasks[] {title, description, verification, test_command, completed: false}` + `finalTaskTitle` enum `reformat, lint, and test` | `plan`, `issue` (+ `impact-analysis`, `user-story`) → `tasks` | `definition-of-ready-done`, `build-guardrails` |
| `implement-task-list` | `implement-opencode` | (`IMPLEMENTATION`, inherited) | `IMPLEMENT`; user prompt replaced: tasks in order with TDD, run each `test_command`, stop on the first red verification, no commits by the agent, work log in `work-log.md` (collected as `work-log`, kept out of the commit) ending with `RESULT: GREEN` or `RESULT: STOPPED at <task>`, which `verifyCommand` checks; "Noticed out of scope" list without fixes | `tasks` → `diff`, `mr`, `work-log` | `definition-of-ready-done` |
| `review-against-spec` | `base-llm` | `REVIEW` | `LLM`, review schema plus `coverage[] {requirement, status MET/PARTIAL/MISSING/NOT_APPLICABLE, evidence}`; the matrix stays in the `review` artefact and a MISSING requirement makes `ReviewVerdictPolicy` return REQUEST_CHANGES | `diff`, `user-story`, `tasks` → `review` | `definition-of-ready-done`, `security-review-checklist` |

The LLM executor turns a structured field into an artefact only when the field name equals the
`produces` name, so the schemas name their document fields like the artefacts: `write-user-story`,
`write-use-case` and `write-adr` store the markdown of `user-story`, `use-case` and `adr`, `clarify-spec`
the markdown `clarifications` and `plan-task-list` the `tasks` array; `analyse-issue` and
`impact-analysis` store their whole JSON answer on purpose. A seed test checks that every `produces` name
of every built-in template is a schema field, a collected file (`work-log.md` becomes `work-log`) or an
output of its executor. The review guard and the review model default recognise review steps by
`produces review` or category `REVIEW`.

Skills (eight): `spec-writing`, `conventional-commits`, `security-review-checklist`, and the SDLC skills `definition-of-ready-done` (binary DoR/DoD per user story, use case,
ADR, implementation task and release; failing DoR returns to the author; exceptions only with a recorded decision;
"DoD by vibe"), `user-story-format`, `use-case-format`, `adr-format` and `build-guardrails` (IDs `GR-A/S/Q/D`, a
rule the build does not enforce does not exist, every rule has a negative test). Each is a SKILL.md with a
frontmatter `name`/`description` ("Use when ...") and concrete instructions; the prompts of the runner templates
name the skills to apply.

Rules (four, none automatic): `agent-ground-rules` (evidence, scope and safety: re-read before claiming, paths as
evidence, a hit outside scope is not a task, narrowest edit, report contradictions) and `ask-dont-guess` (source
hierarchy, one open-question marker per context: `[NEEDS CLARIFICATION: ...]` in Spec Kit specs, `[?]` elsewhere,
no invented owner or date, TODO/TBD with a reason, append not rewrite, never change status fields) reach every
agent through `base-agent` and `agent-task`; `kotlin-hexagonal` and `angular-ng-zorro` describe the Back Office
stack and are examples to copy into a project rule and make automatic there. Rules carry no frontmatter. Until
ADR 0055 all four were skills; `V303__rules_and_categories.sql` moves them from `skill` to `rule` in every scope,
moves their keys from templates' `skills`/`removeSkills` to `rules`/`removeRules`, and derives `category` from the
first implemented interface, else from the executor.

Seeding is idempotent by key. A built-in template, skill or rule nobody edited (saved by the system
or before actor stamps existed, and for templates still version 1) is replaced by the shipped definition
when their content checksums differ; the checksum covers every editable field (for templates also category and
rules) and normalises what the database changes (null config values, key order, number types), so an unchanged
entry is not rewritten on every start. Edited entries and non-built-in copies are never overwritten
(`seeded task library templates=... skills=... rules=... upgraded templates=... skills=... rules=...` in the
startup log).

## Access and audit (ADR 0017, 0018)

| Operation | GLOBAL entry | PROJECT entry |
|---|---|---|
| list, get, resolve | `library.view` | `project.view` or `library.project.manage` on the project |
| create, update, delete | `library.manage` | `library.project.manage` on the project |

The same applies to templates, skills and rules. Moving an entry between scopes needs the write permission of
both. `TaskCatalog` (used by the workflow engine and designer) is a system operation and checks nothing itself.
Every change is audited: `TEMPLATE_CREATE/UPDATE/DELETE` (update diff covers key, name, scope, `extendsKey`,
category, executor, skills and `removeSkills`, rules and `removeRules`, prompt lengths and whether the prompt
changed, config keys, schema length, produces/consumes, run inputs), `SKILL_*`, `RULE_CREATE/UPDATE/DELETE`
(`automatic`, content length); entries carry `updatedBy` (`V301__actor_stamps.sql`). Built-in templates cannot be
deleted; a template another one extends, a skill or a rule a template lists cannot be deleted, and their key and
scope cannot change.

## API

`/api/v1/task-templates` (+ `/resolve/{key}`, GET for the template, POST with a step's overrides),
`/api/v1/skills`, `/api/v1/rules` — CRUD per `openapi.yaml`; `key` is optional on create and update. Built-in
entries can be edited and shadowed per project; built-in templates cannot be deleted.

## UI

**Library** has three groups (ADR 0055): **Tasks** (Templates, Skills, Rules), **Workflows** (the workflow
library belongs to the Projects context, ADR 0037 and
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#workflow-library)) and **Sources**. The template list is
grouped by category; the template editor shows the inheritance chain, the skill and rule pickers (automatic rules
read-only), an "effective prompt" preview (resolved, with skills and rules) and the problems of the check. The old
`/library/interfaces` link redirects to the templates. The workflow designer palette lists concrete templates
grouped by category.
