# 07 — Task Library

The Task Library is a bounded context (`tasks-core`, `tasks-adapters`) that owns what agents do and
how they are prompted, independent of any single workflow (ADR 0015). Projects use it through the
`TaskCatalog` port; benchmarks may reference templates in a later iteration.

## Concepts

| Concept | Analogy | Content |
|---|---|---|
| `TaskInterface` | interface | `key`, `name`, `description`, `inputs[]` (name, kind `INPUT|STATE|ARTIFACT`, required), `outputs[]` (name, kind `STATE|ARTIFACT`), `ports[]` (default `success`, `failure`) |
| `TaskTemplate` | class | `key`, `name`, `description`, `abstract`, `extendsKey`, `implements[]`, `executor`, `scope` (`GLOBAL` or `PROJECT(projectId)`), `modelProfileId`, `prompt {system, user, mode APPEND|REPLACE}`, `skills[]`, `config{}`, `outputSchema`, `confidenceField`, `produces[]`, `consumes[]`, `tags[]`, `builtIn`, `version` |
| `Skill` | mixin / library | `key`, `name`, `description`, `content` (markdown, Agent Skills `SKILL.md` convention), `scope`, `tags`, `builtIn` |
| `ResolvedTask` | instance | the merged result used by executors |

## Resolution rules (`TaskResolver`, pure domain service)

1. Find the template by key: a `PROJECT` template for the calling project shadows a `GLOBAL` one.
2. Walk `extendsKey` up to the root (max depth 8, cycles are a `ValidationException`). Each ancestor is
   resolved with the same project-shadowing rule.
3. Merge root → leaf:
   - `executor`: nearest defined wins (usually the root's);
   - `modelProfileId`, `outputSchema`, `confidenceField`: child wins when set;
   - `prompt.system` / `prompt.user`: child `APPEND` = parent + blank line + child; `REPLACE` = child only;
   - `skills`: union in order, minus the child's `removeSkills`;
   - `config`: deep merge, child keys win, `null` deletes;
   - `produces`, `consumes`, `implements`, `tags`: union.
4. Apply node overrides (`TaskOverrides`): model, prompt append/replace, add/remove skills, config merge.
5. Check interfaces: every interface implemented anywhere in the lineage must have all `outputs` in
   `produces` and all required `ARTIFACT`/`STATE` inputs in `consumes`; `ports` are the union of interface
   ports. Missing pieces are reported as `interfaceCheck.problems` and make `resolve` throw.
6. An `abstract` leaf cannot be resolved for execution (it can be previewed).

`GET /task-templates/resolve/{key}?projectId=` returns the merged view with `lineage` and
`interfaceCheck`, so the owner can see what an agent will actually receive.

## Seeded content

Interfaces: `Specifier` (in: `issue`; out: artefact `spec`), `Clarifier` (in: `spec`; out: state
`confidence`, artefact `clarifications`), `Planner` (in: `spec`; out: `plan`), `TaskPlanner` (in: `plan`;
out: `tasks`), `Implementer` (in: `tasks` or `issue`; out: `diff`, state `mr`), `Reviewer` (in: `diff`;
out: artefact `review`, state `review`), `Analyst` (in: `issue`; out: `analysis`), and from the SDLC
conventions of ADR 0019 `RequirementWriter` (`requirement-writer`; in: `issue`; out: artefact
`user-story`) and `ChangeAnalyst` (`change-analyst`; in: `issue`; out: artefact `impact-analysis`, state
`confidence`).

Templates (all `GLOBAL`, `builtIn`, stamped `updatedBy = system`; twenty in total, the thirteen below plus the
seven SDLC templates):

| Key | extends | implements | executor |
|---|---|---|---|
| `base-agent` (abstract) | — | — | — (system prompt: role, constitution placeholder, honesty and scope rules; skills `agent-ground-rules`, `ask-dont-guess`, inherited by every agent) |
| `fetch-issue` | — | — | `FETCH_ISSUE` |
| `spec-kit-specify` | `base-agent` | `Specifier` | `SPEC_KIT` (phase SPECIFY) |
| `clarify-spec` | `base-agent` | `Clarifier` | `LLM` (schema with `confidence`, `questions[]`) |
| `spec-kit-plan` | `base-agent` | `Planner` | `SPEC_KIT` (PLAN) |
| `spec-kit-tasks` | `base-agent` | `TaskPlanner` | `SPEC_KIT` (TASKS) |
| `implement-opencode` | `base-agent` | `Implementer` | `IMPLEMENT` (agent OPENCODE, push, MR push options, verifyCommand from config) |
| `implement-claude-code` | `implement-opencode` | — | `IMPLEMENT` (agent CLAUDE_CODE) |
| `code-review` | `base-agent` | `Reviewer` | `LLM` (findings schema, chunked diff) |
| `analyse-issue` | `base-agent` | `Analyst` | `LLM` |
| `llm-task` | — | — | `LLM` (generic task for any LLM work, ADR 0034: the prompt is written per node with the override "replace prompt", the run parameter `input` reaches the model as a data block, revision feedback is appended; used by the starter workflow **LLM task with review** of projects without a repository) |
| `notify-jira` | — | — | `NOTIFY` (Jira comment; `config.messageTemplate` = `Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}`) |
| `transition-issue` | — | — | `TRANSITION_ISSUE` (`config.toStatus` = `In Review`, `config.failIfMissing` = `false`) |

`NOTIFY` renders `config.messageTemplate`, else the task's user prompt, else
`Back Office run {{run.short}} of workflow {{run.workflow}}: {{node.name}}.` over the template model
(variables: [designing-a-workflow.md](../guides/designing-a-workflow.md#template-variables)).
`TRANSITION_ISSUE` fails the node when the issue's workflow offers no transition to `toStatus` (e.g. it is
already there) unless `config.failIfMissing` is `false`: then the node succeeds with a note in the run log.
The executor treats an absent `failIfMissing` as `true`; the built-in template sets `false`.

SDLC templates (ADR 0019, derived from a reference SDLC framework, written in English):

| Key | extends | implements | executor | consumes → produces | skills (besides the inherited ground rules) |
|---|---|---|---|---|---|
| `write-user-story` | `base-agent` | `RequirementWriter` | `LLM`, schema `userStory`, `openQuestions[]`, `confidence` (confidenceField) | `issue` → `user-story`, `confidence` | `user-story-format`, `ask-dont-guess`, `definition-of-ready-done` |
| `write-use-case` | `base-agent` | — | `LLM`, schema `useCase`, `openQuestions[]`, `confidence` | `user-story` → `use-case`, `confidence` | `use-case-format`, `definition-of-ready-done` |
| `write-adr` | `base-agent` | — | `LLM`, schema `adr`, `options[]`, `decision`, `openQuestions[]`, `confidence` | `issue` (+ `analysis`) → `adr`, `confidence` | `adr-format`, `definition-of-ready-done` |
| `impact-analysis` | `base-agent` | `ChangeAnalyst` | `LLM`, CR sections: description, business justification, impact (artefacts, components, new stories, specification), estimate, options, recommendation, `openQuestions[]`, `confidence` | `issue` → `impact-analysis`, `confidence` | `definition-of-ready-done` |
| `plan-task-list` | `base-agent` | `TaskPlanner` | `LLM`, schema `tasks[] {title, description, verification, test_command, completed: false}` + `finalTaskTitle` enum `reformat, lint, and test` | `plan`, `issue` (+ `user-story`) → `tasks` | `definition-of-ready-done`, `build-guardrails` |
| `implement-task-list` | `implement-opencode` | (`Implementer`, inherited) | `IMPLEMENT`; user prompt replaced: tasks in order with TDD, run each `test_command`, stop on the first red verification, work log in `work-log.md` (collected), "Noticed out of scope" list without fixes | `tasks` → `diff`, `mr`, `work-log` | `build-guardrails`, `definition-of-ready-done` |
| `review-against-spec` | `base-agent` | `Reviewer` | `LLM`, review schema plus `coverage[] {requirement, status MET/PARTIAL/MISSING/NOT_APPLICABLE, evidence}` | `diff`, `user-story`, `tasks` → `review` | `definition-of-ready-done`, `security-review-checklist` |

The LLM executor turns a structured field into an artefact only when the field name equals the
`produces` name; `write-user-story`, `write-use-case` and `impact-analysis` therefore store their whole
JSON answer (document plus open questions and confidence) as the artefact, `write-adr` stores the
markdown of `adr` and `plan-task-list` stores the `tasks` array.

Skills: `spec-writing`, `kotlin-hexagonal`, `conventional-commits`, `security-review-checklist`,
`angular-ng-zorro`, and the SDLC skills `agent-ground-rules` (edit protocol: before/after proposal,
only what was asked, a grep hit outside scope is not a task, narrowest edit, re-read before claiming,
stop on contradictions), `ask-dont-guess` (source hierarchy, `[?]` markers with owner and date,
TODO/TBD with a reason, append not rewrite, never change status fields), `definition-of-ready-done`
(binary DoR/DoD per user story, use case, ADR, implementation task and release; failing DoR returns to
the author; exceptions only with a recorded decision; "DoD by vibe"), `user-story-format`,
`use-case-format`, `adr-format` and `build-guardrails` (IDs `GR-A/S/Q/D`, a rule the build does not
enforce does not exist, every rule has a negative test). Each is a SKILL.md with a frontmatter
`name`/`description` and concrete rules.

Seeding is idempotent by key. Entries that exist are not overwritten; the one exception is a built-in
template nobody edited (version 1, saved by the system), which receives skills later added to its
shipped definition and the shipped `config` when it changed, so older installations get the ground rules
on `base-agent` and the corrected `notify-jira` / `transition-issue` config (`templatesUpgraded` in the
startup log).

## Access and audit (ADR 0017, 0018)

| Operation | GLOBAL entry | PROJECT entry |
|---|---|---|
| list, get, resolve | `library.view` | `project.view` or `library.project.manage` on the project |
| create, update, delete | `library.manage` | `library.project.manage` on the project |
| interfaces (always global) | read `library.view`, change `library.manage` | — |

Moving an entry between scopes needs the write permission of both. `TaskCatalog` (used by the workflow
engine and designer) is a system operation and checks nothing itself. Every change is audited:
`TEMPLATE_CREATE/UPDATE/DELETE` (update diff covers name, scope, `extendsKey`, `implements`, executor,
skills, prompt lengths and whether the prompt changed, config keys, schema length, produces/consumes),
`INTERFACE_*`, `SKILL_*`; entries carry `updatedBy` (`V301__actor_stamps.sql`).

## API

`/api/v1/task-interfaces`, `/api/v1/task-templates` (+ `/resolve/{key}`), `/api/v1/skills` — CRUD per
`openapi.yaml`. Built-in entries can be edited and shadowed per project, not deleted.

## UI

Library section with three tabs (Templates, Interfaces, Skills). The template editor shows the
inheritance chain, an "effective prompt" preview (resolved), the interface check, and the skill picker.
The workflow designer palette lists concrete templates grouped by interface.
