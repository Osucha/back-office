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

Templates (all `GLOBAL`, `builtIn`, stamped `updatedBy = system`; twenty-four in total, the seventeen below plus the
seven SDLC templates):

| Key | extends | implements | executor |
|---|---|---|---|
| `base-agent` (abstract) | — | — | — (short shared system prompt: role, constitution placeholder, honesty, scope and safety rules; skills `agent-ground-rules`, `ask-dont-guess`, inherited by every agent) |
| `base-llm` (abstract) | `base-agent` | — | — (in-process LLM steps: no repository and no tools; with an output schema the answer is one JSON object, uncertainty goes into its fields) |
| `base-coding-agent` (abstract) | `base-agent` | — | — (runner steps: work in the repository clone, file paths as evidence, never commit or push (the runner commits), never disable tests, skills are named in the task) |
| `fetch-issue` | — | — | `FETCH_ISSUE` (summary, description, acceptance criteria, labels; no comments or attachments, no config) |
| `spec-kit-specify` | `base-coding-agent` | `Specifier` | `SPEC_KIT` (phase SPECIFY; user prompt starts with `KEY: summary`, the issue text and acceptance criteria, then the instructions) |
| `clarify-spec` | `base-llm` | `Clarifier` | `LLM` (schema with `confidence`, `clarifications` as markdown, `summary`) |
| `spec-kit-plan` | `base-coding-agent` | `Planner` | `SPEC_KIT` (PLAN; the spec is read from the repository, the prompt carries feedback and the optional run parameter `techHints`) |
| `spec-kit-tasks` | `base-coding-agent` | `TaskPlanner` | `SPEC_KIT` (TASKS; the plan is read from the repository) |
| `implement-opencode` | `base-coding-agent` | `Implementer` | `IMPLEMENT` (agent OPENCODE, push, MR push options from `mergeRequest`, `mergeRequestTitleTemplate`, `mergeRequestDraft`; `verifyCommand` from config, none by default; on a rework the prompt carries the latest `review` artefact) |
| `implement-claude-code` | `implement-opencode` | — | `IMPLEMENT` (agent CLAUDE_CODE) |
| `code-review` | `base-llm` | `Reviewer` | `LLM` (findings schema; instructions before the data, the diff last; the issue or, in CI runs, the merge request title and description as context; one call, no chunking) |
| `analyse-issue` | `base-llm` | `Analyst` | `LLM` (the whole JSON answer is the `analysis` artefact) |
| `llm-task` | — | — | `LLM` (generic task for any LLM work, ADR 0034: the prompt is written per node with the override "replace prompt", the run parameter `input` reaches the model as a data block, revision feedback is appended; used by the starter workflow **LLM task with review** of projects without a repository) |
| `agent-task` | — | — | `IMPLEMENT` (generic coding-agent task: the prompt is written per node with "replace prompt"; issue, run parameter `input` and feedback are in the system prompt; agent CLAUDE_CODE, `push` false by default, true for a branch and merge request; `repositoryOptional`: in a project without repositories an empty workspace, the final answer becomes the `result` artefact; skills `agent-ground-rules`, `ask-dont-guess`) |
| `prepare-workspace` | — | — | `IMPLEMENT` (ADR 0044: `prepareOnly`, `workspace` = `main`: clones the step's repositories and sources into the run workspace and runs `setupCommand`; no agent, never pushes) |
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
| `write-user-story` | `base-llm` | `RequirementWriter` | `LLM`, schema `user-story`, `openQuestions[]`, `confidence` (confidenceField) | `issue` → `user-story`, `confidence` | `user-story-format`, `definition-of-ready-done` |
| `write-use-case` | `base-llm` | — | `LLM`, schema `use-case`, `openQuestions[]`, `confidence` | `user-story` → `use-case`, `confidence` | `use-case-format`, `definition-of-ready-done` |
| `write-adr` | `base-llm` | — | `LLM`, schema `adr`, `options[]`, `decision`, `openQuestions[]`, `confidence` | `issue` (+ `analysis`) → `adr`, `confidence` | `adr-format`, `definition-of-ready-done` |
| `impact-analysis` | `base-llm` | `ChangeAnalyst` | `LLM`, CR sections: description, business justification, impact (artefacts, components, new stories, specification), estimate, options, recommendation, `openQuestions[]`, `confidence` | `issue` → `impact-analysis`, `confidence` | `definition-of-ready-done` |
| `plan-task-list` | `base-llm` | `TaskPlanner` | `LLM`, schema `tasks[] {title, description, verification, test_command, completed: false}` + `finalTaskTitle` enum `reformat, lint, and test` | `plan`, `issue` (+ `impact-analysis`, `user-story`) → `tasks` | `definition-of-ready-done`, `build-guardrails` |
| `implement-task-list` | `implement-opencode` | (`Implementer`, inherited) | `IMPLEMENT`; user prompt replaced: tasks in order with TDD, run each `test_command`, stop on the first red verification, no commits by the agent, work log in `work-log.md` (collected as `work-log`, kept out of the commit) ending with `RESULT: GREEN` or `RESULT: STOPPED at <task>`, which `verifyCommand` checks; "Noticed out of scope" list without fixes | `tasks` → `diff`, `mr`, `work-log` | `definition-of-ready-done` |
| `review-against-spec` | `base-llm` | `Reviewer` | `LLM`, review schema plus `coverage[] {requirement, status MET/PARTIAL/MISSING/NOT_APPLICABLE, evidence}`; the matrix stays in the `review` artefact and a MISSING requirement makes `ReviewVerdictPolicy` return REQUEST_CHANGES | `diff`, `user-story`, `tasks` → `review` | `definition-of-ready-done`, `security-review-checklist` |

The LLM executor turns a structured field into an artefact only when the field name equals the
`produces` name, so the schemas name their document fields like the artefacts: `write-user-story`,
`write-use-case` and `write-adr` store the markdown of `user-story`, `use-case` and `adr`, `clarify-spec`
the markdown `clarifications` and `plan-task-list` the `tasks` array; `analyse-issue` and
`impact-analysis` store their whole JSON answer on purpose. A seed test checks that every `produces` name
of every built-in template is a schema field, a collected file (`work-log.md` becomes `work-log`) or an
output of its executor.

Skills: `spec-writing`, `kotlin-hexagonal`, `conventional-commits`, `security-review-checklist`,
`angular-ng-zorro` (the last two describe the Back Office stack and are examples), and the SDLC skills
`agent-ground-rules` (evidence, scope and safety: re-read before claiming, paths as evidence, a hit
outside scope is not a task, narrowest edit, report contradictions), `ask-dont-guess` (source hierarchy,
one open-question marker per context: `[NEEDS CLARIFICATION: ...]` in Spec Kit specs, `[?]` elsewhere,
no invented owner or date, TODO/TBD with a reason, append not rewrite, never change status fields), `definition-of-ready-done`
(binary DoR/DoD per user story, use case, ADR, implementation task and release; failing DoR returns to
the author; exceptions only with a recorded decision; "DoD by vibe"), `user-story-format`,
`use-case-format`, `adr-format` and `build-guardrails` (IDs `GR-A/S/Q/D`, a rule the build does not
enforce does not exist, every rule has a negative test). Each is a SKILL.md with a frontmatter
`name`/`description` ("Use when ...") and concrete rules; the prompts of the runner templates name the
skills to apply.

Seeding is idempotent by key. A built-in template, skill or interface nobody edited (saved by the system
or before actor stamps existed, and for templates still version 1) is replaced by the shipped definition
when their content checksums differ; the checksum covers every editable field and normalises what the
database changes (null config values, key order, number types), so an unchanged entry is not rewritten on
every start. Edited entries and non-built-in copies are never overwritten (`templatesUpgraded`,
`skillsUpgraded`, `interfacesUpgraded` in the startup log).

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

Library section with four tabs (Templates, Interfaces, Skills and Workflows; the workflow library belongs to the
Projects context, ADR 0037 and [03-pipelines-and-agents.md](03-pipelines-and-agents.md#workflow-library)). The template editor shows the
inheritance chain, an "effective prompt" preview (resolved), the interface check, and the skill picker.
The workflow designer palette lists concrete templates grouped by interface.
