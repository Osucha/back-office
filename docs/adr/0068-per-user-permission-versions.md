# 0068 — Permission versions per user and a UI that follows them

- Status: accepted
- Date: 2026-11-16
- Deciders: owner
- Related: ADR 0066 (stage 3 of three), 0017 (deny by default, principle 6), 0023 (cluster signals);
  docs/architecture/08-identity-and-access.md

## Context

The permission cache was keyed by one global access version: any grant change re-resolved every user. The UI
loaded `/me` once, so after an administrator changed someone's access their buttons stayed wrong until a reload.

## Decision

- A user's access version is the global counter plus a counter of that user (`identity_user_access_version`),
  the library's `PermissionVersions`. Changes of one user (their grants, groups, memberships, status, tokens) bump
  theirs; changes that may affect many (roles, groups and their grants, the SYSTEM grant, group memberships of
  projects, settings, project renames and deletions, migrations) bump the global one, in the same transaction.
- The snapshot stays in the in-memory cache keyed by (user, version), not in the session: personal tokens have
  no session, and Spring Session JDBC would write the snapshot back on every change.
- Each instance applies its own bump after commit; others through the cluster signal (NOTIFY on commit), with a
  2 s poll of recent changes as the fallback. CI tokens are verified per request.
- Authenticated API responses carry `X-Access-Version`; `/me` returns `accessVersion`. The SPA reloads `/me` once
  when a response announces a newer version, and after a 403 (throttled).

## Alternatives considered

- `SessionCachedPermissions` with the snapshot in the session: fits session users only, adds session writes.
- Reading the user's version from the database per request: one more query per request.

## Consequences

- A grant change no longer flushes everyone; a revocation still takes effect on the next request.
- A version only grows; a client compares numerically.
