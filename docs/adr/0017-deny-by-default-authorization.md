# 0017 - Deny-by-default authorisation: code-defined permissions, three-level grants, project roles

Status: accepted - 2026-09-27

## Context
Different users must see and do different things: administrators manage users, library curators maintain
templates, project members edit workflows or only approve gates. A reference project
separates permissions from settings, keeps the permission catalogue in code, resolves grants on three
levels (system, group, user; closest wins) and enforces checks with an `AuthorizationGate` in every use
case plus a build rule. It lacks project scoping and freezes permissions in the token.

## Decision
- A new **identity** bounded context owns users, groups, roles, grants, project memberships, tokens and the
  audit log (`identity-core`, `identity-adapters`).
- **Catalogue in code**: every module declares its permissions (`resource.action`, scope PLATFORM or
  PROJECT) and contributes them as a `PermissionCatalogContribution` bean. The database stores grants only;
  unknown keys are rejected.
- **Platform grants** on levels SYSTEM, GROUP, USER with ALLOW/DENY, closest level wins, DENY wins within
  the group level; roles are named permission bundles.
- **Project roles** through memberships of users or groups; `admin.projects.all` for platform-wide access.
- **Gate in every use case**: `AuthorizationGate.require(permission[, projectId])` is the first statement of
  every public use-case method; exceptions are annotated `@PublicOperation(reason)` or
  `@SystemOperation(reason)`. An ArchUnit rule fails the build when a public method of an
  `application.service` class neither calls the gate nor carries an annotation.
- **List queries** are filtered with `gate.projectFilter(permission)`.
- **Effective permissions are computed per request** from a cache keyed by user and a global access
  version that every access change increments.

## Alternatives
- `@PreAuthorize` annotations on controllers: easy to forget, invisible in core, no project scope.
- Roles in Keycloak mapped to authorities: cannot express "approver on project X" without a role per
  project; administration would be split between two UIs.
- Spring Security ACL module: row-level ACLs are heavier than needed; memberships cover the use cases.

## Consequences
- Every existing use case is retrofitted with a gate call and tests use `FakeAuthorizationGate.permitting`
  with the exact permission so a wrong constant fails the test.
- Background work (engine, schedulers, seeding, runner callbacks) runs through `gate.asSystem(reason)`.
- Human gates gain approver rules and DoR/DoD checklists (RACI: exactly one accountable role per gate).
