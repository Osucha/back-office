# 0054 — An approachable task library; CI checks that depend on time only block the way to main

- Status: accepted
- Date: 2026-10-25
- Deciders: owner
- Related: ADR 0016 (task library), 0034 (step repositories), 0052 (self-development loop), 0053 (run inputs);
  docs/architecture/07-task-library.md, docs/manual/task-library.md, docs/manual/workflows.md, .gitlab-ci.yml

## Context

The owner finds the task library hard to find one's way in. The editor of a template shows everything at once:
type, Extends, Implements, executor, Abstract, scope, model, prompts, skills, Produces/Consumes, the confidence
field, tags, configuration as raw JSON, output schema, sandbox and run inputs. Executors appear as enum values
(`IMPLEMENT` is in fact "a coding agent in a sandbox", also for analysis and workspace preparation). The built-in
library has 26 templates, two of them unused duplicates (`implement-claude-code` = `implement-opencode` with
`agent: CLAUDE_CODE`; `analyse-issue` overlaps `impact-analysis`). What a workflow step finally gets (template,
inheritance and the step's own overrides) is visible nowhere in one piece.

Separately, with work on `develop` and merge requests into `main` gated by the pipeline (ADR 0052), the CVE scan
blocked an unrelated change (run 49a8c59f had to raise dompurify): new advisories appear over time, independent of
the change.

## Decision

1. **Two levels in the template editor.** Basic: name, description, Based on, executor, scope, model, prompt,
   skills, run inputs and the configuration. Advanced (collapsed): Abstract, Implements, Produces/Consumes, confidence
   field, tags, output schema and sandbox, each with a one-line explanation in plain words.
2. **Executors by what they do**: "Model answer", "Coding agent", "Spec Kit step", "Jira: read issue / comment /
   change status", "Start workflows", "Project digest"; the enum value stays a small hint.
3. **Configuration as a form.** `GET /workflows/task-executors` returns every executor with the JSON Schema of its
   config keys (the schema the designer already uses for a step); the library editor renders the form from it and
   keeps a raw JSON view for keys outside the schema.
4. **What a step gets.** `POST /task-templates/resolve/{key}` resolves the template a project sees with a step's
   overrides; the designer shows the effective model, system and user prompt, skills, configuration and run inputs
   next to the step.
5. **Deprecated built-ins instead of deletions.** A built-in tagged `deprecated` keeps working in the steps that use
   it but is no longer offered (palette, MCP palette, the list hides it unless asked). Now `implement-claude-code`
   (use `implement-opencode`, `implement-task-list` or `agent-task` with the agent Claude Code) and `analyse-issue`
   (use `impact-analysis`).
6. **CI: time-dependent checks block only the way to main.** `security:dependencies` (Trivy) and
   `security:suppressions` report without blocking on `develop` and merge requests into it; merge requests into
   `main`, `main` and tags must pass them. Tests, lint, builds and the secret scan block everywhere: they depend on
   the change. The branches are named in `.gitlab-ci.yml` (`RELEASE_BRANCH`, `INTEGRATION_BRANCH`), so GitLab's
   default branch changes nothing.

## Consequences

- The data model and the API of templates are unchanged; existing workflows keep their templates.
- A CVE found on `develop` is visible as a warning in its pipelines and must be fixed before the merge request into
  `main` can merge.
- Interfaces stay; they are an advanced tool for library authors, not something a workflow author has to touch.
