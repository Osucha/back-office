# 0019 - Delivery conventions adopted from a reference SDLC framework

Status: accepted - 2026-09-27

## Context
A reference project's documentation repository contains a mature, AI-oriented SDLC framework: fixed artefact
templates with IDs, binary Definition of Ready/Done per artefact, RACI with exactly one accountable role,
"do not guess, ask" rules for agents, an edit protocol, a task-list format with `verification` and
`test_command` per task, and build guardrails with negative tests. Back Office executes such processes, so
these conventions belong in its task library and workflows.

## Decision
- **Task Library seed** gains templates and skills derived from the framework: `write-user-story`,
  `write-use-case`, `write-adr`, `impact-analysis` (change request), `plan-task-list` (produces a task list
  with `title`, `description`, `verification`, `test_command` per task and a mandatory final
  "reformat, lint, and test" task), `implement-task-list` (TDD, work log, "noticed out of scope" section,
  a red verification step stops the run), `review-against-spec`; skills `agent-ground-rules` (edit protocol,
  change only what was asked, re-read before claiming), `ask-dont-guess` (source hierarchy, `[?]` markers
  with owner and date, append not rewrite), `definition-of-ready-done`, `user-story-format`,
  `use-case-format`, `adr-format`, `build-guardrails`.
- **Gates** get DoR/DoD checklists and approver rules (ADR 0017).
- **Workflow template** "Change request (delta)" alongside "Issue to reviewed MR".
- **Build guardrails**: rule IDs `GR-A*` (architecture), `GR-S*` (security), `GR-Q*` (quality), each
  enforced by a build step and documented with the negative test that proves it fails.

## Consequences
- Seeded content is in English (the platform language); Polish originals are not copied verbatim.
- The seed is idempotent by key and can be shadowed per project like any built-in.
