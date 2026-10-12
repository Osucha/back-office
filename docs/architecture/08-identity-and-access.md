# 08 — Identity, access and audit

Back Office is a multi-user platform: one installation serves one organisation with many users, groups
and projects (ADR 0016–0018). Multi-tenancy (several organisations per installation) is deliberately
out of scope; every table that would need a tenant column already hangs off a project or a user, so it
can be added later without reshaping the model.

## Principles

| # | Principle | Where it comes from |
|---|---|---|
| 1 | Authentication through an OIDC provider (Keycloak by default) and/or local accounts with Argon2id passwords and optional TOTP, chosen per user (`authMode`). | ADR 0016, ADR 0021 |
| 2 | The browser never sees a token. The API is its own backend-for-frontend: OIDC login, server-side session, HttpOnly cookie, same origin. | ADR 0016; common BFF cookie pattern |
| 3 | Authorisation is decided by Back Office, not by the identity provider, because it is project-scoped. | ADR 0017 |
| 4 | Deny by default: every use case calls `AuthorizationGate.require(...)` or is explicitly marked `@PublicOperation` / `@SystemOperation`; the build fails otherwise. | ADR 0017; guardrail GR-A10 |
| 5 | Permissions come from code (module contributions); the database stores only grants. | ADR 0017 |
| 6 | Effective permissions are resolved per request (cached, invalidated on change), never frozen in a token: revoking access takes effect on the next request. | ADR 0017 |
| 7 | Every change to access, every denied access and every authentication event is audited in the same transaction as the change. | ADR 0018 |

## Authentication flow

```
Browser ──GET /api/v1/me──────────────────────────────▶ API   401 (no session)
Browser ──full page /oauth2/authorization/oidc?returnTo=/projects ─▶ API ─302─▶ Keycloak login
Keycloak ──302 /login/oauth2/code/oidc?code=...──────▶ API   code exchange, JIT provisioning,
                                                            session created, cookie BO_SESSION
API ──302 returnTo──────────────────────────────────▶ Browser
Browser ──GET /api/v1/me (cookie, X-XSRF-TOKEN on writes)─▶ API  200 {user, permissions, ...}
Browser ──POST /api/v1/auth/logout──────────────────▶ API   session invalidated, 200 {logoutUrl}
Browser ──full page logoutUrl (Keycloak end_session)─▶ Keycloak ─302─▶ /
```

| Aspect | Rule |
|---|---|
| Session | Server-side (Spring Session JDBC, table `SPRING_SESSION`), cookie `BO_SESSION`, `HttpOnly`, `Secure` (except local), `SameSite=Lax`, path `/`. |
| Idle timeout | `backoffice.auth.session.idle-timeout` (default `PT30M`), returned as `idleTimeoutSeconds` in `/me` for the UI countdown. |
| Absolute timeout | `backoffice.auth.session.absolute-timeout` (default `PT12H`) measured from login (`authTime` session attribute). |
| CSRF | Cookie `XSRF-TOKEN` (readable by JS) + header `X-XSRF-TOKEN` on unsafe methods (Spring Security SPA mode; Angular reads it natively). Bearer-token requests (PAT, CI token) are exempt. |
| API 401 | JSON problem detail, never a redirect. The UI shows a non-blocking "Session expired: log in again / stay" notification. |
| Account check | On every request the session's user is re-read through a cache (TTL 30 s, evicted on change): a deactivated user loses access immediately; all their sessions are deleted on deactivation. |
| Logout | RP-initiated logout: `POST /api/v1/auth/logout` invalidates the session and returns the provider's `end_session_endpoint` URL with `id_token_hint` and `post_logout_redirect_uri`. |
| Providers | Any OIDC provider (`backoffice.auth.oidc.issuer-uri`, client id/secret). Keycloak realm for local development in `deploy/keycloak/realm-back-office.json`. |

## Local accounts

ADR [0021](../adr/0021-local-accounts.md) adds e-mail + password login next to OIDC. Everything after the
login (session, CSRF, timeouts, gate, audit) is the same as for OIDC.

```
Browser ──GET /api/v1/auth/config──────────────────▶ API   methods {local, oidc}, passwordPolicy, XSRF-TOKEN cookie
Browser ──POST /api/v1/auth/login {email,password} (X-XSRF-TOKEN)─▶ API
        ◀── 200 AUTHENTICATED (new session, BO_SESSION, CSRF token rotated)
        ◀── 200 MFA_REQUIRED {mfaToken}  ──POST /api/v1/auth/login/mfa {mfaToken, code|recoveryCode}──▶ API ─▶ AUTHENTICATED
        ◀── 200 PASSWORD_CHANGE_REQUIRED / MFA_ENROLMENT_REQUIRED (restricted session)
        ◀── 401 invalid-credentials (every failure)      ◀── 429 too-many-attempts + Retry-After (per IP)
```

| Aspect | Rule |
|---|---|
| Sign-in method | `User.authMode`: `OIDC` (default; existing users), `LOCAL`, `BOTH`. OIDC login rejects `LOCAL` users (`method-not-allowed`); local login rejects `OIDC` users. The platform setting `localLoginEnabled` (and the hard switch `backoffice.auth.local.enabled`) turns local login off; OIDC is on when an issuer is configured |
| Credential | `identity_local_credential`: Argon2id hash (`{argon2}` in a `DelegatingPasswordEncoder`, re-hashed when parameters change), `mustChangePassword`, failure counter, `lockedUntil`, `lockoutCount`. `NULL` hash = no usable password |
| Policy | 12..128 characters, no composition rules, not among the 10 000 most common passwords, not containing the e-mail local part (>= 3 chars); editable in platform settings |
| Enumeration and timing | Unknown user, wrong password, locked, deactivated and wrong-method all answer the same `401 invalid-credentials`; every attempt runs exactly one hash (a dummy hash for unknown e-mails). Reasons only in the audit log |
| Lockout | 5 consecutive failures (password or MFA) -> 15 min, doubling per further lockout up to 24 h; reset on success or admin unlock |
| Throttle | Per source IP sliding window, 20 attempts / 5 min over `/auth/login`, `/auth/login/mfa`, `POST /auth/password/reset/{token}`; in memory per replica; `429` + `Retry-After` |
| MFA | TOTP (RFC 6238, HMAC-SHA1, 30 s, 6 digits, ±1 step, replay of the last used step rejected); secret AES-GCM encrypted with a key derived from `backoffice.auth.local.secret-key`; 10 single-use recovery codes stored as HMAC. The step token `mfaToken` is HMAC-signed, bound to the user id and a nonce, valid 5 min and consumed on first use (a wrong code means signing in again) |
| MFA required | Platform setting `mfaRequiredForLocal`: a local user without TOTP gets a session restricted to `/me`, `/me/security`, `/me/mfa/**`, `/auth/logout` until enrolment is confirmed |
| Forced change | `mustChangePassword` (bootstrap admin): a session restricted to `/me`, `/me/security`, `/auth/password/change`, `/auth/logout`; the change needs no current password there. Other API calls: `403` `password-change-required` / `mfa-enrolment-required` |
| Links | Invite (`LOCAL`/`BOTH`), auth-mode change and admin reset return `<public-url>/set-password/<token>`: 256-bit token, SHA-256 at rest, 24 h, single use; a reset disables the old password and ends all sessions |
| Session | Created like the OIDC success handler: old session invalidated (fixation), `userId`, `authTime`, `authMethod=LOCAL`, principal index, client metadata; CSRF token rotated. Password change ends every other session of the user |
| Bootstrap | `backoffice.auth.local.bootstrap-admin-email` + `-password`: created as active local `platform-admin` with `mustChangePassword` when no user exists |

Audit codes: `LOGIN_SUCCESS` / `LOGIN_FAILURE` (`details.method` `LOCAL`/`OIDC`; local reasons `unknown-user`,
`bad-password`, `locked`, `method-not-allowed`, `inactive`, `bad-mfa`, `throttled`), `ACCOUNT_LOCKED`,
`ACCOUNT_UNLOCK`, `PASSWORD_CHANGE`, `PASSWORD_RESET_ISSUE`, `PASSWORD_RESET_COMPLETE`, `MFA_ENABLE`,
`MFA_DISABLE`, `MFA_RESET`, `RECOVERY_CODE_USED`, `RECOVERY_CODES_REGENERATE`, `AUTH_MODE_CHANGE`.

| Use case (port) | Authorisation |
|---|---|
| `LocalLogin`, `CompleteMfaLogin`, `PasswordResetLinks.check/complete` | `@PublicOperation` (anonymous; the credential or link is the proof) |
| `ChangePassword`, `ManageMyMfa`, `GetMySecurity` | `@PublicOperation`, self only (session users, not tokens) |
| `PasswordResetLinks.issue`, `AdminLocalAccounts` (auth mode, unlock, MFA reset) | `admin.users.manage` |
| `BootstrapLocalAdmin` | `@SystemOperation` (startup) |

## Users and provisioning

| Field | Notes |
|---|---|
| `id` | UUID, stable |
| `subject`, `issuer` | OIDC `sub` + `iss`; unique pair; null for an invited user who has not logged in yet |
| `email` | unique, case-insensitive; matching key for invitations |
| `displayName` | from `name` / `preferred_username`, editable by admins |
| `status` | `INVITED → ACTIVE ⇄ DEACTIVATED` (a local user becomes `ACTIVE` when the first password is set) |
| `authMode`, `lastLoginMethod` | `LOCAL`, `OIDC`, `BOTH`; `LOCAL` / `OIDC` (see "Local accounts") |
| `groups` | many-to-many |
| `lastLoginAt`, `createdAt`, `createdBy` | |

- **Just-in-time provisioning**: on first login a user record is created (`ACTIVE`) when
  `backoffice.auth.registration = OPEN`; with `INVITE_ONLY` (default) only invited e-mails and e-mails of
  allowed domains (`backoffice.auth.allowed-email-domains`) are accepted, everyone else gets
  `/login?error=not-invited`.
- **Invitations**: an admin creates a user by e-mail with groups and roles (`INVITED`); the first login with
  that e-mail binds `subject` and activates the account. Back Office does not send e-mail: the UI shows the
  sign-in link to copy.
- **Bootstrap**: e-mails listed in `backoffice.auth.bootstrap-admins` receive the `platform-admin` role on
  first login. There is no other way to become the first administrator.
- **Rules**: an admin cannot deactivate themselves or remove the last `platform-admin`.

## Authorisation model

### Scopes

- **Platform** permissions apply to the whole installation (manage users, manage the global task library...).
- **Project** permissions apply to one project (edit workflows of project X, approve gates of project X).

### Grants (platform scope), closest level wins

| Level | Target | Meaning |
|---|---|---|
| `SYSTEM` | everyone authenticated | defaults |
| `GROUP` | a group | overrides SYSTEM |
| `USER` | a user | overrides GROUP and SYSTEM |

A grant is `(permission, effect: ALLOW|DENY, level, target)`. Resolution per permission: take the grants
of the highest level present for the user (USER > any of the user's groups > SYSTEM); within the GROUP
level `DENY` wins over `ALLOW`. No grant anywhere = denied. Roles are named bundles of permissions;
assigning a role to a group or user at a level is equivalent to `ALLOW` grants for every permission in the
role at that level. The admin UI shows the table **System | Group(s) | User | Effective** per permission.

### Project memberships

`ProjectMembership(projectId, principal: USER(id) | GROUP(id), projectRole)`. Effective project
permissions = union of the project roles of the user's direct and group memberships. Holders of the
platform permission `admin.projects.all` get every project permission everywhere (auditors get read-only
equivalents through the `auditor` role).

### Permission catalogue (source of truth: `*Permissions` objects in each core module)

| Key | Scope | Module | Meaning |
|---|---|---|---|
| `admin.users.view` | PLATFORM | identity | list users, groups, memberships |
| `admin.users.manage` | PLATFORM | identity | invite, edit, deactivate users; manage groups |
| `admin.roles.manage` | PLATFORM | identity | edit roles and platform grants |
| `admin.audit.view` | PLATFORM | identity | read the audit log |
| `admin.projects.all` | PLATFORM | identity | every project permission on every project |
| `admin.settings.manage` | PLATFORM | identity | platform settings (registration mode, domains) |
| `tokens.personal` | PLATFORM | identity | create own personal access tokens |
| `projects.create` | PLATFORM | projects | create projects (creator becomes OWNER); built-in roles `project-manager` and `platform-admin` (ADR 0028) |
| `projects.create.personal` | PLATFORM | projects | create private projects of your own (only the creator is a member, adding members is refused); every member (ADR 0038, identity migration V410) |
| `integrations.view` | PLATFORM | projects | see integration status and run connection tests; list platform connections (ADR 0030) |
| `admin.connections.manage` | PLATFORM | projects | create, edit, rotate, verify and delete platform connections (Administration → Connections, ADR 0030); see the metadata of personal connections (ADR 0035) |
| `connections.personal` | PLATFORM | projects | create and use own personal connections (My account → Connections, ADR 0035); a personal connection is used only with its owner's approval or run start and is invisible to everybody else |
| `library.view` | PLATFORM | tasks | read the global task library |
| `library.manage` | PLATFORM | tasks | create/edit global templates, interfaces, skills |
| `models.view` | PLATFORM | evaluation | read model profiles |
| `models.manage` | PLATFORM | evaluation | create/edit model profiles and pricing, test a profile |
| `evaluation.view` | PLATFORM | evaluation | read suites, benchmarks, leaderboards |
| `evaluation.run` | PLATFORM | evaluation | create suites/tasks, create/start/cancel benchmarks |
| `usage.view` | PLATFORM | llm | read the usage ledger for all projects and benchmarks |
| `fleet.view` | PLATFORM | runner | see runner pools, runners and agent jobs (ADR 0024) |
| `fleet.manage` | PLATFORM | runner | create/edit/delete runner pools, issue registration tokens, revoke runners, cancel agent jobs |
| `fleet.personal` | PLATFORM | runner | your own personal runner pool under My account: connect your computer, disconnect it, choose GATEWAY or LOCAL_LOGIN; every member (ADR 0040, identity migration V411) |
| `project.view` | PROJECT | projects | see the project, its workflows, runs, CI executions |
| `project.manage` | PROJECT | projects | edit project settings, CI entrypoints; deleting the project also needs `project.members.manage` (owners only) |
| `project.members.manage` | PROJECT | identity | add/remove members and change their project roles |
| `project.ci.manage` | PROJECT | projects | create/revoke CI tokens |
| `project.connections.manage` | PROJECT | projects | create, edit, rotate, verify and delete the project's connections (ADR 0030); listing them needs only `project.view` |
| `workflow.edit` | PROJECT | projects | create/edit/delete/duplicate workflows |
| `run.start` | PROJECT | projects | start and retry runs |
| `run.cancel` | PROJECT | projects | cancel runs |
| `run.approve` | PROJECT | projects | decide human gates (subject to the gate's own approver rule) |
| `run.assign` | PROJECT | projects | assign or reassign who decides an open human gate (ADR 0028) |
| `run.signal` | PROJECT | projects | send a manual CI signal to a waiting run |
| `library.project.manage` | PROJECT | tasks | create/edit project-scoped templates and skills |
| `project.usage.view` | PROJECT | llm | usage and cost of this project |
| `ci.execute` | PROJECT | projects | granted only to CI tokens: start CI executions |
| `ci.signal` | PROJECT | projects | granted only to CI tokens: send signals |

The governance context (ADR 0025: attestations, delivery insights, MCP server) adds no keys: attestations and
insights need `project.view`; MCP tools check the permission of the use case they call (docs/architecture/11-governance.md).
The MCP endpoint `/mcp` accepts personal access tokens only.

### Built-in roles (seeded, editable, not deletable)

| Role | Scope | Permissions |
|---|---|---|
| `platform-admin` | PLATFORM | all platform permissions incl. `admin.projects.all` and `admin.connections.manage` |
| `member` (SYSTEM default) | PLATFORM | `library.view`, `models.view`, `evaluation.view`, `tokens.personal`, `integrations.view`, `connections.personal`, `projects.create.personal`, `fleet.personal` (no `projects.create` since ADR 0028) |
| `project-manager` | PLATFORM | `member` + `projects.create`, `usage.view` |
| `evaluator` | PLATFORM | `member` + `evaluation.run` |
| `library-curator` | PLATFORM | `member` + `library.manage` |
| `auditor` | PLATFORM | `admin.users.view`, `admin.audit.view`, `usage.view`, `library.view`, `models.view`, `evaluation.view` |
| `owner` | PROJECT | every project permission except `ci.*` (incl. `run.assign`, `project.connections.manage`) |
| `maintainer` | PROJECT | `owner` minus `project.members.manage`, `project.connections.manage` (credentials stay with owners, ADR 0030) and project deletion (`project.manage` still allows settings); keeps `run.assign` |
| `developer` | PROJECT | `project.view`, `workflow.edit`, `run.start`, `run.cancel`, `run.signal`, `project.usage.view` |
| `approver` | PROJECT | `project.view`, `run.approve`, `project.usage.view` |
| `viewer` | PROJECT | `project.view` |

Upgrades: built-in roles are stored and never overwritten by seeding. Seeding creates missing built-ins
(`project-manager` on an older install, audited `ROLE_CREATE`); the identity migration V406 removed
`projects.create` from the stored `member`, `evaluator`, `library-curator` and added `run.assign` to the stored
`owner` and `maintainer` (audited `ROLE_UPDATE` by `system`). The identity migration V408 adds
`project.connections.manage` to the stored `owner` only (ADR 0030; audited the same way); `platform-admin`
receives `admin.connections.manage` as one of all platform permissions. The identity migration V409 adds
`connections.personal` to the stored `member`, `project-manager`, `evaluator` and `library-curator` (ADR 0035;
audited the same way) and the platform setting `specKitAllowed` (ADR 0036, default true; read by other contexts
through the shared-kernel port `PlatformFeatures`).

### Operating model: who creates projects, who assigns steps (ADR 0028)

- **Project managers create projects.** Only holders of `projects.create` (built-in `project-manager`,
  `platform-admin`, or a custom grant) can create a project; the creator becomes its `owner` and adds the
  team with project roles (`project.members.manage`). Ordinary members work in the projects they are added to.
- **The workflow says who decides each step** (gate approver rules below, edited in the designer as
  "Who decides this step").
- **Owners and maintainers assign a waiting step** (`run.assign`): `PUT /approvals/{id}/assignees` names the
  users who decide this open approval, for this run only; the roles rule and four-eyes still apply and
  every assignee must be able to decide (`run.approve`). `GET /approvals/{id}/assignee-candidates` lists the
  project members who qualify. The assignees see the gate in **Your turn → Needs you**.

### Gate approval rules (RACI on gates)

A `HUMAN_GATE` node may declare:

| Field | Meaning |
|---|---|
| `approvers.projectRoles` | project roles allowed to decide (default: any holder of `run.approve`) |
| `approvers.users` | explicit user ids; when set, only they decide (they also need one of `projectRoles`, if set) |
| `approvers.accountable` | the single accountable project role shown in the inbox ("A" in RACI) |
| `approvers.forbidSelfApproval` | the user who started the run cannot approve it (default `false`) |
| `checklist[]` | Definition-of-Ready/Done items `{id, text, required}`; `approve` needs all required items ticked |

A run-time **assignment** (`approval_request.assignment`: user ids, `assignedBy`, `assignedAt`, comment; ADR 0028)
replaces `approvers.users` for that approval only (`GateApprovalPolicy`: rules 1, 2 and 4 are `canBeAssigned`,
rule 3 checks the assignment if there is one, else `approvers.users`). It is audited as `APPROVAL_ASSIGN`
(old and new user ids) and published as `ApprovalAssigned`.

Decisions record `decidedBy` (actor), ticked checklist items and the comment; they are audited as
`RUN_GATE_DECIDED`.

## Tokens for machines

| Token | Prefix | Scope | Created by |
|---|---|---|---|
| Personal access token (PAT) | `bo_pat_` | the owner's effective permissions, optionally narrowed to a list of permissions; expiry required (max 365 days) | the user (`tokens.personal`) |
| Project CI token | `bo_ci_` | `ci.execute`, `ci.signal` on one project | project member with `project.ci.manage` |
| Runner registration token | `bo_rrt_` | single use, 24 h: registers one runner-controller in one pool | operator with `fleet.manage`; for their own personal pool, the user with `fleet.personal` (ADR 0040) |
| Runner token | `bo_rt_` | runner protocol v2 (`/runner/v2/**`) for the jobs of its pool and its own leases; revocable | issued at registration |
| Job token | `bo_job_` | one agent job: sandbox callbacks, uploads, the LLM proxy (`/llm/**`) with the job's model allowlist; expires with the lease / terminal state | issued at lease time |

All are sent as `Authorization: Bearer ...` (the LLM proxy also accepts `x-api-key`), stored as SHA-256 and
shown once. PATs and CI tokens are verified by the identity filter; `bo_rt_` / `bo_job_` by the filters of
`agent-runner` and `llm-gateway` (the security chain only permits those paths). A token of one kind is refused
on the endpoints of another.

## Audit log

| Column | Notes |
|---|---|
| `event_time`, `id` | primary key `(event_time, id)`, doubles as keyset cursor |
| `actor_kind`, `actor_id`, `actor_name` | from the security context |
| `operation` | text code (`USER_INVITE`, `USER_DEACTIVATE`, `GRANT_CHANGE`, `MEMBER_ADD`, `WORKFLOW_UPDATE`, `RUN_START`, `RUN_GATE_DECIDED`, `APPROVAL_ASSIGN`, `CI_TOKEN_CREATE`, `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `LOGOUT`, `ACCESS_DENIED`, `AUDIT_VIEW`...) |
| `entity_type`, `entity_id`, `project_id` | |
| `result` | `SUCCESS`, `DENIED`, `FAILURE` |
| `details` | jsonb, sanitised (control characters stripped, truncated at 16 kB with a flag) |
| `correlation_id`, `source_ip`, `user_agent` | correlation id generated by the server for every request |

Rules: the write-only `AuditPort` writes in the caller's transaction; audit write failures never break the
business operation (they are logged on the `AUDIT` logger and counted); reading the audit log is itself
audited (`AUDIT_VIEW`).

## Where it lives

| Module | Content |
|---|---|
| `shared-kernel` (`shared.security`, `shared.audit`) | `AuthorizationGate`, `CurrentUser`, `Actor`, `ProjectFilter`, annotations, `PermissionDefinition`, `PermissionCatalogContribution`, `ProjectAccessProvisioner`, `ProjectAccessDirectory` (ADR 0028), `AuditPort`; test fixtures `FakeAuthorizationGate`, `RecordingAuditPort` |
| `identity-core` | users, groups, roles, grants, memberships, tokens, invitations; `PermissionResolver`; use cases; local accounts (`LocalAccounts.kt`, `Totp.kt`, `LocalAccountServices.kt`) |
| `identity-adapters` | OIDC login/BFF, local login (`LocalAuthController`, `LoginSessions`, `LoginThrottleFilter`, Argon2/AES-GCM/HMAC adapters), session handling, token filters, `AuthorizationGate` implementation, audit log store and search, correlation id filter, REST for `/me`, `/auth`, `/admin/**`, `/projects/{id}/members`, `/tokens` |
| every other context | a `*Permissions` object in core + a `PermissionCatalogContribution` bean in adapters config; `gate.require(...)` at the top of every use case; audit entries for mutations |
