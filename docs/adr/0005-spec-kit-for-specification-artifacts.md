# 0005 - GitHub Spec Kit as the specification workflow and artefact format

Status: accepted - 2026-09-26; Spec Kit made optional per project by ADR 0036 (2026-10-09)

## Context
Pipelines need a "create and validate documentation/idea" phase whose output later drives
implementation. A bespoke document format would need its own prompts, validators and agent support.

## Decision
Use Spec Kit unchanged: `constitution.md` per project (stored in `Project.constitution` and written into
the repository), and `specs/<NNN>/spec.md`, `plan.md`, `tasks.md` produced by the `/speckit.*` commands
executed by the coding agent inside the runner. Back Office stores these files as run artefacts and shows
them at gates.

## Alternatives
- Own templates (PRD + ADR + task list): more control, no ecosystem; every agent would need custom prompting.
- Kiro-style specs or BMAD: less widely adopted; Spec Kit is agent-agnostic and supports OpenCode and Claude Code.

## Consequences
- Runner image includes `specify-cli` (installed via `uv`).
- Validation of specs is a separate `LLM` step with a confidence score feeding an `ON_LOW_CONFIDENCE` gate.
