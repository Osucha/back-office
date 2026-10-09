# 0001 - Hexagonal architecture with one core and one adapters module per bounded context

Status: accepted - 2026-09-26

## Context
The application has two clearly different capabilities (delivery pipelines, model evaluation) that share
a model catalogue and cost ledger. It will be maintained by one person and by coding agents, so the
structure must make wrong dependencies impossible rather than merely discouraged.

## Decision
Gradle multi-module build. Per bounded context: `<ctx>-core` (domain + application, pure Kotlin) and
`<ctx>-adapters` (all driving and driven adapters). `shared-kernel` holds primitives and the LLM/ledger
ports; `llm-gateway` is the Spring AI adapter shared by both contexts; `app` wires Spring Boot.
ArchUnit tests in `app` enforce: core has no framework dependencies, adapters do not depend on other
contexts' adapters, domain does not depend on application.

## Alternatives
- Single module with packages only: cheaper to start, but the compiler cannot stop `domain -> Spring` leaks; ArchUnit alone is easy to ignore.
- Module per adapter (persistence, rest, langgraph, ...): purer, but ~15 modules for a one-person tool; wiring overhead outweighs the benefit.

## Consequences
- Use cases are plain classes instantiated in `<ctx>.adapter.config` `@Configuration` classes.
- Cross-context calls (projects -> model catalogue) go through a port implemented in `app`.
- Compile time is slightly higher; build clarity and agent-friendliness are much higher.
