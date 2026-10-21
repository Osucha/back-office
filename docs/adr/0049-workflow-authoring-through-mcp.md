# 0049 — Workflow authoring through the MCP server

- Status: accepted
- Date: 2026-10-21
- Deciders: owner
- Related: ADR 0025 (MCP server), 0029 (prompt injection, gate decisions via MCP), 0019 (workflows);
  docs/architecture/11-governance.md, docs/manual/integrations-ide.md

## Context

The MCP server lets an IDE agent read runs, start workflows and (with an opt-in token) decide gates, but not design
workflows. The owner wants to describe workflows to an agent (for example a workflow in which Back Office develops
itself and asks the owner at every decision) and have the agent build them, instead of drawing each one in the
designer. The workflow JSON shape already exists (`WorkflowDefinitionInput` in openapi.yaml), but its mapping to the
domain lived in `projects-adapters`, which the MCP adapter may not import.

## Decision

1. **One encoding.** The framework-free JSON shape and its mapping (`WorkflowJson`, now with `WorkflowDocumentJson`)
   move to `projects.application.port.in`: REST, persistence and the MCP server share it.
2. **Read tools.** `list_projects`, `get_workflow_palette` (control nodes, task templates with ports and config
   schema, state fields), `get_workflow` (definition in that shape) and `validate_workflow` (no save).
3. **Write tools.** `create_workflow` and `update_workflow` call `ManageWorkflows.create/update` (permission
   `workflow.edit`). Both validate first and save nothing on errors; `update_workflow` takes `expectedVersion` so
   a concurrent edit is not overwritten and is annotated destructive. Unknown JSON properties are errors.
4. **Projects and run parameters.** `create_project` (`ManageProjects.create`, at most one repository, optionally
   private, Spec Kit off) needs the same token option; `start_workflow` also takes run parameters as a JSON object, so
   workflows without an issue tracker can be started from the IDE. `list_workspaces`, `create_workspace` and
   `update_workspace` manage the run workspaces of ADR 0044 (writes: `project.manage` and the same token option).
5. **Token option.** Writes need a personal access token created with **Allow workflow edits via MCP**
   (`allowMcpWorkflowEdits`, `TokenCapabilities.MCP_WORKFLOW_EDITS`, identity V412, default off); refusals are
   audited as `MCP_WORKFLOW_EDIT` `DENIED`. Reason: an agent steered by injected text could otherwise remove a human
   gate or point a step at another repository.

## Consequences

- An agent can build complete workflows, including human gates, from a conversation; the owner reviews them in the
  designer (versions and authors are recorded as for designer edits).
- Removing a gate through MCP is possible with the option; the tool description tells the agent never to do it
  unless asked, and the change is versioned and audited.
