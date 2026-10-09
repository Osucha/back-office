# Task Library: templates, interfaces and skills

## When to use this guide

You want to change what an agent does or how it is prompted without editing a workflow: add a new kind
of task, specialise a built-in one for a single project, attach a reusable skill, or understand why the
resolved prompt an executor receives looks the way it does. The concepts and merge rules are specified in
`docs/architecture/07-task-library.md` (ADR 0015); this guide walks through the API
(`/api/v1/task-interfaces`, `/api/v1/task-templates`, `/api/v1/skills`) and the Library UI.

## 1. Concepts in one table

| Concept | Analogy | You use it to |
|---|---|---|
| Task interface | `interface` | promise a contract: which artefacts/state a task consumes, which it produces, which output ports its node exposes |
| Task template | `class` | define a task: executor, prompts, skills, config, `extends` one parent, `implements` interfaces |
| Skill | mixin | reuse a markdown instruction block (`SKILL.md` convention) across templates |
| Resolved task | instance | the merged result an executor receives: template + ancestors + project shadowing + node overrides |

Keys match `^[a-z][a-z0-9-]{1,63}$`. Interfaces are unique by key; templates and skills are unique by
`(key, scope)` where scope is `GLOBAL` or `PROJECT(projectId)`.

The built-in library (seeded on startup, `backoffice.tasks.seed-builtin=true`) contains the abstract
`base-agent`, the Spec Kit phases, `implement-opencode` / `implement-claude-code`, `clarify-spec`,
`code-review`, `analyse-issue` and the Jira tasks, the interfaces `specifier`, `clarifier`, `planner`,
`task-planner`, `implementer`, `reviewer`, `analyst`, `requirement-writer`, `change-analyst`, the SDLC
templates of section 8 and twelve skills. Seeding is idempotent by key:
edit built-ins freely, an edited entry is never overwritten (only a never-edited built-in receives later
shipped skills and config); delete a non-built-in copy and restart to get the
shipped version back. Built-in templates cannot be deleted, only shadowed.

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
| see templates, skills, the resolved preview | `library.view` (role `member` has it) | `project.view` or `library.project.manage` on that project |
| create, edit, delete templates and skills | `library.manage` (role `library-curator`) | `library.project.manage` on that project |
| create, edit, delete interfaces | `library.manage` (interfaces are always global) | — |

`GET /api/v1/task-templates?projectId=P` returns the global entries when you hold `library.view` plus
P's entries when you are a member of P; without either you get `403`. Moving an entry between scopes
needs the write permission of both. Every change is recorded in the audit log (`TEMPLATE_*`,
`INTERFACE_*`, `SKILL_*`, with a field diff on update) and the response shows `updatedBy`.

## 2. Creating an interface

An interface says what a workflow may rely on. Inputs of kind `ARTIFACT` or `STATE` that are `required`
must appear in the implementing template's `consumes`; every output must appear in `produces`.
`INPUT` slots describe workflow inputs and are documentation only.

```http
POST /api/v1/task-interfaces
{
  "key": "changelog-writer",
  "name": "Changelog writer",
  "description": "Turns a diff into a changelog entry.",
  "inputs":  [ { "name": "diff", "kind": "ARTIFACT", "required": true } ],
  "outputs": [ { "name": "changelog", "kind": "ARTIFACT" } ],
  "ports": ["success", "failure"]
}
```

`GET /api/v1/task-interfaces/changelog-writer` returns the definition plus `implementedBy`, the keys of
the templates that declare it. Deleting an interface that is still implemented answers `409`.

## 3. A template that extends a built-in

Concrete templates need an executor, either their own or inherited from an ancestor. `base-agent` has
none (it is abstract), so a child of it declares one. `code-review` has `LLM`, so a child of
`code-review` may leave `executor` empty.

```http
POST /api/v1/task-templates
{
  "key": "write-changelog",
  "name": "Write changelog entry",
  "scope": { "type": "GLOBAL" },
  "extendsKey": "base-agent",
  "implements": ["changelog-writer"],
  "executor": "LLM",
  "prompt": {
    "user": "Write a Keep-a-Changelog entry for this diff:\n\n{{artifacts.diff}}",
    "mode": "APPEND"
  },
  "skills": ["conventional-commits"],
  "consumes": ["diff"],
  "produces": ["changelog"],
  "config": { "maxChunkLines": 800 },
  "tags": ["docs"]
}
```

What the validator checks on every save (`400` with all problems listed): key format, parent exists and
is not the template itself, executor present or inherited, `outputSchema` is well-formed JSON,
`confidenceField` needs an `outputSchema` somewhere in the chain, `implements` keys and `skills` keys
exist (skills with project shadowing), no inheritance cycle and at most 8 templates in the chain.
Updates bump `version`.

Merge rules, root to leaf:

| Field | Rule |
|---|---|
| `executor` | nearest defined wins |
| `modelProfileId`, `outputSchema`, `confidenceField` | child wins when set |
| `prompt.system`, `prompt.user` | per field: child `APPEND` = parent + blank line + child; `REPLACE` = child only; a field the child leaves empty keeps the parent's |
| `skills` | union in order, minus the child's `removeSkills` |
| `config` | deep merge, child keys win, a `null` value deletes the key (so `null`s never reach the executor) |
| `produces`, `consumes`, `implements`, `tags` | union |

## 4. A project-scoped override (shadowing)

A `PROJECT` template with the same key as a `GLOBAL` one replaces it for that project only, including
when the key is used as a parent by another template. Note that shadowing is replacement, not
inheritance: to keep the built-in prompt, extend the same parent and repeat what you need, or extend the
global template under a new key instead.

```http
POST /api/v1/task-templates
{
  "key": "code-review",
  "name": "Code review (SHOP)",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "extendsKey": "base-agent",
  "implements": ["reviewer"],
  "executor": "LLM",
  "prompt": { "system": "This is a payment system: treat any change to money handling as MAJOR at least." },
  "skills": ["security-review-checklist", "kotlin-hexagonal"],
  "outputSchema": "{ ...same schema as the built-in... }",
  "consumes": ["diff"],
  "produces": ["review"]
}
```

`GET /api/v1/task-templates?projectId=<uuid>` lists global templates plus that project's own, both
entries for a shadowed key (the `scope` field tells them apart). `includeAbstract=false` hides abstract
ones. The designer palette (`TaskCatalog.palette`) shows one entry per key, the project's own winning.

Skills shadow the same way: a `PROJECT` skill with the key of a global one is what that project's
templates receive, without changing any template.

## 5. Skills

A skill is a markdown document following the Agent Skills convention: a frontmatter with `name` and
`description`, then concrete, numbered rules. Keep them short (10 to 30 rules); they are prepended to
every prompt that lists them.

```http
POST /api/v1/skills
{
  "key": "sql-migrations",
  "name": "SQL migrations",
  "description": "How Flyway migrations are written in our projects.",
  "scope": { "type": "GLOBAL" },
  "content": "---\nname: sql-migrations\ndescription: How Flyway migrations are written in our projects.\n---\n\n# SQL migrations\n\n1. Append-only: never edit an applied version.\n2. ...",
  "tags": ["database"]
}
```

How executors deliver skills: `LLM` tasks get them appended to the system prompt in a `<skills>`
section; `SPEC_KIT` and `IMPLEMENT` tasks get them written to `.claude/skills/<key>/SKILL.md` and
`.opencode/skills/<key>/SKILL.md` in the runner workspace, which Claude Code and OpenCode load
natively. A skill referenced by any template cannot be deleted or renamed (`409`).

## 6. Reading the resolved preview

`GET /api/v1/task-templates/resolve/{key}?projectId=` returns exactly what the executor will receive,
plus two fields the executor never sees:

```json
{
  "key": "implement-claude-code",
  "executor": "IMPLEMENT",
  "lineage": ["implement-claude-code", "implement-opencode", "base-agent"],
  "interfaces": ["implementer"],
  "prompt": { "system": "You are an engineering agent ... {{project.constitution}} ...", "user": "Implement the work below ..." },
  "skills": [ { "key": "conventional-commits", "name": "Conventional commits" } ],
  "config": { "agent": "CLAUDE_CODE", "branchTemplate": "feature/{{issue.key | lower}}", "push": true, "mergeRequest": { "create": true, "titleTemplate": "{{issue.key}}: {{issue.summary}}", "draft": false } },
  "produces": ["diff", "mr"],
  "consumes": [],
  "ports": ["success", "failure"],
  "abstract": false,
  "interfaceCheck": { "ok": true, "problems": [] }
}
```

- `lineage` is leaf first; the UI draws it as the inheritance chain.
- `interfaceCheck.problems` lists every output an implemented interface expects that is missing from
  `produces`, every required input missing from `consumes`, unknown interfaces and unknown skills.
  The preview still returns the merged task, but the `TaskCatalog` port refuses to execute it.
- `abstract: true` means the same: previewable, not executable.
- `ports` is `success`, `failure` plus every port of every interface in the lineage.
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
| `addSkills`, `removeSkills` | adjust the skill list |
| `config` | deep-merged with the same null-deletes rule |

The designer validates a node's `templateKey` against the catalog (error when unknown, warning when
abstract) and takes the node's output ports from the resolved `ports`. Because nodes reference keys,
editing a template changes every workflow using it on its next run; shadow it per project when only
one project should change.

## 8. SDLC templates and skills (ADR 0019)

Every agent inherits two skills through `base-agent`: `agent-ground-rules` (propose changes as
before/after, change only what was asked, a search hit outside the task is not a task, narrowest edit,
re-read a file before claiming anything about it, stop and report contradictions) and `ask-dont-guess`
(source hierarchy; `[?] question (owner, by date)` markers instead of guesses; TODO/TBD with a reason;
append, do not rewrite; never touch status fields). Remove them on a template with
`removeSkills` if a task must not see them.

| Template | Use it for | Output |
|---|---|---|
| `impact-analysis` | a change request arrives as an issue | JSON with description, business justification, impact on artefacts/components/new stories/specification, estimate, options, recommendation, open questions, `confidence` |
| `write-user-story` | turn the issue into a story with falsifiable acceptance criteria ("System: 1. displays ..., 2. validates ...") | artefact `user-story` (JSON: `userStory` markdown, `openQuestions`, `confidence`) |
| `write-use-case` | expand an accepted story into flows A, A1.., rules RB-xx, postconditions, permissions | artefact `use-case` |
| `write-adr` | draft a decision record (status Proposed, at least two options) | artefact `adr` (markdown) |
| `plan-task-list` | break the plan / story into tasks for an autonomous agent | artefact `tasks`: `[{title, description, verification, test_command, completed: false}]`, always ending with "reformat, lint, and test" |
| `implement-task-list` | execute that list with TDD, one commit per task, stop on the first red `test_command` | `diff`, `mr`, `work-log.md` with a "Noticed out of scope" section |
| `review-against-spec` | review the diff against story, spec and task list | `review` with per-requirement coverage (MET/PARTIAL/MISSING) plus findings |

Skills used by these templates: `definition-of-ready-done`, `user-story-format`, `use-case-format`,
`adr-format`, `build-guardrails`. A human gate after `write-user-story` or `plan-task-list` with a DoR
checklist (ADR 0017) is the usual way to stop a run on low `confidence`. The "Change request (delta)"
workflow template chains `impact-analysis` → `write-user-story` → `plan-task-list` →
`implement-task-list`.

## Where to look when it fails

| Symptom | Where to look |
|---|---|
| `403` on a library call | the caller lacks the permission of the table in section 1 ("Who may do what"); the audit log has an `ACCESS_DENIED` row with the permission |
| a project member does not see global templates | they hold `project.view` on the project but not `library.view` (normally granted by the SYSTEM `member` role) |
| `base-agent` on an old installation lacks the ground rules | it was edited (version > 1), so the seed leaves it alone; add `agent-ground-rules` and `ask-dont-guess` to its `skills` by hand |
| `400` on save with `executor: required on a concrete template` | the parent chain has no executor: set one on the template or mark it `abstract` |
| `400` with `extendsKey: inheritance cycle ...` | the message prints the chain; break it at the template you are saving |
| `400` with `outputSchema: not well-formed JSON at offset N` | the schema is checked as raw JSON; escape quotes when sending it as a string |
| `409` on delete | built-in template, or an interface/skill/template still referenced; the message names the referrers |
| resolve shows the global template although a project one exists | the `projectId` query parameter is missing, or the project template's key differs |
| `interfaceCheck.problems` on a template you did not change | an interface was edited (new output or required input); update `produces` / `consumes` on the implementers listed in its `implementedBy` |
| a skill edit does not show up in the runner | the run started before the edit; skills are snapshotted at resolution time |
| the built-in library is missing after a fresh deploy | `backoffice.tasks.seed-builtin` is `false`, or the startup log lacks `seeded task library ...` because Flyway `V300__task_library.sql` failed; check the `task_template` table |
| ArchUnit fails after touching `tasks-core` | the core module may only import `pl.mos.backoffice.tasks.*`, `pl.mos.backoffice.shared.*` and the standard library |
