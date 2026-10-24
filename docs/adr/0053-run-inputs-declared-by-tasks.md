# 0053 — Run inputs declared by library tasks; the start dialog is a form built from them

- Status: proposed
- Date: 2026-10-24
- Deciders: owner
- Related: ADR 0029 (prompt-injection defences), 0033 (Jira intake), 0049 (workflow authoring through MCP);
  docs/architecture/07-task-library.md, docs/architecture/03-pipelines-and-agents.md, docs/manual/workflows.md,
  docs/manual/task-library.md

## Context

A manual run start asks for a free-form JSON `parameters` map and always shows a Jira issue key field, although
only workflows with Jira steps use an issue key. People should fill a plain-text form instead. What a run needs
depends on the tasks it runs: an agent task takes free input material, a Jira step needs an issue key, the Spec Kit
plan takes technical hints. System starters (CI, the Jira intake watcher, the personal intake, MCP) send the
parameters map and must keep working unchanged.

Interfaces already have a slot kind `INPUT` ("Run input" in the interfaces page) that nothing checks today.

## Considered options

- **L1. Library tasks declare the run inputs; the start form is the union of the inputs of all task nodes of the
  workflow.** Declared once, reused by every workflow; the issue key field appears exactly when a Jira step is
  present; each task's prompt shows the inputs it asked for; interface `INPUT` slots get a meaning. Costs a
  database column and a library editor; two tasks may declare the same name with different labels; a task on a
  rarely taken branch still adds its required field.
- **L2. The START node declares the fields.** No task-library change and one place for the workflow author, but
  every workflow repeats the same fields, the issue key field depends on the author remembering it and prompts do
  not know which input belongs to which task.
- **L3. Both: tasks declare, START adds workflow-only fields or overrides label/required.** Most flexible, but two
  places to look and more designer and validation work.

## Decision

L1. L3 can be added later without breaking L1.

1. **Field model.** A library task template has `runInputs`, a list of `RunInputField`: `name`, `label`, `type`
   (`TEXT` single line, `MULTILINE`, `ISSUE_KEY`), `required` and an optional `help`. Names are unique within a
   template and identifiers (`^[A-Za-z][A-Za-z0-9_-]{0,63}$`, they become run-parameter keys); labels are not blank
   and at most 200 characters, help at most 1 000. `RunInputField` lives in the shared kernel because the Projects
   context reads it through `ResolvedTask.runInputs`.
2. **Inheritance.** `TaskResolver` merges `runInputs` from root to leaf: union by name, the child's field wins
   (it replaces the parent's field of the same name in place). Node overrides do not change run inputs.
3. **Interface `INPUT` slots.** Resolution rule 5 also requires every required `INPUT` slot of an implemented
   interface to appear by name in the resolved `runInputs`.
4. **Effective inputs of a workflow.** The union of the resolved `runInputs` of all task nodes, in definition
   order: a field is required when any task requires it, the first label (and type and help) wins. The START node
   declares no fields of its own; the designer shows the effective inputs on it read-only. A required field of a
   task on a rarely taken branch is still required at start (accepted by the owner). `GET
   /workflows/{id}/run-inputs` returns the effective list for the start dialog.
5. **Issue key.** The value of an `ISSUE_KEY` field is mapped to `RunInput.issueKey` (the `issueKey` of the request
   wins when both are given, and also satisfies a required `ISSUE_KEY` field), so the Jira steps keep reading the
   run's key. On checked starts the value must look like an issue key (`ABC-123`, any case), because it reaches Jira
   calls and branch names. A step whose `issueKeyFrom` names another run parameter does not need the run's key, so
   its `ISSUE_KEY` fields count as optional.
6. **Checked starters.** Manual and API-token starts through `POST /workflows/{id}/runs` are checked: a required
   field that is missing or blank, or any declared field longer than **20 000 characters**, is rejected with a
   validation error naming the field. The personal intake, the Jira intake watcher, CI and MCP starts are not
   checked; they send what they always sent.
7. **Undeclared keys are kept** in `parameters`; the start dialog keeps a collapsed "Advanced (JSON)" section for
   such extra parameters (the issue key cannot be set there). `StartRunRequest` and the MCP `start_workflow` tool
   keep their shape.
8. **Prompts.** The built-in LLM and agent system prompts render a "Run input" list (label: value) of the current
   task's declared fields from a new template variable `runInput`, inside the existing untrusted-data boundaries;
   `parameters.*` stays available to custom templates.
9. **Built-ins and old workflows.** `base-agent` declares `input` (multi-line, optional, "Input material"),
   `fetch-issue` and the Jira notify and transition tasks declare `issueKey` (`ISSUE_KEY`, required) and
   `spec-kit-plan` declares `techHints` (multi-line, optional). The built-in checksum changes, so the seeder updates
   the built-ins and existing workflows get their form without any change of their own. Project copies of templates
   that do not extend `base-agent` show no input box; extra values still go through Advanced (JSON).
10. **Images** pasted with Ctrl+V into input fields are out of scope; they move to phase 2 with their own ADR.

## Consequences

- An additive migration (`task_template.run_inputs jsonb not null default '[]'`); runs keep their own definition
  snapshot and need no migration.
- Scripts that start Jira workflows through the REST API with a token must send the issue key; they already failed
  without it at the fetch step, now they fail at start with a clear message.
- The built-in prompt text changes, so outputs may shift slightly; custom `{{parameters.x}}` templates keep working.
- The 20 000-character limit bounds prompt size and cost per field; input text stays untrusted data in prompts.
- Template editing keeps the library permissions and audit entries; starting still needs `run.start`.
- `WorkflowValidator` warns (does not fail) when a Jira step reads `issueKeyFrom` from a run parameter that no task
  declares, and when two tasks declare the same field name with different labels.
