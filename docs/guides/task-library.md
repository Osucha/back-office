# Task Library: templates, skills and rules

## When to use this guide

You want to change what an agent does or how it is prompted without editing a workflow: add a new kind
of task, specialise a built-in one for a single project, attach a reusable skill or a rule every agent must
follow, or understand why the resolved prompt an executor receives looks the way it does. The concepts and
merge rules are specified in `docs/architecture/07-task-library.md` (ADR 0015, ADR 0055); this guide walks
through the API (`/api/v1/task-templates`, `/api/v1/skills`, `/api/v1/rules`) and the Library UI
(**Library** → **Tasks** → **Templates**, **Skills**, **Rules**; the other groups are **Workflows** and
**Sources**).

## 1. Concepts in one table

| Concept | Analogy | You use it to |
|---|---|---|
| Task template | `class` | define a task: executor, category, prompts, skills, rules, config, `extends` one parent |
| Rule | standing order | state a norm the agent must always follow; always in the agent's context |
| Skill | handbook chapter | give the agent knowledge or a procedure (`SKILL.md` convention) it opens when the task needs it |
| Resolved task | instance | the merged result an executor receives: template + ancestors + project shadowing + node overrides |

Until ADR 0055 there were also task interfaces (`/api/v1/task-interfaces`, `implements`). No workflow ever
referred to one and they only duplicated `consumes`/`produces`, so they were removed; what they gave, a grouping,
is now the template's `category` (`ANALYSIS`, `SPECIFICATION`, `PLANNING`, `IMPLEMENTATION`, `REVIEW`, `JIRA`,
`OPERATIONS`, `GENERAL`; inherited, child wins), by which the library list and the designer palette are grouped.
Every task node has the ports `success` and `failure`.

Keys match `^[a-z][a-z0-9-]{1,63}$` and are optional in the API. Without one, create derives it from the name
(diacritics dropped, `-` between words, a `task-`/`skill-`/`rule-` prefix when the name starts with a digit,
`-2`, `-3`, ... when taken: `"Analiza zgłoszenia"` becomes `analiza-zgloszenia`); update keeps the stored key.
Template keys are unique across scopes, so a template created without a key never shadows anything; skills and
rules are unique by `(key, scope)` where scope is `GLOBAL` or `PROJECT(projectId)`. Send an explicit key only to
shadow a global entry in a project (section 3).

The built-in library (seeded on startup, `backoffice.tasks.seed-builtin=true`) contains the abstract
`base-agent` with its variants `base-llm` (in-process LLM steps) and `base-coding-agent` (runner steps), the Spec
Kit phases, `implement-opencode` / `implement-claude-code`, `clarify-spec`, `code-review`, `analyse-issue`, the
generic `llm-task` and `agent-task`, `prepare-workspace`, `project-digest`, `start-workflow` and the Jira tasks,
the SDLC templates of section 8, eight skills and four rules (`agent-ground-rules`, `ask-dont-guess`,
`kotlin-hexagonal`, `angular-ng-zorro`). Seeding is idempotent by key: edit built-ins freely, an edited entry is
never overwritten (a never-edited built-in template, skill or rule is replaced by the shipped version when its
content checksum differs); delete a non-built-in copy and restart to get the shipped version back. Built-in
templates cannot be deleted, only shadowed.

The Jira tasks:

- `notify-jira` (`NOTIFY`) posts a comment rendered from `config.messageTemplate`, shipped as
  `Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}` (`{{mr.url}}` is empty
  before an `IMPLEMENT` node). Without a `messageTemplate` the task's user prompt is used, else
  `Back Office run {{run.short}} of workflow {{run.workflow}}: {{node.name}}.`
- `transition-issue` (`TRANSITION_ISSUE`) moves the issue to `config.toStatus` (shipped: `In Review`).
  When the issue's workflow offers no such transition (e.g. the issue is already there),
  `config.failIfMissing` decides: `false` (shipped) skips it with a note in the run log and the node
  succeeds; `true` or absent fails the node.

On older installations a built-in nobody edited (version 1, saved by the system) receives the shipped
config at the next start (`templatesUpgraded` in the startup log); an edited copy keeps its own config, so
change its `messageTemplate` / `failIfMissing` yourself. All variables usable in prompts and
templates: [designing-a-workflow.md](designing-a-workflow.md#template-variables).

### Who may do what

| You want to | GLOBAL (the shared library) | PROJECT (one project's own entries) |
|---|---|---|
| see templates, skills, rules, the resolved preview | `library.view` (role `member` has it) | `project.view` or `library.project.manage` on that project |
| create, edit, delete templates, skills and rules | `library.manage` (role `library-curator`) | `library.project.manage` on that project |

`GET /api/v1/task-templates?projectId=P` (likewise `/skills` and `/rules`) returns the global entries when you
hold `library.view` plus P's entries when you are a member of P; without either you get `403`. Moving an entry
between scopes needs the write permission of both. Every change is recorded in the audit log (`TEMPLATE_*`,
`SKILL_*`, `RULE_CREATE`/`RULE_UPDATE`/`RULE_DELETE`, with a field diff on update) and the response shows
`updatedBy`.

## 2. A template that extends a built-in

Concrete templates need an executor, either their own or inherited from an ancestor. `base-agent` has
none (it is abstract), so a child of it declares one. `code-review` has `LLM`, so a child of
`code-review` may leave `executor` empty. The same holds for `category`: leave it empty to inherit it.

```http
POST /api/v1/task-templates
{
  "name": "Write changelog entry",
  "scope": { "type": "GLOBAL" },
  "extendsKey": "base-llm",
  "category": "GENERAL",
  "executor": "LLM",
  "prompt": {
    "user": "Write a Keep-a-Changelog entry for this diff:\n\n{{artifacts.diff}}",
    "mode": "APPEND"
  },
  "skills": ["conventional-commits"],
  "consumes": ["diff"],
  "produces": ["changelog"],
  "config": { "maxInputChars": 100000 },
  "tags": ["docs"]
}
```

The answer carries the derived key, here `write-changelog-entry`; workflows refer to it.

What the validator checks on every save (`400` with all problems listed): key format, parent exists and
is not the template itself, executor present or inherited, `outputSchema` is well-formed JSON,
`confidenceField` needs an `outputSchema` somewhere in the chain, `skills` and `rules` keys exist (with project
shadowing), run input names are unique, no inheritance cycle and at most 8 templates in the chain. Updates bump
`version`.

Merge rules, root to leaf:

| Field | Rule |
|---|---|
| `executor`, `category` | nearest defined wins |
| `modelProfileId`, `outputSchema`, `confidenceField` | child wins when set |
| `prompt.system`, `prompt.user` | per field: child `APPEND` = parent + blank line + child; `REPLACE` = child only; a field the child leaves empty keeps the parent's |
| `skills` | union in order, minus the child's `removeSkills` |
| `rules` | union in order, minus the child's `removeRules`; automatic rules come first (section 5) |
| `config` | deep merge, child keys win, a `null` value deletes the key (so `null`s never reach the executor) |
| `produces`, `consumes`, `tags` | union |
| `runInputs` | union by name; a child's field replaces the parent's field with the same name |

## 3. A project-scoped override (shadowing)

A `PROJECT` template with the same key as a `GLOBAL` one replaces it for that project only, including
when the key is used as a parent by another template. To keep the global template and change only what differs,
give the project template the global key and the same key as `extendsKey`: it then extends the global one (this
is what **Customize for project** in the UI does).

```http
POST /api/v1/task-templates
{
  "key": "code-review",
  "name": "Code review (SHOP)",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "extendsKey": "code-review",
  "prompt": { "system": "This is a payment system: treat any change to money handling as MAJOR at least.", "mode": "APPEND" },
  "rules": ["shop-kotlin-conventions"]
}
```

Without the `extendsKey` the project template is a plain replacement, not an extension: it has to bring its own
executor, prompts, schema and so on.

`GET /api/v1/task-templates?projectId=<uuid>` lists global templates plus that project's own, both
entries for a shadowed key (the `scope` field tells them apart). `includeAbstract=false` hides abstract
ones. The designer palette (`TaskCatalog.palette`) shows one entry per key, the project's own winning, grouped by
`category`.

Skills and rules shadow the same way: a `PROJECT` skill or rule with the key of a global one is what that
project's templates receive, without changing any template.

## 4. Skills

A skill is knowledge or a procedure the agent loads when it needs it ("how to write an ADR"): a markdown document
following the Agent Skills convention, a frontmatter with `name` and `description` ("Use when ...", agents decide
by it whether to open the skill), then short, concrete, numbered steps. Something the agent must always follow
belongs in a rule (section 5).

```http
POST /api/v1/skills
{
  "name": "SQL migrations",
  "description": "Use when writing or reviewing a Flyway migration.",
  "scope": { "type": "GLOBAL" },
  "content": "---\nname: sql-migrations\ndescription: Use when writing or reviewing a Flyway migration.\n---\n\n# SQL migrations\n\n1. Append-only: never edit an applied version.\n2. ...",
  "tags": ["database"]
}
```

How executors deliver skills: `LLM` tasks get them appended to the system prompt in a `<skills>`
section (a single model call cannot open files); `SPEC_KIT` and `IMPLEMENT` tasks get them written to
`.claude/skills/<key>/SKILL.md` and `.opencode/skills/<key>/SKILL.md` in the runner workspace, which Claude Code
and OpenCode load on their own. A runner agent reads only the skills it is told to, so the prompts of the built-in
runner templates name the skills they use ("Apply the skill ..."); name another skill there when you attach it. A skill
referenced by any template cannot be deleted, and its key and scope cannot change (`409`); built-in skills cannot
be deleted.

## 5. Rules

A rule is a norm that is always in the agent's context ("the domain never imports Spring"): plain markdown, no
frontmatter, short, concrete, checkable sentences.

```http
POST /api/v1/rules
{
  "name": "Shop Kotlin conventions",
  "description": "Layers and naming of the shop backend.",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "automatic": true,
  "content": "1. The domain never imports Spring, JPA or Jackson.\n2. ...",
  "tags": ["kotlin"]
}
```

Which rules a step gets, in this order:

1. the **automatic** rules of the global scope (every step of every project), then those of the step's project
   (every step of that project);
2. the rules the template and its ancestors list in `rules`, unless already there.

`removeRules` on a template drops inherited and automatic rules for it and the templates based on it. A project
rule with the key of a global rule replaces it for that project; a non-automatic project copy of a global
automatic rule switches that rule off in the project. A key in `skills` or in a node's `addSkills` that names a
rule (as the built-in skills turned rules did, see section 8) counts as that rule, so stored workflows keep working.

How executors deliver rules (`RulesPrompt`, a `<rules>` block): `LLM` tasks get them in the system prompt, before
the skills; `IMPLEMENT` and `SPEC_KIT` tasks at the start of their prompt, right after the template's system part.
Nothing is written into the repository, so Claude Code and OpenCode see the same text. Permissions are the same as
for skills; a rule a template lists cannot be deleted, and its key and scope cannot change (`409`).

## 6. Reading the resolved preview

`GET /api/v1/task-templates/resolve/{key}?projectId=` returns exactly what the executor will receive,
plus fields the executor never sees (`POST` on the same path with a node's `TaskOverrides` shows what that step
gets):

```json
{
  "key": "implement-opencode",
  "executor": "IMPLEMENT",
  "category": "IMPLEMENTATION",
  "lineage": ["implement-opencode", "base-coding-agent", "base-agent"],
  "prompt": { "system": "You are an engineering agent ... {{project.constitution}} ...", "user": "Implement the work below ..." },
  "skills": [],
  "rules": [ { "key": "agent-ground-rules", "name": "Agent ground rules", "automatic": false }, { "key": "ask-dont-guess", "name": "Ask, don't guess", "automatic": false } ],
  "config": { "agent": "OPENCODE", "branchTemplate": "feature/{{issue.key | lower}}", "push": true, "mergeRequest": true, "mergeRequestTitleTemplate": "{{issue.key}}: {{issue.summary}}", "mergeRequestDraft": false },
  "produces": ["diff", "mr"],
  "consumes": [],
  "ports": ["success", "failure"],
  "abstract": false,
  "check": { "ok": true, "problems": [] }
}
```

- `lineage` is leaf first; the UI draws it as the inheritance chain.
- `rules` lists the automatic rules first (`automatic: true`), then the listed ones.
- `check.problems` lists every skill and rule the lineage names that does not exist. The preview still returns the
  merged task, but the `TaskCatalog` port refuses to execute it.
- `abstract: true` means the same: previewable, not executable.
- `ports` is always `success`, `failure`.
- `executor` is empty only when no template in the chain sets one (an abstract root).

The endpoint answers `404` for an unknown key and `400` for a cycle or a missing parent.

## 7. How workflow nodes reference templates

A `TASK` node in a workflow holds a `templateKey` and optional `TaskOverrides`, never a prompt. At run
time the Projects context calls `TaskCatalog.resolve(templateKey, projectId, overrides)`; the overrides
are applied last, after the whole chain:

| Override | Effect |
|---|---|
| `modelProfileId` | replaces the resolved model |
| `systemPromptAppend` | appended to the resolved system prompt |
| `promptReplace`, then `promptAppend` | replace and/or extend the user prompt |
| `addSkills`, `removeSkills` | adjust the skill list (a key naming a rule adds that rule) |
| `config` | deep-merged with the same null-deletes rule |

The designer validates a node's `templateKey` against the catalog (error when unknown, warning when
abstract); every task node has the ports `success` and `failure`. Because nodes reference keys,
editing a template changes every workflow using it on its next run; shadow it per project when only
one project should change.

## 8. SDLC templates, skills and rules (ADR 0019)

Every agent inherits two rules through `base-agent` (and `agent-task`): `agent-ground-rules` (evidence, scope and
safety: re-read a file before claiming anything about it, paths as evidence, a search hit outside the task is not
a task, narrowest edit, report contradictions) and `ask-dont-guess` (source hierarchy; one open-question marker
per context, `[NEEDS CLARIFICATION: ...]` in Spec Kit specs and `[?] question` elsewhere, never an invented owner
or date; TODO/TBD with a reason; append, do not rewrite; never touch status fields). Drop them on a template with
`removeRules` if a task must not see them. Until ADR 0055 they were skills; `V303__rules_and_categories.sql` moved
them, together with the example rules `kotlin-hexagonal` and `angular-ng-zorro`, into rules in every scope and
moved their keys from `skills`/`removeSkills` to `rules`/`removeRules`. The two stack rules describe the Back
Office code base: copy them into a project rule for your stack and make that one automatic.

| Template | Use it for | Output |
|---|---|---|
| `impact-analysis` | a change request arrives as an issue | JSON with description, business justification, impact on artefacts/components/new stories/specification, estimate, options, recommendation, open questions, `confidence` |
| `write-user-story` | turn the issue into a story with falsifiable acceptance criteria ("System: 1. displays ..., 2. validates ...") | artefact `user-story` (the markdown of the schema field `user-story`; `openQuestions` and `confidence` in the answer) |
| `write-use-case` | expand an accepted story into flows A, A1.., rules RB-xx, postconditions, permissions | artefact `use-case` |
| `write-adr` | draft a decision record (status Proposed, at least two options) | artefact `adr` (markdown) |
| `plan-task-list` | break the plan / story into tasks for an autonomous agent | artefact `tasks`: `[{title, description, verification, test_command, completed: false}]`, always ending with "reformat, lint, and test" |
| `implement-task-list` | execute that list with TDD, stop on the first red `test_command` (the runner commits once; the work log ends with `RESULT: GREEN` or `RESULT: STOPPED at <task>`, which `verifyCommand` checks) | `diff`, `mr`, artefact `work-log` with a "Noticed out of scope" section |
| `review-against-spec` | review the diff against story, spec and task list | `review` with per-requirement coverage (MET/PARTIAL/MISSING) plus findings |

Skills used by these templates: `definition-of-ready-done`, `user-story-format`, `use-case-format`,
`adr-format`, `build-guardrails`. A human gate after `write-user-story` or `plan-task-list` with a DoR
checklist (ADR 0017) is the usual way to stop a run on low `confidence`. The "Change request (delta)"
workflow template chains `fetch-issue` → `impact-analysis` → `write-user-story` → `plan-task-list` (which
reads the approved impact analysis) → `implement-task-list`; after the AI review the rework prompt carries
the `review` artefact.

## Where to look when it fails

| Symptom | Where to look |
|---|---|
| `403` on a library call | the caller lacks the permission of the table in section 1 ("Who may do what"); the audit log has an `ACCESS_DENIED` row with the permission |
| a project member does not see global templates | they hold `project.view` on the project but not `library.view` (normally granted by the SYSTEM `member` role) |
| a built-in on an old installation does not get the shipped changes | it was edited (version > 1, or saved by a person), so the seed leaves it alone; compare it with a fresh installation and copy the changes by hand |
| `400` on save with `executor: required on a concrete template` | the parent chain has no executor: set one on the template or mark it `abstract` |
| `400` with `extendsKey: inheritance cycle ...` | the message prints the chain; break it at the template you are saving |
| `400` with `outputSchema: not well-formed JSON at offset N` | the schema is checked as raw JSON; escape quotes when sending it as a string |
| `400` with `skills: skill X does not exist` or `rules: rule X does not exist` | the key is wrong or names the other kind of entry (for example a former skill that is now a rule): move it between `skills` and `rules` |
| `409` on delete | built-in template, or a template, skill or rule still referenced; the message names the referrers |
| resolve shows the global template although a project one exists | the `projectId` query parameter is missing, or the project template's key differs |
| `check.problems` on a step you did not change | a skill the step adds with `addSkills` was deleted (only references from templates block a delete) |
| a rule does not reach a step | the step's template (or an ancestor) drops it with `removeRules`, or the project has a non-automatic copy of a global automatic rule |
| a skill or rule edit does not show up in the runner | the run started before the edit; skills and rules are snapshotted at resolution time |
| the built-in library is missing after a fresh deploy | `backoffice.tasks.seed-builtin` is `false`, or the startup log lacks `seeded task library ...` because Flyway `V300__task_library.sql` failed; check the `task_template` table |
| ArchUnit fails after touching `tasks-core` | the core module may only import `pl.mos.backoffice.tasks.*`, `pl.mos.backoffice.shared.*` and the standard library |
