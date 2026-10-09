# Administration

Back Office is deny-by-default: nobody can do anything until a role or grant allows it. Administrators
invite people, organise them in groups, decide what each group or person may do on the platform, tune
sign-in rules and budgets, read the audit log and watch the platform's health. Everything is under
**Administration** in the navigation; each page appears only for people with its permission.

**Who needs this:** platform administrator, auditor.

| Page | Path | Needs |
|---|---|---|
| **Users** | `/admin/users` | `admin.users.view` (view), `admin.users.manage` (change) |
| **Groups** | `/admin/groups` | `admin.users.view`, `admin.users.manage` |
| **Roles**, **Grants** | `/admin/roles`, `/admin/grants` | `admin.users.view` or `admin.roles.manage` (view), `admin.roles.manage` (change) |
| **Platform settings** | `/admin/settings` | `admin.settings.manage` |
| **Connections** | `/admin/connections` | `integrations.view` (view), `admin.connections.manage` (change) ([Connections](#connections)) |
| **Runner fleet** | `/admin/fleet` | `fleet.view`, `fleet.manage` ([runner-fleet.md](runner-fleet.md#pools)) |
| **Operations** | `/admin/operations` | `admin.settings.manage` |
| **Audit log** | `/admin/audit` | `admin.audit.view` |

The first administrator comes from `BACKOFFICE_AUTH_BOOTSTRAP_ADMINS` (OIDC) or the local bootstrap
admin ([integrations-identity.md](integrations-identity.md#local-accounts)). Keep at least two platform
administrators; the last active one can be neither deactivated nor stripped of the role.

<a id="users"></a>
## Users

**Invite a person**

1. **Users** → **Invite user**.
2. **E-mail** (the matching key at the first sign-in), optional **Display name**, **Groups**, **Platform
   roles** (needs `admin.roles.manage` too) and **Project memberships** (needs `project.members.manage` on
   each project).
3. Choose the sign-in method: identity provider, **Local** or **Both**.
4. Back Office sends **no e-mail**. Copy the **Sign-in link** (OIDC) or the one-time **Set-password link**
   (local, valid 24 hours) from the panel and send it yourself.

The user is `INVITED` until the first sign-in binds the account (OIDC: issuer + subject) and makes it
`ACTIVE`. Inviting an existing e-mail is refused.

**On a user's page**

| Action | Effect |
|---|---|
| **Access** | groups, platform roles, project memberships and **Effective permissions** (System / Group / User / Effective) |
| **Sign-in method** | switch between identity provider, local and both; removing a method ends the user's sessions |
| **Issue set-password link** | new 24-hour single-use link; the current password stops working and sessions end |
| **Reset MFA** | removes the authenticator and recovery codes (verify the person out of band first) |
| **Unlock account** | clears a lockout after failed sign-ins |
| **End all sessions** | signs the user out everywhere |
| **Deactivate user** | ends sessions, revokes personal tokens, blocks sign-in; nothing is deleted; **Activate** restores |

You cannot deactivate yourself.

<a id="groups"></a>
## Groups

A group carries platform roles and project memberships for all its members.

1. **Groups** → new group; **About**: name, description.
2. **Members and roles**: add users and platform roles.
3. **Identity provider** (optional): an **external group** name. When **Synchronise groups from** the
   provider is on (**Platform settings** → **Registration**), each sign-in sets the user's membership of
   every group with an external group from the provider's groups claim (`architects` and `/architects`
   match the same). Groups without an external group are never touched.
4. Add the group to projects on the project's **Members** tab ([projects.md](projects.md#members)).

A group that is still a member of a project cannot be deleted: remove the memberships first.

<a id="roles-grants"></a>
## Roles and grants

A **role** is a named bundle of permissions with a scope. Built-in roles are seeded, editable, not deletable:

| Role | Scope | Permissions |
|---|---|---|
| `platform-admin` | Platform | all platform permissions, including `admin.projects.all` (every project permission everywhere) and `admin.connections.manage` |
| `member` (granted to everyone) | Platform | `library.view`, `models.view`, `evaluation.view`, `tokens.personal`, `integrations.view` |
| `project-manager` | Platform | `member` + `projects.create`, `usage.view` |
| `evaluator` | Platform | `member` + `evaluation.run` |
| `library-curator` | Platform | `member` + `library.manage` |
| `auditor` | Platform | `admin.users.view`, `admin.audit.view`, `usage.view`, `library.view`, `models.view`, `evaluation.view` |
| `owner` | Project | every project permission except `ci.*`, including `run.assign` and `project.connections.manage` |
| `maintainer` | Project | `owner` without `project.members.manage` and `project.connections.manage` (keeps `run.assign`) |
| `developer` | Project | `project.view`, `workflow.edit`, `run.start`, `run.cancel`, `run.signal`, `project.usage.view` |
| `approver` | Project | `project.view`, `run.approve`, `project.usage.view` |
| `viewer` | Project | `project.view` |

Other platform permissions to hand out deliberately: `models.manage`, `usage.view`, `fleet.view`,
`fleet.manage`, `admin.settings.manage`, `admin.roles.manage`, `admin.users.manage`,
`admin.connections.manage`. **Roles** → new role
creates custom bundles; a role still assigned cannot be deleted.

**Grants** (platform scope) are set on three levels: **System** (everyone signed in; default role
`member`), **Group**, **User**. Each level holds roles plus individual **Allow**/**Deny** per permission.

How a permission is decided:

1. The closest level that says anything wins: User over Group over System.
2. Within a level Deny beats Allow, also across all of a user's groups.
3. A role counts as Allow for each of its permissions.
4. Nothing anywhere means denied.

Project permissions come from project memberships (users or groups with a project role); the creator of a
project becomes its `owner`, and the last owner cannot be removed. Check the outcome on the user's
**Effective permissions**. Every change is audited (`GRANT_CHANGE`, `ROLE_ASSIGN`, `ROLE_UNASSIGN`) and
takes effect on the user's next request.

<a id="project-managers"></a>
## Project managers

Users do not each create their own project. **Project managers** create projects and decide who does which
step; everyone else works in the projects they are added to.

- The built-in platform role **Project manager** (`project-manager`) holds `projects.create` (and
  `usage.view`). The default **Member** role does not, so ordinary users see "Project managers create
  projects" instead of **Create project**. Platform administrators can always create projects.
- Give the role on **Grants**: pick the user or, better, a group such as "Team leads", and add the role
  **Project manager**. Check the result on the user's **Effective permissions**.
- The project manager who creates a project becomes its **Owner**, adds members with project roles
  ([Members and project roles](projects.md#members)), sets who decides each gate in the workflow
  ([Gate settings](workflows.md#gates-config)) and, together with **Maintainers**, can reassign a waiting
  step with **Assign…** (project permission `run.assign`, [Assigning a step](your-turn.md#assign-steps)).

**Upgrading an existing installation**: the migration removes `projects.create` from the stored built-in
`member`, `evaluator` and `library-curator` roles and adds `run.assign` to `owner` and `maintainer`;
the role **Project manager** is created on start. Each change is in the audit log (`ROLE_UPDATE`,
`ROLE_CREATE` by `system`). After the upgrade, give **Project manager** to the people who should create
projects; the setup checklist on Home reminds administrators ("Name a project manager").

<a id="connections"></a>
## Connections

**Connections** (`/admin/connections`) holds the platform-wide credentials of external systems; today these
are Jira accounts. It is in the **Administration** group of the navigation rail and in the command palette
(`Ctrl K`, **Connections**) for holders of `admin.connections.manage`. A project uses a platform connection
when its owners choose it as the project's system connection or list it for its steps
([System connection](integrations-jira.md#jira-system-connection)); projects without a system connection pick
from every project and platform connection with the fewest extra capabilities. Project owners add project
connections on the project's **Connections** tab ([Project connections](projects.md#project-connections)).

**Personal connections** (below the list, for holders of `admin.connections.manage`): every person's own Jira
connections from **My account** → **Connections**, shown as metadata only (owner, name, site, capabilities, kind
of token, expiry, last check). Administrators can neither see their e-mail or token nor use or change them; they
are used only with their owner's own approval or run start
([Personal connections](integrations-jira.md#jira-personal-connections)).

**Platform settings** → **Features** → **Allow Spec Kit** (default on) lets projects turn on Spec Kit; when off,
no project can run Spec Kit steps ([Spec Kit](projects.md#spec-kit)).

- **View**: `integrations.view` (every member). **Add**, **Edit**, **Replace token**, **Test**, **Test
  before saving**, **Delete**: `admin.connections.manage` (built-in `platform-admin`).
- Each connection declares **capabilities** (**Read issues**, **Comment on issues**, **Move issues**) and
  optionally **Allowed projects**; a workflow step uses the connection with the fewest capabilities that
  still cover what it needs. Create a reader and a writer connection backed by two Jira accounts with
  exactly those permissions: [Least privilege](integrations-jira.md#jira-least-privilege),
  [Connections](integrations-jira.md#jira-connections). A platform reader also serves every project's
  [Jira intake watcher](integrations-jira.md#jira-intake).
- Tokens are write-only and encrypted with `BACKOFFICE_SECRETS_KEY`; without the key, adding a connection
  is refused ([Configure Back Office](integrations-jira.md#jira-config)). Back up the key.
- **Test before saving** in the add and edit dialogs checks the account against Jira without storing
  anything; **Test** checks a saved connection. Both show missing and excess Jira permissions
  ([Test a connection](integrations-jira.md#jira-test)).
- The list marks tokens that are **expiring soon** (30 days) or **expired**. From 14 days before expiry,
  administrators get a **Your turn** card and, once per expiry date, a desktop notification
  **Connection expiring** (on by default for administrators); see
  [Token expiry and rotation](integrations-jira.md#jira-token-expiry).
- While `JIRA_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` are set, a read-only legacy connection **Jira
  (environment variables)** with every capability appears here with a notice to migrate
  ([Legacy](integrations-jira.md#jira-config)).

**Upgrading**: the migration adds `project.connections.manage` to the stored built-in `owner` role
(`ROLE_UPDATE` by `system`); `maintainer` deliberately does not get it.

<a id="platform-settings"></a>
## Platform settings

Saved settings win over the server defaults from environment variables.

| Section | Setting | Default |
|---|---|---|
| **Sign-in** | **Allow local sign-in** (cannot be switched off while no identity provider is configured) | on |
| | **Require MFA for local accounts** | off |
| **Password policy** | minimum length (8–128), common-password and e-mail checks | 12, both on |
| **Lockout** | failures before lockout, first lock duration (doubles, max 24 h) | 5, 15 min |
| **Registration** | **Invite only** or **Open**; **Allowed e-mail domains**; **Synchronise groups from** the provider | invite only |
| **Personal access tokens** | **Maximum lifetime (days)** (1–365) | 90 |
| **Budget** | platform monthly limit, alert threshold, hard stop | none |

The platform budget counts every LLM cost of the month (agent calls, workflow LLM steps, reviews,
benchmarks); with hard stop the gateway refuses further agent calls ([costs-insights.md](costs-insights.md#budgets)).
LLM keys and storage are server configuration; **Settings** (`/settings`) shows their status and tests
them. Jira credentials are [Connections](#connections).

<a id="audit"></a>
## Audit log

**Audit log** lists who did what, newest first, written in the same transaction as the change:

- access changes: `USER_INVITE`, `USER_DEACTIVATE`, `GROUP_*`, `ROLE_*`, `GRANT_CHANGE`, `MEMBER_*`,
  `PAT_CREATE`/`PAT_REVOKE`, `CI_TOKEN_CREATE`/`CI_TOKEN_REVOKE`, `SETTINGS_UPDATE`;
- connections: `CONNECTION_CREATE`, `CONNECTION_UPDATE`, `CONNECTION_ROTATE`, `CONNECTION_DELETE`,
  `CONNECTION_VERIFY`, `CONNECTION_TEST` (test before saving), `CONNECTION_EXPIRY_REMINDER`,
  `CONNECTION_SECRET_REWRAP` (never with the token);
- Jira: `JIRA_INTAKE_UPDATE`, `JIRA_INTAKE_POLL_NOW` (intake watcher), `RUN_WRITE_DECIDED` and
  `EXTERNAL_WRITE` (approval before writing, with a SHA-256 digest of the written text);
- sign-in: `LOGIN_SUCCESS`, `LOGIN_FAILURE` (with `reason`), `LOGOUT`, `SESSION_EXPIRED`, local-account
  events (`ACCOUNT_LOCKED`, `PASSWORD_RESET_ISSUE`, `MFA_RESET`, ...), never with a secret;
- refused requests: `ACCESS_DENIED` with the permission and URL;
- work: `WORKFLOW_UPDATE`, `RUN_START`, `RUN_GATE_DECIDED`, `ATTESTATION_CREATE`, ...;
- searching the log itself (`AUDIT_VIEW`).

Filter by time, actor, operation, entity, project, result and correlation id. Every API response carries
`X-Correlation-Id`: ask users for it and search by it. Audit partitions are kept 24 months online by
default (`BACKOFFICE_RETENTION_AUDIT_RETENTION_MONTHS`); export older partitions before enabling drops.

<a id="operations"></a>
## Operations

**Operations** shows the health of the platform itself:

| Card | Shows | Act when |
|---|---|---|
| **Replicas** | each API/worker instance, state, roles, version, last seen; live-update subscribers | an instance is stale or versions differ after an upgrade |
| **Work items** | the queue behind every run step: ready, leased, oldest ready age; agent jobs by state | the oldest ready item ages (workers missing or saturated) |
| **Dead items** | steps that failed all retries, with kind, last error, attempts | fix the cause, then **Retry** |

Runbooks for deeper problems: `docs/runbooks/work-queue.md`, `docs/runbooks/scaling.md`,
`docs/runbooks/upgrade.md`; symptoms in [troubleshooting.md](troubleshooting.md#runs).
