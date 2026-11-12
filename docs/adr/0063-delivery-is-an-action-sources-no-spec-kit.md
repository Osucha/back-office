# 0063 — Delivering changes is an action; sources belong to the workflow; no Spec Kit

- Status: accepted
- Date: 2026-11-12
- Deciders: owner
- Related: ADR 0031 (git access), 0034 (repositories of a step), 0036 (optional Spec Kit), 0043 (library sources),
  0056 (actions are nodes), 0060 (run directory), 0062 (one instruction); followed by 0064 (connectors)

## Context

A coding step still carried git: `push`, `branchTemplate`, `mergeRequest`, three merge request templates, a draft flag,
`commitSubjectTemplate`, its own `repositories` and `setupCommand`. Delivering work (commit, branch, push, merge
request) is not part of doing it: it is a decision of the workflow (deliver after the review, not in the middle of the
work) and the same for every agent. ADR 0056 already made such integrations nodes. With one run directory (ADR 0060)
the agent's work stays in place until something delivers it.

Spec Kit (`phase`, ADR 0036) made Back Office a wrapper around one tool: its own runner mode, project and platform
switches, installed CLI and templates. An agent can use Spec Kit by itself when it is in the repository or among its
skills; Back Office does not need to know.

## Decision

1. **Delivery is an action.** New action `DELIVER_CHANGES` (Deliver changes): in the run directory it commits what the
   agents changed, pushes a branch and optionally opens a merge request. Config: `branchTemplate`,
   `commitSubjectTemplate`, `mergeRequest` (default true), `mergeRequestTitleTemplate`, `mergeRequestDescriptionTemplate`,
   `mergeRequestDraft`, `repositories` (keys to deliver; default every project repository of the workflow's sources
   with changes). Ports `success` / `failure`; produces `mr` and `diff`. It runs as a runner job of mode `DELIVER`
   (no agent) in the run directory; only this job gets push credentials (ADR 0051 tokens with PUSH_BRANCH). The same
   branch safety rules apply (never the default branch, ADR 0031).
2. **Agents never push.** Every agent job is READ: the runner never commits after an agent. The step keys `push`,
   `branchTemplate`, `mergeRequest*`, `commitSubjectTemplate` leave the step and the templates. The diff of the work
   tree against the base stays an artefact of agent steps (reviews read it).
3. **Sources belong to the workflow.** `WorkflowDefinition.sources`: the repositories (project repositories and library
   sources, ADR 0043) cloned into the run directory; default the project's primary repository. Agents read them; only
   `DELIVER_CHANGES` writes back, and only to project repositories. The step key `repositories` and the step
   `setupCommand` leave the step (the workflow's setup command stays, ADR 0060). An agent with a separate directory
   (ADR 0062) gets a fresh copy of the workflow's sources.
4. **Results of a template.** The config key `collectArtifacts` becomes the template field `outputFiles` (globs of files
   returned as artefacts). `verifyCommand` (the acceptance check of the work, run after the agent) stays a template
   config key.
5. **No Spec Kit.** Runner mode `SPEC_KIT`, the config key `phase`, the project and platform Spec Kit switches, the
   Spec Kit CLI in the runner image, `/projects/{id}/spec-kit*` and the templates `spec-kit-specify`, `spec-kit-plan`,
   `spec-kit-tasks` are removed. Built-ins that write the same documents in the repository replace them: `write-spec`,
   `write-plan`, `write-task-list` (coding agents, `codingAgent: true`, output files `specs/**/spec.md`, `plan.md`,
   `tasks.md`, artefacts `spec`, `plan`, `tasks`). Steps of the removed templates are read with the replacements.
6. **Migration.** A step that pushed (its config or its template lineage's `push: true`) becomes the step plus a
   `DELIVER_CHANGES` node `<step id>-deliver` after it carrying its git settings: the step's `success` edges start at
   the delivery's `success`, the delivery's `failure` goes where the step's `failure` went. Step `repositories` move
   into the workflow's `sources` (union), step `setupCommand`s into the workflow's setup command. Done for stored
   workflows, library versions and unfinished run snapshots; read-time compatibility covers imports.

## Alternatives considered

- **A `push` checkbox kept on the step "for simple cases".** Two ways to deliver again. Rejected; the default workflows
  show the pattern.
- **A plugin API for actions now.** Delivery to GitHub, Bitbucket or Gerrit would be implementations of the same action;
  that is worth an API when a second one is needed. Agent-side tools go through MCP connectors (ADR 0064).
- **Keep Spec Kit as an option.** Its own mode, settings and CLI for one tool. Rejected.

## Consequences

- OpenAPI: `ActionType.DELIVER_CHANGES`; `WorkflowDefinitionInput.sources`; `TaskTemplateInput.outputFiles`; runner mode
  `DELIVER` (`SPEC_KIT` removed); Spec Kit settings and endpoints removed; the coding agent config schema keeps
  `verifyCommand`, `agentTools`, `allowQuestions`, `maxQuestionRounds`, `codingAgent`.
