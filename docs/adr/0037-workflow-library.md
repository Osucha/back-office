# 0037 — Workflow library: versioned workflows shared across projects, linked copies and workflow metrics

- Status: accepted
- Date: 2026-10-09
- Deciders: owner
- Related: ADR 0014 (graph workflows), 0015 (Task Library), 0017 (deny-by-default), 0018 (audit), 0025 (delivery
  insights), 0034 (multi-repository projects), 0035 (Jira connection model); docs/architecture/07-task-library.md,
  docs/architecture/11-governance.md, docs/manual/workflow-library.md

## Context

The Library holds the building blocks of agent work (task templates, interfaces, skills), but workflows live only
inside projects. A process that works well in one project has to be rebuilt by hand (or copied node by node) in the
next, the copies drift apart, and nobody can tell whether "Issue to reviewed MR" performs better in version 4 than it
did in version 3, or better than the variant another team uses. The owner wants workflows in the Library so they are
designed once, used in many projects, and measured: how often they succeed (effectiveness) and how long and how much
they take (efficiency).

## Decision

1. **Library workflow** (`LibraryWorkflow`, Projects context, because the graph model, its validator and the run
   engine live there; tables `library_workflow`, `library_workflow_version`, migration `V111`): `key` (slug, unique),
   `name`, `description`, `tags`, `deprecated`, and an append-only list of **immutable versions** (`version`,
   `changeNote`, the graph: nodes, edges, suggested triggers, canvas; `publishedAt/By`; the source project, workflow
   and workflow version).
2. **Published from a project**, not edited in the Library: `POST /workflows/{id}/publish` creates a new entry or a
   new version of an existing one (`library.manage` platform-wide **and** `workflow.edit` on the source project).
   Publishing validates the graph against the **global** Task Library: a step that uses a project-scoped template is
   an error ("publish the template globally first").
3. **Project-local settings are not published.** They are stripped from the published graph and kept when a project
   takes a new version: per-step repositories (`config.repositories`, ADR 0034), a pinned Jira connection id
   (`config.jiraConnection` other than `SYSTEM` / `ACTING_USER`, ADR 0035), named approver users of gates and write
   steps (`approvers.users`; roles stay), and the workflow's name, triggers, enabled flag and viewport.
4. **Linked copies.** A project *adopts* a library version (`POST /projects/{id}/workflows/from-library`, `workflow.edit`
   on the project and `library.view`): an ordinary project workflow is created with a link
   `library {libraryWorkflowId, key, version, modified}`. It runs, is edited and is versioned like any workflow. The
   link records the library version it follows and whether the graph differs from that version (`modified`,
   recomputed on every save: nodes without project-local settings and positions, edges without ids). A newer library
   version shows as "update available"; `POST /workflows/{id}/library/update` replaces the graph with the chosen
   version and re-applies the project-local settings of nodes with the same id (refused with 409 when the copy is
   `modified` unless `discardLocalChanges` is set). `POST /workflows/{id}/library/detach` removes the link. The
   source workflow of a publish becomes linked to the version it produced.
5. **Runs remember the library version** they ran: `workflow_run.library_workflow_id`, `library_version`,
   `library_modified` (copied from the definition snapshot at start, indexed). Metrics are therefore exact per
   library version even after the project workflow changes.
6. **Workflow metrics** in the Governance read model (`JdbcWorkflowInsightsReadModel`, SQL over `workflow_run`,
   its node executions and the usage ledger, like delivery insights): `GET /insights/workflows` for one library
   workflow (across the visible projects) or one project workflow, and `GET /insights/library-workflows` for the list.
   Effectiveness: success rate, first-pass rate (successful without a revision or retried step), failure and
   cancellation counts, rework loops per run. Efficiency: end-to-end duration and active time (end-to-end minus human
   and CI waits) p50/p90, human wait, cost per run and per successful run, tokens. Broken down by version, by project
   and by node (executions, failures, durations, cost, the ports taken: e.g. how often a gate answered `revise`).
   Runs of locally modified copies are counted and shown separately so a version is not blamed for project edits.
7. **Lifecycle**: `deprecated` hides an entry from "Add from library" and marks linked copies; deleting an entry is
   refused while any project workflow is linked to it. Name, description, tags and `deprecated` are edited in the
   Library (`library.manage`). Every change is audited (`LIBRARY_WORKFLOW_PUBLISH`, `_UPDATE`, `_DELETE`,
   `WORKFLOW_ADOPT`, `WORKFLOW_LIBRARY_UPDATE`, `WORKFLOW_LIBRARY_DETACH`).

## Alternatives considered

- **Live reference** (projects run the library graph directly with parameters): no drift, but no project-specific
  graph changes at all and every library edit instantly changes every project's process. Rejected by the owner.
- **Plain template copy** without a link: simplest, but no propagation of fixes and no way to attribute runs to a
  library version, which defeats the measuring goal.
- **Workflows in the Task Library context**: the context owns the Library UI, but the graph model, validator and run
  engine are in the Projects context; moving them to the shared kernel would leak a large model across contexts.
  The Library UI shows both through their own APIs instead.
- **Editing library workflows on their own canvas**: possible later; the validated path today is designing and
  running a workflow in a real project, then publishing it.
- **Materialised metric tables**: not needed at current volumes (same reasoning as ADR 0025); the queries use the new
  `(library_workflow_id, created_at)` and existing `workflow_run` indexes.

## Consequences

- `WorkflowDefinition` and the OpenAPI `WorkflowDefinition` gain a read-only `library` link; `WorkflowRun` gains the
  library columns. Existing workflows and runs have no link (null) and are unaffected.
- No new permission keys: `library.view` / `library.manage` (Task Library) and `workflow.edit` / `project.view`
  (Projects) cover every operation. Metrics only count runs of projects the caller may view.
- The seeded starter workflows stay project-local; they can be published like any other workflow.
