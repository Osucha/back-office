# 0021 - Local accounts with passwords and TOTP, alongside OIDC

Status: accepted - 2026-09-27. Amends [0016](0016-oidc-bff-authentication.md) (OIDC stays; local login is added).

## Context
ADR 0016 delegated all authentication to an OIDC provider. The owner asked for local users and local
login: installations without Keycloak, service or break-glass accounts, and external collaborators who
are not in the organisation's directory. The reference project supports local and domain login per
account (`AuthMode LOCAL / DOMAIN / BOTH`) with a password policy, uniform error messages and a forced
password change, but it leaks timing for unknown users and has no lockout or MFA.

## Decision
- **Per-user `authMode`**: `LOCAL`, `OIDC` or `BOTH`. OIDC users never have a password. Local login can
  be switched off platform-wide (`localLoginEnabled`), OIDC is on when an issuer is configured; either
  may be the only method.
- **Local login** `POST /api/v1/auth/login` creates the same server-side session as OIDC, so everything
  after login (CSRF, timeouts, gate, audit) is identical.
- **Passwords** hashed with Argon2id (Spring Security `Argon2PasswordEncoder`, `defaultsForSpringSecurity_v5_8`
  parameters) behind a `DelegatingPasswordEncoder` so parameters can be upgraded on next login. Policy:
  12–128 characters, no composition rules (NIST SP 800-63B), rejects a bundled list of common passwords
  and passwords containing the e-mail's local part.
- **No enumeration, no timing leak**: unknown user, wrong password, deactivated account and wrong
  `authMode` return the same `401 invalid-credentials`; an unknown user still runs one hash against a
  dummy value.
- **Brute-force protection**: per-account lockout after 5 failures for 15 minutes, doubling up to 24 h;
  per-source-IP throttling (sliding window, 20 attempts / 5 min) returns `429` with `Retry-After`. Lockout
  is not revealed by the error; admins see and clear it.
- **MFA**: optional TOTP (RFC 6238, 30 s, 6 digits, ±1 step, replay of the last used step rejected) with
  10 single-use recovery codes (hashed). The platform can require MFA for local accounts; users then
  enrol at next login (`MFA_ENROLMENT_REQUIRED`).
- **Onboarding and resets without e-mail**: Back Office does not send e-mail. Inviting a local user or
  resetting a password returns a one-time link (256-bit token, hashed at rest, 24 h, single use) that the
  administrator hands over. A reset invalidates the old password and all sessions.
- **Bootstrap without an identity provider**: `backoffice.auth.local.bootstrap-admin-email` +
  `bootstrap-admin-password` (env) create the first platform admin with `mustChangePassword` when no user
  exists yet.
- Every step is audited: `LOGIN_SUCCESS`/`LOGIN_FAILURE` (method, reason), `ACCOUNT_LOCKED`,
  `PASSWORD_CHANGE`, `PASSWORD_RESET_ISSUE`, `PASSWORD_RESET_COMPLETE`, `MFA_ENABLE`, `MFA_DISABLE`,
  `MFA_RESET`, `RECOVERY_CODE_USED`, `ACCOUNT_UNLOCK`, `AUTH_MODE_CHANGE`.

## Alternatives
- Keep "local" users inside Keycloak only: already possible, but the owner wants the platform to work
  without Keycloak and to manage these accounts in one place.
- WebAuthn/passkeys: stronger, planned as a follow-up; TOTP covers the common case today.

## Consequences
- Back Office now stores credentials: hashes, TOTP secrets (encrypted with `backoffice.auth.local.secret-key`,
  AES-GCM) and recovery-code hashes; the key must be provided in production (the chart generates one).
- The login page offers both methods according to `/auth/config`.
