# 0006 - Coding agents run as Kubernetes Jobs in a shared runner image

Status: execution model superseded by [0024](0024-sandboxed-runner-fleet.md) on 2026-09-28 (the runner image stays)

## Context
Implementation steps and coding benchmarks need a sandbox with git, toolchains and an agent harness.
Benchmarks must compare models fairly, so the harness must be provider-agnostic.

## Decision
One `runner` image containing OpenCode (default, provider-agnostic), Claude Code (optional, Anthropic
best case), Spec Kit, git and JDK/Node/Python toolchains. Each step or benchmark attempt is one
Kubernetes `Job` created by the API via fabric8; results return through an authenticated HTTP callback.
A `docker run` adapter provides the same contract for local development.

## Alternatives
- Anthropic Managed Agents or other hosted sandboxes: excellent for a single vendor, but the evaluation
  capability needs any vendor in the same harness.
- Running agents inside the API pod: no isolation, no resource limits, a restart kills the work.
- GitLab CI as the runner: possible, but slower to start, harder to stream logs and couples the app to a
  CI runner fleet.

## Consequences
- The API needs RBAC to create, list and delete Jobs and read pod logs in its namespace.
- The runner protocol (spec, events, result) is part of the API contract and versioned with it.
