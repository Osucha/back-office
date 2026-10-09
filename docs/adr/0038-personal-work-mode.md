# 0038 — Personal work: private projects and a personal Jira intake that proposes runs

- Status: accepted
- Date: 2026-10-09
- Deciders: owner
- Related: ADR 0028 (manager-led projects), 0033 (Jira intake watcher), 0035 (Jira connection model; amended
  here), 0032 (desktop notifications), 0037 (workflow library); docs/manual/my-work.md

## Context

Back Office was designed for a team that runs its software process in it: a manager creates a project, designs the
SDLC on the canvas, decides which step a person decides and which an agent does (ADR 0028), and the project's Jira
intake watcher starts runs on the project's shared reader connection (ADR 0033).

The owner also works the other way: in a team that does not use Back Office, issues are assigned to them in Jira (any
number, in different statuses) and they want to automate **their own** work. That needs three things the team mode
does not offer:

1. a place for their workflows without asking a manager for a project (`projects.create` is a manager permission);
2. watching **their** issues, which only their own Jira account can express (`assignee = currentUser()`), while ADR
   0035 forbids the watcher to use a personal connection;
3. a confirmation **before** a run starts, per issue, with a notification, instead of a run that starts on its own.

## Decision

Both ways of working exist side by side in one installation; there is no global mode switch.

1. **Personal projects.** `Project.personal` (column `project.personal`, immutable after creation). Creating one
   needs the new platform permission **`projects.create.personal`**, granted to every member (built-in roles
   `member`, `project-manager`, `evaluator`, `library-curator`; identity migration V410); `projects.create` also
   allows it. The creator becomes the owner as usual. A personal project has **only its owner**: adding members is
   refused (identity context, through the new shared-kernel port `PersonalProjects`). Everything else (repositories,
   workflows from the library, runs, budget, connections) works as in a team project. Platform administrators with
   `admin.projects.all` still see it, like every project.
2. **Personal intake rules** (`PersonalIntakeRule`, Projects context, table `personal_intake_rule`, V112): owned by
   one user, managed only by that user under `/me/intake` (permission `connections.personal`). A rule names one of
   the owner's **personal connections** with `ISSUE_READ`, a **JQL** (for example
   `assignee = currentUser() AND statusCategory != Done`), a poll interval (1–1440 minutes), a **default workflow**
   (in a project where the owner holds `run.start`) and whether the issues already matching on the first poll become
   proposals (`includeExisting`, default true). At most 10 rules per user.
3. **"Appears in the results" detection.** Every poll runs the rule's JQL (at most 50 issues, `ORDER BY updated DESC`
   unless the JQL orders itself) and creates one **proposal** per issue key the rule has not seen before, whatever
   made the issue match (assignment, a status change or creation). An issue gets at most one proposal per rule; a
   dismissed proposal can be restored. Polling uses the work queue (`PERSONAL_INTAKE_POLL`, one live item per rule),
   with exponential back-off on errors (Retry-After honoured), like ADR 0033.
4. **Amendment of ADR 0035.** A personal connection may be used **to read issues for its owner's own intake rules**:
   the owner configured the rule (their action), the client is narrowed to `ISSUE_READ`, and the results are visible
   only to the owner. Personal connections remain unusable by project watchers, by other people and for writes
   without the owner's approval.
5. **Proposals, not runs** (`IntakeProposal`, table `intake_proposal`): issue key and, for display only, summary,
   type, status and priority (escaped in the UI, never sent to a model by the intake; the run's Fetch issue step reads
   the issue as untrusted data, ADR 0029). States `NEW` → `STARTED` (with the run id) or `DISMISSED` (restorable);
   `BASELINE` for issues seen on a first poll without `includeExisting`. New proposals publish
   `IntakeProposalsFound` with `ownerUserId`, so the event stream and the desktop notification ("New Jira tasks")
   reach only the owner.
6. **Start = the owner starts the run.** `POST /me/intake/proposals/{id}/start {workflowId?}` starts the rule's
   default workflow (or another one the owner may start) through the ordinary `StartRun` use case: `run.start` is
   checked, the run's `startedBy` is the owner, so `ACTING_USER` Jira steps may use the owner's personal connection
   (ADR 0035). Trigger `MANUAL`, parameter `intakeProposalId`.
7. Audit: `INTAKE_RULE_CREATE/UPDATE/DELETE`, `INTAKE_RULE_POLL_NOW`, `INTAKE_PROPOSAL_START`, `_DISMISS`, `_RESTORE`.

## Alternatives considered

- **A global installation mode** (team or personal): one installation could not serve a team with a manager and an
  individual automating their own work at the same time.
- **A project type with a simplified UI** for personal work: more UI branches for the same building blocks; a
  personal project is the same project with one owner.
- **Workflows of team projects for personal intake**: the team would see an individual's runs and the individual
  would need the team's `run.start`; still possible, because a rule may name any workflow the owner may start.
- **A run that waits at a first gate** (no new concept): the run would be started by the watcher, so its steps could
  not act as the owner, every issue would leave a run behind even when ignored, and the project watcher still could
  not see "my" issues.
- **Project watcher with a status list**: does not express assignment and still needs a shared account.

## Consequences

- New UI: **My work** (proposals with Start / Dismiss / Restore, rules), "New private project" on the Projects page,
  a "private" badge, no Members tab for personal projects, a "New Jira tasks" notification setting, a link from
  **Your turn**.
- `projects.create.personal` lets every member create projects; budgets (ADR 0025) and the platform budget still
  limit what they cost.
- The team mode is unchanged.
