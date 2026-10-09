# 0016 - OIDC login through a backend-for-frontend with server-side sessions

Status: accepted - 2026-09-27. Supersedes [0009](0009-single-owner-api-key-auth.md). Amended by [0021](0021-local-accounts.md): local accounts are allowed alongside OIDC.

## Context
Back Office becomes a platform for many users. It needs login, logout, session expiry, single sign-on
with the organisation's identity provider and machine access for scripts. A reference
project keeps tokens out of the browser with an HttpOnly cookie behind a same-origin proxy, which
works well, but it also stores local passwords and freezes permissions in a 15-minute JWT.

## Decision
- **Authentication by OIDC** (authorization code flow with PKCE) against any OIDC provider; Keycloak 26.7
  is the default and ships as a local realm and an optional Helm dependency. Back Office stores no passwords;
  MFA, password policy, brute-force protection and federation (LDAP, SAML, social) are the provider's job.
- **The API is the BFF.** Spring Security `oauth2Login` performs the code exchange; tokens stay on the
  server; the browser holds only the `BO_SESSION` cookie. Sessions are stored in PostgreSQL with Spring
  Session JDBC so the API can scale out and sessions can be revoked.
- **Idle and absolute timeouts** (30 min / 12 h defaults) and a per-request account check through a
  short-lived cache, so deactivation is immediate.
- **CSRF** protection with the `XSRF-TOKEN` cookie and `X-XSRF-TOKEN` header (Angular's built-in support).
- **Machine access** with personal access tokens (`bo_pat_`) and project CI tokens (`bo_ci_`), bearer only.
- The single `BACKOFFICE_API_KEY` is removed; the first administrator comes from
  `backoffice.auth.bootstrap-admins`.

## Alternatives
- SPA as a public OIDC client with tokens in the browser (angular-oauth2-oidc): simpler server, but tokens
  are exposed to XSS and refresh logic lives in the browser.
- Own login with passwords and JWT (as in the reference project): full control, but duplicates what Keycloak does better
  and makes revocation hard.
- Spring Cloud Gateway as a separate BFF: an extra service for no benefit at this scale.

## Consequences
- Local development needs Keycloak (compose service with realm import and test users).
- Tests use Spring Security test support (`oidcLogin()`), plus one Testcontainers Keycloak test for the
  real flow.
- The CI client script and runner callbacks are unaffected (they use their own bearer tokens).
