# 0062 — One instruction, parameters with sources, session and directory on the agent, sandbox on the project

- Status: accepted
- Date: 2026-11-12
- Deciders: owner
- Related: ADR 0053 (run inputs), 0058 (parameters), 0060 (run directory, sessions), 0061 (template is only the work);
  followed by 0063 (delivery as an action, sources, no Spec Kit) and 0064 (connectors)

## Context

After ADR 0061 the template still carried things that are not "the work" or that confuse it:

- **System prompt and user prompt.** In model APIs the system prompt is the stable frame (who you are, which rules),
  the user message is the task with its data. The frame is the agent's now (environment document, ADR 0057); coding
  agents get both fields glued into one text anyway. What was left in template system prompts were role sentences that
  duplicate the agent, plus data sections every template had to remember (`{{runInputText}}`, `{{feedback}}`, ...).
- **Parameters and run inputs** were both declared on templates. The difference is *when* and *who*: parameters are
  set by the workflow designer, run inputs by whoever starts a run. A template cannot know which. GitHub Actions has
  it right: an action declares `inputs`, the workflow declares what it asks at start, the step binds one to the other.
- **Session and separate directory** were step settings, but a session is the agent's conversation and "does not see
  the run directory" is part of what an agent may access.
- **Sandbox policy** could be set on templates and steps; it is a property of the project (ceiling: platform, pool).

## Decision

1. **One instruction.** A template has `instructions` (what to do and the acceptance criteria; placeholders allowed)
   and `instructionsMode` (APPEND / REPLACE over the parent, for presets). The platform composes the call:
   - system: the agent's environment document (ADR 0057), then the answer format (output schema, questions);
   - message: the instructions, then the **standard data sections**, each only when it has content and the
     instructions do not place it themselves with its placeholder: the issue (`{{issue.*}}`), the artefacts the task
     consumes (`{{artifacts.<name>}}`), the parameter values bound at run start that the instructions do not use,
     the reviewer's feedback (`{{feedback}}`) and the owner's answers (`{{answers}}`). Untrusted values are data blocks
     as before (ADR 0029).
   Coding agents get the same message as their prompt. Step overrides: `instructionsAppend` (notes for this step) and
   `instructionsReplace`; the former `systemPromptAppend` / `promptAppend` read as append, `promptReplace` as replace.
2. **Parameters with sources; no run inputs on templates.** A template declares only parameters (types TEXT,
   MULTILINE, NUMBER, BOOLEAN, CHOICE and ISSUE_KEY). A step binds each parameter (`overrides.parameterBindings`):
   `VALUE` (a fixed value; the former `overrides.parameters`), `START` (asked in the run start form; label, type and
   required come from the parameter) or `ARTIFACT` (the content of an artefact of an earlier step). Unbound parameters
   take the preset value or the default. The workflow's start form is the union of its `START` bindings and the run
   inputs of its action nodes (ADR 0056); a `START` ISSUE_KEY parameter is the run's issue key. Migration: template
   `runInputs` become parameters and every step of such a template binds them `START`, so start forms stay the same.
3. **Session on the agent.** `Agent.session`: `PER_STEP` (default: the repeats of one step continue its conversation,
   ADR 0060 `OWN`), `PER_RUN` (all steps of this agent in a run are one conversation) or `FRESH` (every execution
   starts anew). The step keys `session` and `continueSession` are gone (read: ignored; legacy `sessionRole` keeps
   working as before).
4. **Directory on the agent.** `Agent.access.directory`: `RUN` (default) or `SEPARATE` (a fresh directory with only the
   step's repositories; no session continuation). The step key `directory` is gone (read: ignored).
5. **Sandbox only on the project.** `sandboxPolicy` leaves templates (and was never applied per step); the project's
   policy under the platform and pool ceilings stays (ADR 0024).

## Alternatives considered

- **Keep a system prompt field "for advanced users".** It would be used for role text again and compete with the
  agent. Rejected; `instructions` may still say anything.
- **Run inputs declared on the workflow by hand.** Duplicates the parameter declarations; the `START` binding gives the
  same form with labels and types from the template. Rejected.

## Consequences

- OpenAPI: `TaskTemplateInput.instructions`, `instructionsMode`; `prompt` and `runInputs` and `sandboxPolicy` removed;
  `TemplateParameter.type` gains `ISSUE_KEY`; `TaskOverrides.instructionsAppend`, `instructionsReplace`,
  `parameterBindings`; `systemPromptAppend`, `promptAppend`, `promptReplace`, `parameters` removed (read compatibly);
  `AgentInput.session`; `AgentAccess.directory`; `ResolvedTask.instructions`; `/workflows/{id}/run-inputs` returns the
  `START` bindings and action run inputs.
