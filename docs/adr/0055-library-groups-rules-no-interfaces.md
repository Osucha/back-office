# 0055 — Library in three groups, rules next to skills, categories instead of interfaces, keys people never type

- Status: accepted
- Date: 2026-11-05
- Deciders: owner
- Related: ADR 0015 (task library: templates, interfaces, skills), 0016, 0053 (run inputs), 0054 (approachable task
  library); docs/architecture/07-task-library.md, docs/manual/task-library.md, docs/api/openapi.yaml

## Context

ADR 0054 made the template editor approachable, but the library itself still had five flat pages (templates,
interfaces, skills, sources, workflows) and three concepts that did not earn their place:

- **Interfaces** promised inputs, outputs and ports. In the code they only (1) re-checked that a template lists in
  `consumes`/`produces` what it already lists there, (2) could add ports, which no built-in interface did (all nine
  had `success`/`failure`), and (3) labelled templates in the palette. No workflow ever referred to an interface: a
  step always names a concrete template, and projects specialise templates through `extends` and step overrides, not
  by picking an implementation of an interface. An abstraction without polymorphic use.
- **Skills** mixed two different things: knowledge or procedures an agent opens when it needs them ("how to write an
  ADR"; the Agent Skills idea) and norms that must always apply ("the domain never imports Spring", the ground rules
  of every agent). Coding agents load skills on demand, so a norm stored as a skill may never be read.
- **Keys**: every template and skill asked its author for a slug. People do not need it; workflows, MCP and the API
  do.

## Decision

1. **Library in three groups**: **Tasks** (templates, skills, rules), **Workflows** (the workflow library), **Sources**.
2. **Rules** are a new library entry next to skills (global or project scope, built in or not, same permissions as
   skills). A rule is always in the agent's context: model answer steps get the rules in the system prompt before the
   skills, coding agents and Spec Kit steps at the start of their prompt right after the template's system part
   (nothing is written into the repository, both agents see the same text). A rule applies to the templates that list
   it (`rules`, inherited and unioned like skills) or, when **automatic**, to every step of its scope (global: every
   project, project: every step of that project). `removeRules` drops inherited and automatic rules. A project rule
   with a global rule's key replaces it in place; a non-automatic project copy switches a global automatic rule off for
   that project. `ResolvedTask.rules` carries them, automatic ones first; the resolved preview shows them.
3. Built-in skills that were norms become rules: `agent-ground-rules` and `ask-dont-guess` (every agent through
   `base-agent` and `agent-task`), `kotlin-hexagonal` and `angular-ng-zorro` (examples of project rules). V303 moves
   them in existing databases, in every scope, and moves their keys from templates' `skills`/`removeSkills` to
   `rules`/`removeRules`. A key in a step's `addSkills` that names a rule counts as that rule, so stored workflows keep
   working.
4. **Interfaces are removed** (model, REST `/task-interfaces`, `implements`, the `task_interface` table, the resolver's
   interface check). Every task node has the ports `success` and `failure`. What interfaces really gave, a grouping,
   becomes a **category** of the template (`TemplateCategory`: analysis, specification, planning, implementation,
   review, Jira, operations, general), inherited child-wins; the library list and the designer palette group by it.
   V303 derives it from the first implemented interface, else from the executor. The review guard recognises review
   steps by `produces review` or category `REVIEW` (instead of the interface `reviewer`).
5. **Keys are derived, not typed.** A new template, skill or rule without a key gets one from its name (diacritics
   dropped, `-` between words, a `task-`/`skill-`/`rule-` prefix when the name starts with a digit, `-2`, `-3`, ...
   when taken; template keys are unique across scopes so a new template never shadows anything by accident). An
   update without a key keeps it; renaming changes the name only. To customise a global template for a project the UI
   creates the project template with the global key and `extendsKey` (**Customize for project**). The API still
   accepts explicit keys.

## Alternatives considered

- **Keep interfaces, hide them in Advanced** (ADR 0054). Keeps a concept nobody needs and the cost of keeping
  `consumes`/`produces` in sync twice. Rejected; if a workflow ever needs "any reviewer, the project picks which",
  that is a new decision with real polymorphism.
- **Rules into `CLAUDE.md` / `AGENTS.md` in the workspace.** Would need changes in the runner, risks ending up in the
  agent's commit and differs between agents. The prompt carries them reliably for every executor. Rejected for now.
- **Mark some skills "always on" instead of a new entry.** The difference is exactly the one users need to see when
  writing; one page per concept is easier to explain. Rejected.

## Consequences

- OpenAPI: `/task-interfaces` and the `TaskInterface`, `TaskInterfaceInput`, `Slot` schemas are gone; `/rules` is new;
  `key` is optional on template, skill and rule input; `ResolvedTask.interfaceCheck` is `check`, `interfaces` is
  `category`, plus `rules`; `PaletteTask.interfaces` is `category`; MCP returns `category` too.
- Every built-in template nobody edited is upgraded on the next start (the checksum now covers category and rules).
  Edited ones keep their content and get their category and rules from V303.
- Steps of existing workflows keep their template keys; prompts of coding agents grow by the rules they get.
- Next: the concept of the agent in a workflow (the owner wants to revisit it on top of this structure).
