# 0015 - Task Library: templates with inheritance, interfaces and Agent Skills as a bounded context

Status: accepted - 2026-09-26

## Context
Agent tasks differ by kind of work and by project (prompts, skills, models, verification commands). The
owner wants them managed like code: extensible (`extends`) and contract-driven (`implements`) so a
workflow can rely on "a Specifier" without caring which prompt variant runs.

## Decision
- New bounded context **Task Library** (`tasks-core`, `tasks-adapters`) owning `TaskTemplate`,
  `TaskInterface` and `Skill`. Projects consume it only through the `TaskCatalog` port in shared-kernel,
  which returns a `ResolvedTask` (all inheritance merged, overrides applied).
- **Template** = class: `key`, `abstract`, `extends` (single parent), `implements` (many interfaces),
  `executor`, prompt (`system`/`user`, `APPEND` to or `REPLACE` the parent), `skills`, `config`, `modelProfileId`,
  `produces`/`consumes`, `outputSchema`, `confidenceField`. Merge rules: config maps deep-merge child-wins,
  prompts append or replace, skills union minus `removeSkills`, scalar fields child-wins, executor inherited.
- **Scope**: `GLOBAL` or `PROJECT`. A project template with the same key shadows the global one, so a
  project can specialise "code-review" without touching the library.
- **Interface** = contract: required inputs (state/artefact names), produced outputs, exposed ports.
  Resolution validates that a concrete template satisfies every interface it (or an ancestor) implements.
- **Skill** = reusable markdown following the Agent Skills convention (`SKILL.md`). In-process LLM tasks
  get skills appended to the system prompt; runner tasks get them written to `.claude/skills/<key>/SKILL.md`
  and `.opencode/skills/<key>/SKILL.md`, which Claude Code and OpenCode load natively.
- Seeded library: abstract `base-agent`, Spec Kit tasks, implementers (OpenCode, Claude Code), clarifier,
  code review, Jira tasks; interfaces `Specifier`, `Planner`, `TaskPlanner`, `Implementer`, `Reviewer`,
  `Clarifier`, `Analyst`; a handful of skills. All editable; built-ins can be shadowed, not deleted.

## Alternatives
- Prompts inline in workflow nodes: no reuse, no project specialisation, drift between workflows.
- Prompt files in each repository only: invisible to the platform and to benchmarks.

## Consequences
- Workflow nodes hold a `templateKey` and overrides, not prompts; the designer palette groups templates
  by interface.
- Benchmarks can later evaluate a template across models by referencing its key (not in this iteration).
