# Authentication, users and access

## When to use this guide

You install or administer Back Office for more than one person: you need the first administrator, want to
invite colleagues, organise them in groups, decide who may do what on the platform and in which projects,
hand out personal access tokens to scripts, read the audit log, run Back Office **without an identity
provider** using local accounts (e-mail + password, optional TOTP; the Helm chart's default), or connect
an external OIDC identity provider (Keycloak, Entra ID, Google, ...; the chart does not deploy one, ADR 0039). The design is in [08-identity-and-access.md](../architecture/08-identity-and-access.md)
and ADRs [0016](../adr/0016-oidc-bff-authentication.md), [0017](../adr/0017-deny-by-default-authorization.md),
[0018](../adr/0018-transactional-audit-log.md), [0021](../adr/0021-local-accounts.md).

## How it fits together

```
Browser ── /oauth2/authorization/oidc?returnTo=/projects ──▶ API ──302──▶ identity provider (Keycloak, Entra ID, Google...)
provider ── /login/oauth2/code/oidc?code=... ──▶ API: code exchange (PKCE), user resolved/provisioned,
                                                 server-side session, cookie BO_SESSION ──302──▶ returnTo
Browser ── POST /api/v1/auth/login {email, password} (+ /auth/login/mfa) ──▶ API: same server-side session
Browser ── /api/v1/** with BO_SESSION (+ X-XSRF-TOKEN on writes) ──▶ API: permissions resolved per request
Scripts ── Authorization: Bearer bo_pat_... ──▶ API        CI ── Authorization: Bearer bo_ci_... ──▶ API
```

- Every user has a **sign-in method** (`authMode`): `OIDC` (the provider only, the default for existing
  users), `LOCAL` (e-mail + password in Back Office) or `BOTH`. For OIDC users Back Office stores no
  password; password policy, MFA and federation are the provider's job. For local users Back Office
  stores an Argon2id hash, applies its own policy, lockout and throttling, and offers TOTP (section 11).
- **Authorisation is decided by Back Office**, inside every use case (deny by default). The provider only
  says who you are (and optionally which groups you are in).
- Permissions are resolved on every request (cached for at most 30 s and invalidated on every access
  change), so revoking access takes effect on the next request.

## Configuration (`backoffice.auth.*`)

| Property (env var) | Default | Meaning |
|---|---|---|
| `oidc.issuer-uri` (`OIDC_ISSUER_URI`) | blank | Issuer of the provider. **Blank = OIDC login off** (only bearer tokens work). Local profile: `http://localhost:8180/realms/back-office` |
| `oidc.client-id` / `oidc.client-secret` (`OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET`) | `back-office` / blank | Confidential client for the authorization-code flow |
| `oidc.scopes` | `openid,profile,email` | Requested scopes |
| `oidc.provider-name`, `oidc.account-url` | `Keycloak`, derived | Shown on the login page; account page for password/MFA (derived for Keycloak issuers) |
| `registration` (`BACKOFFICE_REGISTRATION`) | `INVITE_ONLY` | `OPEN`: anyone the provider authenticates gets an account. Default until an admin saves platform settings |
| `allowed-email-domains` | empty | With `INVITE_ONLY`, e-mails of these domains may still self-register |
| `bootstrap-admins` (`BACKOFFICE_BOOTSTRAP_ADMINS`) | empty | Comma list of e-mails that receive `platform-admin` on their **first** login |
| `groups-claim` | `groups` | Claim holding group names (for group synchronisation) |
| `sync-groups` | `false` | Default of the setting "synchronise groups from the identity provider" |
| `personal-token-max-days` | `90` | Default maximum lifetime of personal tokens (hard limit 365) |
| `session.idle-timeout` | `PT30M` | Session ends after this much inactivity |
| `session.absolute-timeout` | `PT12H` | Session ends this long after login, active or not |
| `cookie-secure` (`BACKOFFICE_COOKIE_SECURE`) | `true` | `Secure` flag of `BO_SESSION`; `false` only for plain-HTTP development (the `local` profile) |
| `local.enabled` (`BACKOFFICE_AUTH_LOCAL_ENABLED`) | `true` | Local accounts on/off. Hard switch: the platform setting "local login" can only narrow it |
| `local.secret-key` (`BACKOFFICE_AUTH_LOCAL_SECRET_KEY`) | blank (`local` profile: a fixed dev key) | Base64 of 32 bytes (`openssl rand -base64 32`). Encrypts TOTP secrets, keys recovery-code hashes, signs MFA step tokens. **Required while local login is enabled** - the API does not start without it. The Helm chart generates one |
| `local.bootstrap-admin-email` / `-password` (`BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD`) | blank (`local` profile: `owner@example.com` / `ChangeMe-Local-123`) | First platform admin **without an identity provider**; created only when no user exists, must change the password at the first login |
| `local.reset-link-validity` | `PT24H` | Lifetime of set-password and reset links |
| `local.lockout.max-failures` / `local.lockout.lock-duration` | `5` / `PT15M` | Defaults of the platform lockout setting |
| `local.throttle.max-attempts` / `local.throttle.window` | `20` / `PT5M` | Per source IP (and per API replica): sign-in attempts before `429` |

Also relevant: `backoffice.public-url` (where the provider sends you after logout) and
`server.forward-headers-strategy: framework` (already set) so that the API behind a TLS-terminating ingress
builds `https://` redirect URIs and marks cookies `Secure`.

The registration mode, allowed domains, group synchronisation, token lifetime, local login, "MFA required
for local accounts", the password policy and the lockout can later be changed in **Administration →
Settings** (`PUT /api/v1/admin/settings`); saved settings win over the properties. Switching local login off
is refused (`409`) while no identity provider is configured - nobody could sign in any more.

## 1. The first administrator

1. Put your e-mail into `BACKOFFICE_BOOTSTRAP_ADMINS` (in Helm, the chart value that maps to this variable), e.g.
   `ops@example.com`. It works even with `INVITE_ONLY`.
2. Make sure the provider has a user with that e-mail and a **verified** e-mail address.
3. Open Back Office and sign in. On this first login you receive the `platform-admin` role at user level
   (audited as `ROLE_ASSIGN` with reason `bootstrap-admin`).

There is no other way to become the first administrator. The bootstrap list is only read on a user's first
login: if an administrator later removes the role, the next login does not bring it back. Once you have a
second administrator you may remove your address from the list.

Locally (`--spring.profiles.active=local`) `admin@example.com` / `admin` from the development realm
(`deploy/keycloak/realm-back-office.json`) is the bootstrap admin and registration is `OPEN`.

**Without an identity provider** set `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` and
`BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_PASSWORD` instead (see section 11.7). On a fresh database the local
profile creates `owner@example.com` / `ChangeMe-Local-123` this way.

## 2. Inviting users

**Administration → Users → Invite user** (`POST /api/v1/admin/users`, needs `admin.users.manage`):
enter the e-mail, optionally a display name, groups, platform roles (also needs `admin.roles.manage`) and
initial project memberships (needs `project.members.manage` on each project).

- The user is created as `INVITED`. Back Office sends no e-mail: copy the **sign-in link** shown in the UI
  (`signInUrl`, i.e. `<public-url>/login`) and send it yourself.
- The first login with that e-mail (as a verified address at the provider) binds the account to the
  provider identity (`issuer` + `subject`) and activates it. Later logins match by identity, so an e-mail
  change at the provider is picked up automatically.
- Inviting an e-mail that already exists answers `409`.
- Choose the **sign-in method** when inviting (`authMode`; default `OIDC` when a provider is configured,
  otherwise `LOCAL`). A `LOCAL` or `BOTH` invitation returns a one-time **set-password link**
  (`setPasswordUrl`) instead - see section 11.
- A login with an e-mail that is neither invited nor allowed ends on `/login?error=not-invited`.

## 3. Groups

**Administration → Groups** (`/api/v1/admin/groups`). A group has members, optional platform roles and an
optional **external group** name. When "synchronise groups from the identity provider" is on, each login
sets the user's membership of every group that has an external group from the provider's `groups` claim
(`architects` and `/architects` both match); groups without an external group are never touched.
A group that is still a member of projects cannot be deleted (`409`): remove the memberships first.

## 4. Roles and the grants table

Permissions come from code: every module contributes its keys (`GET /api/v1/admin/permissions` lists the
catalogue). A **role** is a named bundle of permissions with a scope:

| Role | Scope | Permissions |
|---|---|---|
| `platform-admin` | PLATFORM | all platform permissions incl. `admin.projects.all` |
| `member` (SYSTEM default) | PLATFORM | `library.view`, `models.view`, `evaluation.view`, `tokens.personal`, `integrations.view` |
| `project-manager` | PLATFORM | `member` + `projects.create`, `usage.view` (ADR 0028: managers create projects) |
| `evaluator` | PLATFORM | `member` + `evaluation.run` |
| `library-curator` | PLATFORM | `member` + `library.manage` |
| `auditor` | PLATFORM | `admin.users.view`, `admin.audit.view`, `usage.view`, `library.view`, `models.view`, `evaluation.view` |
| `owner` | PROJECT | every project permission except `ci.*` (incl. `run.assign`: reassign a waiting gate) |
| `maintainer` | PROJECT | `owner` without `project.members.manage` (so a maintainer can edit the project but not delete it) |
| `developer` | PROJECT | `project.view`, `workflow.edit`, `run.start`, `run.cancel`, `run.signal`, `project.usage.view` |
| `approver` | PROJECT | `project.view`, `run.approve`, `project.usage.view` |
| `viewer` | PROJECT | `project.view` |

Built-in roles are seeded on first start, can be edited (`admin.roles.manage`) but not deleted. Custom
roles can be created; unknown permission keys and keys of the wrong scope are rejected (`400`). A role that
is still assigned cannot be deleted (`409`).

**Platform grants** (`/api/v1/admin/grants`, `admin.roles.manage`) are set on three levels:

| Level | Target | Holds |
|---|---|---|
| SYSTEM | every authenticated user | roles + individual ALLOW/DENY (default: role `member`) |
| GROUP | one group | roles + ALLOW/DENY |
| USER | one user | roles + ALLOW/DENY |

Per permission, **the closest level that says anything wins** (USER > GROUP > SYSTEM); within one level DENY
beats ALLOW (also across all of a user's groups); a role counts as ALLOW for each of its permissions;
nothing anywhere means denied. **Users → a user → Effective permissions**
(`GET /api/v1/admin/users/{id}/effective-permissions`) shows the table:

| Permission | System | Group(s) | User | Effective |
|---|---|---|---|---|
| `library.manage` | NONE | Curators: ALLOW | NONE | yes (GROUP:Curators) |
| `library.view` | ALLOW | NONE | DENY | no (USER) |
| `usage.view` | NONE | NONE | NONE | no (denied by default) |

Every changed permission is audited as one `GRANT_CHANGE` entry; role assignments as `ROLE_ASSIGN` /
`ROLE_UNASSIGN`.

## 5. Project members

**Project → Members** (`/api/v1/projects/{id}/members`). Anyone with `project.view` sees the member list;
adding, changing and removing members needs `project.members.manage` (owners). Members are users or groups
with a project role; a user's project permissions are the union over their direct and group memberships.
The creator of a project becomes its `owner` automatically. **The last owner can be neither removed nor
demoted** (`409`) — add another owner (a user or a group) first. Holders of `admin.projects.all`
(platform admins) have every project permission everywhere without being members.

Projects created before identity existed have no owner yet: a platform admin adds the first owner.

**Deleting a project** is irreversible (runs, approvals, CI history) and needs both `project.manage` and
`project.members.manage` on it, i.e. owners (and platform admins); maintainers cannot delete. A project with
active runs cannot be deleted (`409`) until they are cancelled.

## 6. Personal access tokens

**My account** (`/account`) **→ Personal access tokens** (`/api/v1/tokens`, needs `tokens.personal`, granted by `member`):

- Give a name, a lifetime (1 … `personalTokenMaxDays`, at most 365 days) and optionally a permission subset.
  The token (`bo_pat_` + 40 characters) is shown **once**; only its SHA-256 hash is stored.
- A token acts with **its owner's current permissions**, narrowed to the subset when one is given. It can
  only carry permissions the owner holds. A narrowed token keeps "all projects" access only if the subset
  contains `admin.projects.all`.
- Use: `curl -H "Authorization: Bearer bo_pat_..." https://backoffice.example.com/api/v1/me`. Bearer requests
  need no CSRF header.
- Tokens cannot create tokens. You can always revoke your own tokens, even after losing `tokens.personal`.
- Deactivating a user revokes all their tokens.

Project CI tokens (`bo_ci_`) are managed per project (see [ci-integration.md](ci-integration.md)).

## 7. Sessions and timeouts

- The session lives in PostgreSQL (`SPRING_SESSION`, Spring Session JDBC); the browser holds only the
  `BO_SESSION` cookie (HttpOnly, Secure, SameSite=Lax) and `XSRF-TOKEN` (read by the UI and sent back as
  `X-XSRF-TOKEN` on writes).
- **Idle timeout** (30 min) and **absolute timeout** (12 h from login). `GET /api/v1/auth/session` tells the UI
  how long is left and does not itself extend the session.
- On every request the user is re-checked: a deactivated user loses access immediately.
- **Logout** (`POST /api/v1/auth/logout`) ends the Back Office session and returns the provider's end-session
  URL, so the UI also ends the provider session (for a local session `logoutUrl` is empty).
- Administrators can list and terminate a user's sessions (**Users → a user → Sessions**,
  `GET/DELETE /api/v1/admin/users/{id}/sessions`).

## 8. Deactivating users

**Users → a user → Deactivate user** (`POST /api/v1/admin/users/{id}/deactivate`): the status becomes
`DEACTIVATED`, all sessions are deleted and all personal tokens revoked; the next request of that user is
answered with 401 and their next login ends on `/login?error=deactivated`. Rules: you cannot deactivate
yourself, and the last active platform admin can neither be deactivated nor lose the role (`409`).
**Activate user** restores the account (`INVITED` again if the user never logged in). Nothing is deleted;
history keeps the user's name.

## 9. Audit log

**Administration → Audit log** (`GET /api/v1/admin/audit`, needs `admin.audit.view`) lists who did what,
newest first, filterable by time, actor, operation, entity, project, result and correlation id, with
keyset paging (`nextCursor`). Recorded:

- every access change (`USER_INVITE`, `USER_UPDATE`, `USER_DEACTIVATE`, `GROUP_*`, `ROLE_*`, `GRANT_CHANGE`,
  `MEMBER_ADD`/`MEMBER_ROLE_CHANGE`/`MEMBER_REMOVE`, `PAT_CREATE`/`PAT_REVOKE`, `SETTINGS_UPDATE`), written in
  the same transaction as the change;
- authentication events (`LOGIN_SUCCESS`, `LOGIN_FAILURE` with `method` and `reason`, `LOGOUT`, `SESSION_EXPIRED`);
- local-account events (`ACCOUNT_LOCKED`, `ACCOUNT_UNLOCK`, `PASSWORD_CHANGE`, `PASSWORD_RESET_ISSUE`,
  `PASSWORD_RESET_COMPLETE`, `MFA_ENABLE`, `MFA_DISABLE`, `MFA_RESET`, `RECOVERY_CODE_USED`,
  `RECOVERY_CODES_REGENERATE`, `AUTH_MODE_CHANGE`) - never with a password, token or code;
- every refused request (`ACCESS_DENIED`, with the permission and the URL);
- other contexts' mutations (`WORKFLOW_UPDATE`, `RUN_START`, `RUN_GATE_DECIDED`, ...);
- reading the audit log itself (`AUDIT_VIEW`).

Every response carries `X-Correlation-Id`; search the log (and the application logs, MDC `correlationId`)
by it when a user reports a problem. Each entry is also logged on the `AUDIT` logger. The table grows
without bound: archive/partition by month (`(event_time, id)` key) as an operational task.

## 10. Connecting another OIDC provider

Any OpenID Connect provider with discovery works. Register a **confidential web client** with the
authorization-code flow and redirect URI `https://<your-host>/login/oauth2/code/oidc`, allow
`https://<your-host>/` as post-logout redirect, then set `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`,
`OIDC_CLIENT_SECRET` (and `OIDC_PROVIDER_NAME` for the login page).

| Provider | Issuer | Notes |
|---|---|---|
| Keycloak | `https://kc.example.com/realms/<realm>` | Add a "Group Membership" mapper (claim `groups`, full path off) for group sync |
| Microsoft Entra ID | `https://login.microsoftonline.com/<tenant-id>/v2.0` | App registration → Web platform; `email` needs the optional claim or an `email` on the account; groups claim emits **object ids** — use them as external group names, set `OIDC_GROUPS_CLAIM=groups` |
| Google | `https://accounts.google.com` | OAuth client "Web application"; no groups claim (leave sync off); restrict with `allowed-email-domains` or invitations |
| GitLab | `https://gitlab.example.com` | Application with scopes `openid profile email`; groups in claim `groups_direct` |

Users are matched by `issuer` + `subject`, so switching providers later means re-binding: existing users
keep their e-mail and can be invited again only after an administrator clears the old binding (not yet
exposed in the UI; see the runbook for `identity-conflict`).

## 11. Local accounts (e-mail + password)

Use local accounts for installations without Keycloak, for service or break-glass accounts, and for
external collaborators who are not in your directory (ADR [0021](../adr/0021-local-accounts.md)). A local
login creates exactly the same session as an OIDC login: timeouts, CSRF, permissions and audit are identical.

### 11.1 Creating a local user

**Users → Invite user** with sign-in method **Local** (or **Both** for someone who may use either):
`POST /api/v1/admin/users` with `"authMode": "LOCAL"`. The response contains `setPasswordUrl`
(`<public-url>/set-password/<token>`). Back Office sends no e-mail: hand the link over yourself through a
channel you trust. The user opens it, chooses a password (the page shows the policy) and is signed in; the
account becomes `ACTIVE`.

An existing user can be switched with **Users → a user → Sign-in method**
(`PUT /api/v1/admin/users/{id}/auth`). Switching to `LOCAL`/`BOTH` without a password returns a
`setPasswordUrl`; switching to `OIDC` deletes the password, the authenticator and the recovery codes.
Removing a method ends the user's sessions. The change is refused (`409`) when it would leave no platform
administrator able to sign in.

### 11.2 Set-password links and resets

- A link is 256 bits of randomness, stored only as a hash, valid **24 hours** (`local.reset-link-validity`)
  and usable **once**. Issuing a new link invalidates the previous one.
- **Users → a user → Issue set-password link** (`POST /api/v1/admin/users/{id}/password-reset`) returns a new link,
  **immediately** disables the current password and terminates all the user's sessions.
- `GET /api/v1/auth/password/reset/{token}` validates a link (404 when unknown, used or expired);
  `POST` with `newPassword` sets it. If the user has TOTP, the answer is `MFA_REQUIRED` and they enter a code next.
- Users change their own password in **My account → Security** (`POST /api/v1/auth/password/change`, current
  password required); all their other sessions end.

### 11.3 Password policy

12 to 128 characters, no composition rules (NIST SP 800-63B); rejected are the 10 000 most common passwords
(bundled list, case-insensitive) and passwords containing the local part of the user's e-mail
(`jan.kowalski` in `my-jan.kowalski-pw`, when that part has at least 3 characters). Minimum length and both
checks are editable in **Platform settings → Password policy** (minimum 8..128, maximum at most 128). Passwords are
hashed with Argon2id; outdated parameters are upgraded on the next successful login.

### 11.4 Two-factor authentication (TOTP)

- Users enrol in **My account → Security → Set up two-factor authentication**: scan the QR code (or type the secret)
  into any authenticator app, confirm with a current 6-digit code, and store the **10 recovery codes**
  shown once (`xxxx-xxxx-xx`, each usable once). They can regenerate the codes or switch TOTP off with the
  current password.
- **Platform settings → Require MFA for local accounts** (`mfaRequiredForLocal`): local users without an
  authenticator are signed in with a restricted session at their next login (`MFA_ENROLMENT_REQUIRED`) and
  can do nothing but enrol; switching TOTP off is then refused.
- **Lost device**: the user signs in with a recovery code. Without codes an administrator uses
  **Users → a user → Reset MFA** (`DELETE /api/v1/admin/users/{id}/mfa`, audited `MFA_RESET`); verify the
  person's identity out of band first. With "MFA required" they must enrol again at the next login.
- TOTP only protects local sign-in. OIDC users configure MFA at the identity provider.

### 11.5 Lockout, throttling and unlock

- **5 consecutive failures** (wrong password or wrong MFA code) lock the account for **15 minutes**; every
  further lockout doubles the duration up to 24 hours; a successful login or an unlock resets the counter
  (`ACCOUNT_LOCKED` in the audit log). The limits are editable in **Platform settings → Lockout**.
- A locked account gets the same `401 invalid-credentials` as a wrong password (nothing tells an attacker
  that it exists). Administrators see `lockedUntil` on the user and clear it with **Unlock**
  (`POST /api/v1/admin/users/{id}/unlock`, `ACCOUNT_UNLOCK`).
- Independently, each source IP may make **20 sign-in attempts per 5 minutes** on `/auth/login`,
  `/auth/login/mfa` and set-password links; above that the API answers `429 too-many-attempts` with
  `Retry-After` (seconds). The counter is kept in memory per API replica.
- Unknown e-mail, wrong password, deactivated account, locked account and an account that may not sign in
  locally all return the same `401` after the same amount of hashing work; the real reason is only in the
  audit log (`LOGIN_FAILURE`, `details.reason` = `unknown-user`, `bad-password`, `locked`,
  `method-not-allowed`, `inactive`, `bad-mfa`, `throttled`).

### 11.6 What the login page does

`GET /api/v1/auth/config` returns `methods.local` / `methods.oidc` and the password policy (and sets the
`XSRF-TOKEN` cookie that `POST /api/v1/auth/login` needs). `POST /auth/login` answers one of
`AUTHENTICATED`, `MFA_REQUIRED` (with a 5-minute, single-use `mfaToken` for `POST /auth/login/mfa`),
`PASSWORD_CHANGE_REQUIRED` or `MFA_ENROLMENT_REQUIRED`. In the last two cases the session is restricted:
other API calls answer `403` with `code` `password-change-required` / `mfa-enrolment-required`.

### 11.7 Bootstrap without an identity provider

1. Leave `auth.oidc.issuerUri` blank (Helm) / `BACKOFFICE_AUTH_OIDC_ISSUER_URI` empty, keep local login enabled
   and provide `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` (the chart generates it).
2. Set `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` and `BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_PASSWORD`
   (Helm: `auth.local.bootstrapAdminEmail` / `bootstrapAdminPassword`, stored in the app Secret).
3. Start the API on an **empty** database: it creates that user as an active local `platform-admin`
   (audited `USER_PROVISION` + `ROLE_ASSIGN` with reason `bootstrap-local-admin`) and logs a warning.
4. Sign in; you must change the password before anything else. Then remove the two variables. They are
   ignored as soon as any user exists.

## Where to look when it fails

- Login problems: [runbooks/cannot-log-in.md](../runbooks/cannot-log-in.md).
- `LOGIN_FAILURE`, `ACCESS_DENIED` and `SESSION_EXPIRED` entries in **Administration → Audit log** (the reason is in
  `details`), found by the `X-Correlation-Id` of the failing response.
- Logs: `pl.mos.backoffice.identity` (login, sessions), `AUDIT` (every audit entry),
  `org.springframework.security` at `DEBUG` for the filter chain.
- Code: `identity-core` (rules and use cases; local accounts in `LocalAccountServices.kt`, `LocalAccounts.kt`,
  `Totp.kt`), `identity-adapters` (`IdentitySecurityConfiguration`, `PrincipalFilter`, `OidcLoginSuccessHandler`,
  `LocalAuthController`, `LoginSessions`, `LoginThrottleFilter`, `LocalAuthAdapters.kt`, `JdbcAuditLog`).
