# 0028 - Manager-led projects and step assignment

Status: accepted - 2026-10-07

## Context
The operating model the owner wants: users do not each create their own project. A manager (someone above
the team) creates and manages the project and decides which step is done by whom.

What the platform did before:

- The built-in platform role `member`, granted to everyone at SYSTEM level, held `projects.create`. Every
  signed-in user could create a project and became its `owner`, so the project landscape was whatever
  individuals created.
- Who decides a human gate was fixed in the workflow definition (`approvers.projectRoles`, `approvers.users`,
  `forbidSelfApproval`; ADR 0017, `GateApprovalPolicy`). When the named approver was away, or the manager
  wanted a specific person on one run, the only way was to edit the workflow (a new version, affecting
  every later run) or to change project roles.

Built-in roles are stored and never overwritten by seeding (administrators may edit them), so a change of
the built-in set needs an explicit upgrade step for existing installations.

## Decision
1. **Project creation is a manager permission.** `projects.create` is removed from `member` (and from
   `evaluator` and `library-curator`, which are `member` plus one permission). A new built-in platform
   role **`project-manager`** ("Project manager", pl "Kierownik projektów") holds `projects.create` and
   `usage.view` plus the member permissions (`integrations.view`, `library.view`, `models.view`,
   `evaluation.view`, `tokens.personal`). `platform-admin` keeps everything. The creator of a project
   still becomes its `owner`.
2. **Run-time assignment of an open gate.** A new project permission **`run.assign`** (owner and
   maintainer) allows `PUT /approvals/{id}/assignees {userIds, comment}`: the named users decide this
   OPEN approval, replacing the definition's `approvers.users` for this approval only (an empty list
   restores the definition's rule). The roles rule and the four-eyes rule still apply; every assignee must
   be an active user who holds `run.approve` on the project and satisfies them (400 otherwise), resolved by
   the identity context through the new shared-kernel port `ProjectAccessDirectory`.
   `GET /approvals/{id}/assignee-candidates` lists the project members who qualify. The assignment is
   stored on the approval (`approval_request.assignment`, V106), audited as `APPROVAL_ASSIGN` (old/new
   assignees) and published as `ApprovalAssigned`, so inboxes update live; the assignee sees the gate in
   **Your turn → Needs you** ("Assigned to you by ...").
3. **Definition-level assignment stays** in the designer's gate panel, now labelled "Who decides this
   step", with a hint that owners and maintainers can reassign a waiting step in a single run.
4. **Upgrade.** The identity migration V406 removes `projects.create` from the stored built-in member,
   evaluator and library-curator roles and adds `run.assign` to the stored built-in owner and maintainer
   roles, audits each change (`ROLE_UPDATE`, actor `system`) and bumps the access version. The startup
   seeding creates the missing `project-manager` role (audited `ROLE_CREATE`).

## Alternatives
- **Keep `projects.create` for everyone and rely on conventions.** Rejected: the owner asked for the
  manager-led model to hold, and conventions do not hold.
- **Reuse `project.manage` for assignment** instead of a new permission. Rejected: `project.manage` is
  "edit settings" and is the permission a custom role would grant for configuration work; deciding who
  does which step is a separate responsibility an administrator may want to give to a lead without
  settings access (a custom project role with `run.assign`).
- **Assign by editing the workflow** (a new definition version). Rejected: it changes every later run,
  needs `workflow.edit`, and does not help a run that is already waiting.
- **Let any approver hand the gate to someone else.** Rejected: it moves the decision of who decides away
  from the person accountable for the project.
- **Grant `project-manager` automatically to existing project owners on upgrade.** Rejected: it would
  re-create the old model silently; administrators decide who the managers are (the first-run checklist
  and the manual point to it).

## Consequences
- After the upgrade, users who are not `project-manager` (or `platform-admin`, or granted
  `projects.create` directly) can no longer create projects; the Projects page says "Project managers
  create projects" and the empty state asks to be added by a project manager or owner. Administrators
  must give the role to the right people or groups (Administration → Grants).
- Administrators who had deliberately edited the built-in member role keep their other edits; only the
  `projects.create` key is removed.
- Approvals carry `assignees` (names), `assignment` and `canAssign`; `GateApprovalPolicy` checks the
  assignees instead of the definition's named users when an assignment exists.
- A new shared-kernel port (`ProjectAccessDirectory`) gives the Projects context read access to resolved
  project access of other users without importing the identity context.
