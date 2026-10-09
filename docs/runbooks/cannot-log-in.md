# Runbook: a user cannot log in (or keeps getting logged out)

## When to use this runbook

Someone cannot get into Back Office: the browser bounces between Back Office and the identity provider,
ends on `/login?error=...`, the UI says "Session expired" right after login, every write fails with 403,
or scripts get 401. Background: [authentication-and-users.md](../guides/authentication-and-users.md).

## How a login is supposed to go

1. The UI calls `GET /api/v1/me` → 401 → full-page navigation to `/oauth2/authorization/oidc?returnTo=...`.
2. The API redirects (302) to the provider's authorization endpoint (with PKCE and `redirect_uri =
   <public-url>/login/oauth2/code/oidc`).
3. After the provider login it redirects back to `/login/oauth2/code/oidc?code=...&state=...`; the API
   exchanges the code, validates the ID token, resolves the user (`LOGIN_SUCCESS` / `LOGIN_FAILURE` in the
   audit log), stores the user id in the server-side session and sets `BO_SESSION`.
4. 302 to `returnTo`; from now on `/api/v1/me` answers 200.

A **local** login (ADR 0021) instead posts `{email, password}` to `POST /api/v1/auth/login` (with the
`XSRF-TOKEN` cookie from `GET /api/v1/auth/config` and the `X-XSRF-TOKEN` header). It answers
`AUTHENTICATED` (session created), `MFA_REQUIRED` (then `POST /api/v1/auth/login/mfa` with the code),
`PASSWORD_CHANGE_REQUIRED` or `MFA_ENROLMENT_REQUIRED`, or `401 invalid-credentials` for every failure.
The real reason of a failure is only in the audit log: `LOGIN_FAILURE` with `details.method = LOCAL` and
`details.reason` (sections 11-16).

First checks for every case: the `X-Correlation-Id` of the failing response, **Administration → Audit log**
filtered by `LOGIN_FAILURE` or that correlation id, and the API log (`pl.mos.backoffice.identity`,
`org.springframework.security`).

## Symptoms, checks, fixes

### 1. `/login?error=not-invited`

The provider authenticated the user but Back Office refused to create an account.

Check: audit `LOGIN_FAILURE` with `details.reason = not-invited`; **Administration → Platform settings** registration
mode and allowed domains; is there an `INVITED` user with exactly this e-mail?

Fix: invite the user (**Users → Invite user**), add the domain to allowed e-mail domains, or switch registration to
`OPEN`. For the very first administrator: put the e-mail into `BACKOFFICE_BOOTSTRAP_ADMINS` and restart.

### 2. `/login?error=deactivated`, `email-not-verified`, `email-missing`, `identity-conflict`

- `deactivated`: the account is deactivated → **Users → the user → Activate user** if that is intended.
- `email-not-verified`: the provider says `email_verified=false` → verify the address in the provider
  (Keycloak: user → *Email verified*).
- `email-missing`: no `email` claim → request scope `email`; Entra ID: add the optional `email` claim.
- `identity-conflict`: a Back Office user with this e-mail is already bound to another provider identity
  (other `sub` or other issuer, e.g. after recreating the user in Keycloak or switching providers).
  Check `SELECT id, issuer, subject FROM identity_user WHERE lower(email) = lower('<email>')`. Fix (DBA,
  audited manually): `UPDATE identity_user SET issuer = NULL, subject = NULL, status = 'INVITED' WHERE id = '<id>'`;
  the next login binds the new identity.

### 3. Redirect loop between Back Office and the provider

Check: browser dev tools, network tab with "preserve log": does `/login/oauth2/code/oidc` answer with
`Set-Cookie: BO_SESSION` and does the next request send it back?

Causes and fixes:

- **Cookie not set behind a TLS proxy** (most common): the API sees plain HTTP, so it builds `http://`
  redirect URIs or the browser drops the `Secure` cookie. Make the ingress send `X-Forwarded-Proto: https`
  and `X-Forwarded-Host`, keep `server.forward-headers-strategy: framework`. For plain-HTTP development
  only, set `BACKOFFICE_COOKIE_SECURE=false`.
- UI and API on different sites (e.g. `app.example.com` and `api.other.com`): `SameSite=Lax` cookies are not
  sent on cross-site XHR. Serve the UI and `/api`, `/oauth2`, `/login` from the same origin (the Helm ingress
  and the Angular dev proxy do this).
- Several API replicas without the shared session store: all replicas must use the same database (Spring
  Session JDBC); check that `SPRING_SESSION` receives rows.

### 4. Provider error page: "Invalid parameter: redirect_uri"

The redirect URI the API sends (`<scheme>://<host>/login/oauth2/code/oidc`, derived from the request) is not
registered at the provider. Register exactly that URI (scheme, host, port). If it shows `http://` behind
TLS, fix the forwarded headers as in 3.

### 5. `/login?error=invalid-id-token` or `invalid-token-response` right after the provider login

- **Clock skew**: ID token `iat`/`exp` are checked with 60 s tolerance. Compare `date -u` on the API host,
  the provider host (and the Docker Desktop VM locally); enable NTP.
- **Issuer mismatch**: `OIDC_ISSUER_URI` must equal the `iss` the provider puts into tokens (Keycloak:
  hostname settings / `KC_HOSTNAME`; a token issued for `http://localhost:8180` is rejected when the API
  uses `http://keycloak:8080`).
- Wrong client secret (`invalid-token-response` with `unauthorized_client` in the log) → fix
  `OIDC_CLIENT_SECRET`.
- The API cannot reach the provider's token/JWKS endpoints (egress, proxy, TLS trust store) → the log shows
  the connection error; OIDC discovery is lazy, so this surfaces at the first login, not at startup.

### 6. Writes fail with 403 `"code":"csrf"`

The request has a session but no valid `X-XSRF-TOKEN` header. The UI reads the `XSRF-TOKEN` cookie
(not HttpOnly) and sends it back. Check that the cookie exists for the UI's origin and that a proxy does not
strip the header. Scripts should use a personal token (`Authorization: Bearer bo_pat_...`), which needs no
CSRF header.

### 7. 403 with `"permission": "..."`

Authenticated but not allowed. The body names the missing permission (and `projectId`); the audit log has an
`ACCESS_DENIED` entry. Check **Users → the user → Effective permissions** (System | Group | User | Effective)
and the project's members. A DENY at user or group level overrides roles.

### 8. "Session expired" / 401 shortly after login

- Idle timeout (`BACKOFFICE_SESSION_IDLE_TIMEOUT`, 30 min) or absolute timeout (12 h,
  `SESSION_EXPIRED` in the audit log with `reason=absolute-timeout`).
- The user was deactivated or their sessions were terminated by an admin (`USER_DEACTIVATE`,
  `SESSIONS_TERMINATE`).
- The session row was lost: API pointed at another database, or the `SPRING_SESSION` tables are missing
  (Flyway `V401__spring_session.sql`).
- API clock far ahead of real time (session expiry uses the API clock).

### 9. Scripts get 401 `"code":"invalid-token"`

The bearer token is unknown, expired or revoked, or its owner is deactivated. **My account** (`/account`) **→ Personal access tokens** shows
`expiresAt` / `revokedAt`; create a new token. `bo_ci_` tokens: check the project's CI tokens.

### 10. Nobody can log in at all

- `OIDC_ISSUER_URI` blank → the log says "OIDC login is disabled"; `/api/v1/auth/config` returns no `loginUrl`
  (`methods.oidc = false`). Only local accounts and tokens work then; see section 16 for the first local admin.
- Provider down → `/oauth2/authorization/oidc` answers 500 with a discovery error in the log; fix the provider,
  no API restart needed.

### 11. Local login: `401 invalid-credentials` although the password "is right"

The answer is deliberately the same for every cause. Check the audit log (`LOGIN_FAILURE`, filter by the
user or the correlation id) for `details.reason`:

| Reason | Meaning | Fix |
|---|---|---|
| `unknown-user` | no user with this e-mail | typo, or invite the user (sign-in method Local) |
| `bad-password` | wrong password, or no password set yet | issue a reset link (section 13) |
| `locked` | the account is locked after repeated failures | section 12 |
| `method-not-allowed` | the user's sign-in method is `OIDC`, or local login is switched off (setting or `BACKOFFICE_AUTH_LOCAL_ENABLED=false`) | set the method to `LOCAL`/`BOTH` (**Users → the user → Sign-in method**) or enable local login |
| `inactive` | the user is `INVITED` (never set a password) or `DEACTIVATED` | send the set-password link again / activate |
| `bad-mfa` | wrong, reused or expired TOTP / recovery code, or the MFA step token expired (5 min) or was already used | sign in again from the password step; check the device clock; section 14 |

### 12. Local account locked

After 5 consecutive failures (password or MFA code) the account is locked for 15 minutes, then 30, 60 ...
up to 24 hours (`ACCOUNT_LOCKED` in the audit log with `lockedUntil`). The user sees only `invalid-credentials`.

Check: **Users → the user** shows `lockedUntil`; audit `LOGIN_FAILURE` entries before the lock (source IPs:
the user or someone guessing?).

Fix: **Unlock** (`POST /api/v1/admin/users/{id}/unlock`, `ACCOUNT_UNLOCK`). If the failures came from
elsewhere, reset the password (section 13) and consider requiring MFA (**Settings**). The limits are in
**Platform settings → Lockout**.

### 13. Forgotten password / set-password link does not work

- **Users → the user → Issue set-password link** returns a new link (24 h, single use); the old password and every
  session stop working at once. Hand the link over through a trusted channel.
- "This link is not valid" (`404` on `/api/v1/auth/password/reset/{token}`): the link was used, expired, or
  replaced by a newer one (only the latest link works), or the user was deactivated or switched to `OIDC`.
  Issue a new one.
- `400` when setting the password: the policy (12+ characters, not a common password, not containing the
  e-mail name); the page shows the rules.
- `409 local login is disabled`: enable it in **Settings** (or the environment) first.

### 14. Lost authenticator (TOTP) device

1. The user signs in with one of the 10 **recovery codes** (`RECOVERY_CODE_USED`), then re-enrols in
   **My account → Security**.
2. Without recovery codes: verify the person's identity out of band, then **Users → the user → Reset MFA**
   (`DELETE /api/v1/admin/users/{id}/mfa`, audited `MFA_RESET`). They sign in with the password only (or,
   with "MFA required", are asked to enrol again).
3. Codes that never match: the phone's clock is off by more than 30 s (enable automatic time), or the
   code was already used for the previous login (each 30-second code works once).
4. All authenticators broke at once after a deployment: `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` changed. Restore
   the old key from the Secret history; otherwise every user needs an MFA reset.

### 15. `429 too-many-attempts` / `403 password-change-required` / `403 mfa-enrolment-required`

- `429` with `Retry-After`: more than 20 sign-in attempts from one IP in 5 minutes (per API replica;
  `LOGIN_FAILURE` with reason `throttled`). Wait the given seconds. Many users behind one NAT/proxy hit it
  together: raise `BACKOFFICE_AUTH_LOCAL_THROTTLE_MAX_ATTEMPTS`, and make sure the ingress forwards the real
  client address in `X-Forwarded-For` - otherwise every request seems to come from the ingress.
- `403 password-change-required`: the session is restricted until the user changes the password
  (bootstrap admin, first login); the UI must send them to the change form.
- `403 mfa-enrolment-required`: the platform requires MFA and the user has none yet; they must enrol
  TOTP (`/me/mfa/totp`) first.

### 16. The API does not start: `backoffice.auth.local.secret-key ... must be set`

Local login is enabled but `BACKOFFICE_AUTH_LOCAL_SECRET_KEY` is missing or not base64 of 32 bytes.
Helm with `secrets.create=true` generates it; with an existing Secret add
`BACKOFFICE_AUTH_LOCAL_SECRET_KEY=$(openssl rand -base64 32)`, or set `BACKOFFICE_AUTH_LOCAL_ENABLED=false`
for OIDC-only installations.

**No administrator can sign in and there is no identity provider**: on a fresh database set
`BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` and restart (it is ignored once any user
exists). On an existing database a remaining administrator can reset the others; if none is left, have a
DBA clear the lockout (`UPDATE identity_local_credential SET locked_until = NULL, failed_attempts = 0`) for
the admin, or set its `auth_mode` back to one that works - and record what was done.

## Where to look when it fails

- Audit log: `LOGIN_FAILURE` (`details.method`, `details.reason`), `ACCOUNT_LOCKED`, `SESSION_EXPIRED`,
  `ACCESS_DENIED`, `LOGOUT` (`GET /api/v1/admin/audit`).
- API log with `logging.level.org.springframework.security=DEBUG` for one reproduction; correlate with
  `X-Correlation-Id`.
- Database: `identity_user` (status, issuer, subject, `auth_mode`), `identity_local_credential` (`locked_until`,
  `failed_attempts`, `must_change_password`; never copy `password_hash` anywhere), `identity_mfa_totp`,
  `identity_password_reset_token`, `SPRING_SESSION` (`PRINCIPAL_NAME` = user id), `identity_personal_token`.
- Provider: Keycloak → realm → Events (enable login events) shows `LOGIN_ERROR` with the reason.
