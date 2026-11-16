# 0066 — Authorization on port-permissions

- Status: partly superseded by 0067 (runner principal); stage 3 done in 0068
- Date: 2026-11-16
- Deciders: owner
- Related: ADR 0017 (deny-by-default authorisation), 0029 (token capabilities), 0028 (gate approvers);
  docs/architecture/08-identity-and-access.md

## Context

ADR 0017 gave Back Office its own authorisation primitives: string permission keys, an `AuthorizationGate`,
`@PublicOperation` / `@SystemOperation` and an ArchUnit test. The same ideas now live in the library
port-permissions (typed permissions declared per module, the `AccessGuard` port, a central catalogue,
ArchUnit rules that also see checks in lambdas). Keeping two implementations means keeping two.

## Decision

Back Office adopts port-permissions 0.2.0 (JitPack) for its authorisation primitives:

- every module declares its permissions as `PermissionSet`s with typed `Permission` / `ScopedPermission<UUID>`
  (scope type `project`) and contributes them as a `ModulePermissions` bean; identity builds the library
  `PermissionCatalog` from them;
- use cases call `AccessGuard.require(...)` and filter lists with `scopesWith(...)` (`ScopeFilter`);
  "who am I" is the Back Office port `CurrentPrincipal`;
- exemptions use the library `@PublicOperation` / `@SystemOperation`; deny by default is
  `PortPermissionsRules.operationsAreGuarded()`.

What stays in Back Office: roles, groups, memberships and grants, gate approver rules, token capabilities, and
computing the effective permissions of each request (`CurrentUser`, mapped to the library snapshot by
`effectivePermissions`). The guard is Back Office's own `AccessGuard` implementation over `CurrentUser`.

The move is done in three stages:

1. Typed permissions, the guard and the architecture rule, without any change of behaviour (this record).
2. System operations without privileges: system ports, `requireSystem()` in every `@SystemOperation`, the system
   principal holding no permissions, the runner as a `MACHINE` principal.
3. Effective permissions cached in the session with invalidation, and the snapshot in `/me` (done in ADR 0068: versions
   per user, the snapshot in the in-memory cache instead of the session, `accessVersion` in `/me`).

## Alternatives considered

- Keep the own primitives: two implementations of the same concept to maintain.
- The library `DefaultAccessGuard`: its system principal holds nothing, which today's background work (stage 1
  keeps it) would not survive.

## Consequences

- Permission keys are unchanged (`PermissionSet` module + name = the old key); stored roles, grants and token
  subsets stay valid, and a test pins the full catalogue.
- Core modules may depend on port-permissions-core (Kotlin stdlib only), nothing else new.
- The build needs JitPack as a repository, limited to the library's group.
