# 0041 — Workflow drafts; the project key is Back Office's own short code, not a Jira key

- Status: accepted
- Date: 2026-10-13
- Deciders: owner
- Related: ADR 0014 (graph workflows; amended), 0033 (Jira intake), 0035 (Jira connection model; amended),
  0037 (workflow library); docs/manual/workflows.md, docs/manual/projects.md

## Context

1. Saving a workflow ran the full validation (graph, connections, repositories, Spec Kit) and refused any error, in
   the API and in the designer. Unfinished work could not be kept: a half-designed graph was lost or had to be bent
   into a valid shape first.
2. Every project has a required, unchangeable key. Besides labels, CI logins (`ci:<KEY>`), MCP lookups and the
   `{{project.key}}` template variable, it silently served as the **Jira project key** whenever the binding named
   none (and the project form copied it into the binding). A key chosen without Jira in mind could then not be
   aligned with the Jira project, and connections limited to the Jira project failed with a confusing message.

## Decision

1. **Drafts.** Create and update always save (still requiring a name and nodes). A definition with validation errors
   is stored with `draft = true` (migration V113, `WorkflowDefinition.draft`, API field `draft`, read-only). No run
   starts from a draft — by hand, from CI, the Jira intake watcher or a My work proposal — and a draft cannot be
   published to the library; the next save without errors clears the flag. Adopting or updating from the library
   still validates. Warnings never block. The designer always allows saving and says that errors keep a draft; the
   workflow list shows **draft (has errors)** and disables **Start run**.
2. **Short code.** The project key stays (CI logins, MCP, templates, history) but is presented as Back Office's own
   short code: optional on create (generated from the name: initials of several words or the first letters,
   diacritics dropped, a number appended while taken) and changeable on update (unique; memberships follow through
   `ProjectAccessProvisioner.projectRenamed`; runs, CI executions and audit keep the code they recorded).
3. **No implicit Jira key.** `Project.jiraProjectKey` is the binding's key or nothing; the form requires it when Jira
   is on. Without it connection choice treats the Jira project as unknown, and the intake watcher refuses to poll
   unless a JQL filter narrows the search. Migration V114 writes the old fallback explicitly for projects that use
   Jira (a JQL filter, an intake watcher or a Jira system connection), so they behave as before.

## Alternatives considered

- **Remove the project key entirely**: CI logins, MCP tools, templates, run history and memberships use it; replacing
  it with the UUID or the name would break readable CI identities and existing integrations.
- **Drafts as a separate object next to the published workflow**: two definitions per workflow, merge rules and a
  second editor state; the version history already keeps every save, so one flag is enough.
- **Keep the Jira fallback, only allow renaming**: the confusion (a short code that silently means a Jira project)
  would remain.

## Consequences

- Unfinished workflows can be saved at any time; runs and the library only ever see complete ones.
- A renamed project shows its new code everywhere live; old runs and audit entries show the code they were created
  with. CI scripts that print the login see the new `ci:<CODE>`.
- Projects that relied on the Jira fallback without any Jira configuration get no Jira key; their Jira steps choose
  connections without a project restriction until the key is set (the designer and the watcher say so).
