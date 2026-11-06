# 0058 — Templates with parameters: ready blocks, presets, flat built-ins

- Status: accepted
- Date: 2026-11-06
- Deciders: owner
- Related: ADR 0015 (task library), 0053 (run inputs), 0056 (stage 3 of its direction), 0057 (the agent is the environment)

## Context

A template was sometimes a ready building block ("write a user story") and sometimes a prompt to complete ("LLM task":
replace the user prompt in the designer). The only ways to complete one were overriding the prompt on the step or
extending the template, and the built-ins used deep `extends` chains mostly to share the system prompt and rules, which
are the agent's job now (ADR 0057). The owner: "templates should sometimes be ready blocks and sometimes prompts with
placeholders that leave room for configuration".

## Decision

1. **Parameters.** A template declares typed parameters: `name`, `label`, `type` (`TEXT`, `MULTILINE`, `NUMBER`,
   `BOOLEAN`, `CHOICE` with `options`), `required`, `default`, `help`. Prompts and text config read them as
   `{{params.<name>}}`. Declarations are inherited, child wins by name.
2. **Values.** A step sets `overrides.parameters`; a template may set `parameterValues` for the parameters of its lineage.
   The value is the step's, else the nearest template's, else the default. Unknown names, wrong types and a required
   parameter without a value are resolution problems (the step cannot run; the preview still works).
3. **Ready block and preset.** A template is a **ready block** when every required parameter has a value without the
   step; the palette and the library mark the others "needs parameters". A **preset** is a template that extends another
   and only sets `parameterValues` (the editor creates one from any template).
4. **Flat built-ins.** Built-in templates no longer extend `base-*` (ADR 0057 moved their content to the agents); only
   `implement-task-list` and the deprecated `implement-claude-code` extend `implement-opencode`. `llm-task` and
   `agent-task` take their task as the parameter `task` (default: the former placeholder task), so the designer fills a
   field instead of replacing the prompt. Steps that replaced the prompt keep working (`promptReplace` still wins).
5. `extends` stays (presets, project customisations of a global template). Depth stays capped at 8.

## Alternatives considered

- **Drop `extends`.** Breaks stored templates and project customisations for little gain once the built-ins are flat.
  Rejected.
- **Parameters as run inputs.** Run inputs are asked when a run starts (ADR 0053), parameters are fixed when the
  workflow is designed. Different moments, different people. Kept apart.

## Consequences

- OpenAPI: `TemplateParameter`; `TaskTemplateInput.parameters` and `parameterValues`; `TaskOverrides.parameters`;
  `ResolvedTask.parameters` (definition, value, source); `PaletteTask.needsParameters`.
- The template model of prompts has `params`.
