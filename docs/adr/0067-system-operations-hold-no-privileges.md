# 0067 — System operations hold no privileges

- Status: accepted
- Date: 2026-11-16
- Deciders: owner
- Related: ADR 0066 (stage 2 of three), 0024 (runner protocol), 0029 (job and agent tokens);
  docs/architecture/08-identity-and-access.md

## Context

Until now `asSystem` switched to a principal that passed every permission check. Any path that wandered into
`asSystem` (an engine call, a filter, a callback) could reach every user use case.

## Decision

- The system principal holds no permissions: inside `asSystem` every `require(...)` fails and `scopesWith` lets
  nothing through; only `requireSystem()` passes.
- Work nobody asked for in person goes through **system ports** in `application.port.in.system` of each context.
  Every `@SystemOperation` calls `guard.requireSystem()` first and implements a system-port method.
- The callers of system ports are internal or machine-facing: schedulers, work-queue handlers, event listeners,
  startup seeding, the workflow engine, the authentication filters and the runner protocol and sandbox callbacks
  (`adapter.in.runner`). Each enters `asSystem` on the thread that calls the port.
- Four port-permissions rules in `DenyByDefaultArchitectureTest`: `operationsAreGuarded`,
  `systemOperationsRequireSystem`, `systemOperationsLiveInSystemPorts`,
  `systemPortsAreNotReachableFromUserFacingAdapters` (web, REST, MCP, SSE).
- The runner protocol stays a system operation in a machine-facing adapter instead of a `MACHINE` principal: the
  adapter passes the authenticated runner or job credential explicitly, and every operation checks that the job or
  pool belongs to it. With an unprivileged system a runner reaches only those operations.

## Alternatives considered

- A `MACHINE` principal with runner permissions: more concepts (permissions, grants and a principal for runners),
  no extra protection, since the ownership checks are needed either way.

## Consequences

- Cross-context ports that other use cases call (`TaskCatalog`, `ProjectAccessDirectory`,
  `ProjectAccessProvisioner`, `ConnectorCatalog`) are not use cases and carry no annotation; the calling use case
  authorises.
- A user use case that records engine progress (cancelling a run) enters `asSystem` for that call; it gains nothing.
