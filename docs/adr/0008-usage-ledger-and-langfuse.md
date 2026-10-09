# 0008 - Own usage ledger for cost plus optional Langfuse tracing over OTLP

Status: accepted - 2026-09-26

## Context
"How much does this model cost in real work" must be answered inside the app (per run, per benchmark,
per model) and ideally with full traces for debugging prompts.

## Decision
Every LLM call and runner job writes a `UsageRecord` (tokens, cost from `ModelProfile` pricing or the
harness report). Additionally, Spring AI observations are exported over OTLP; when Langfuse is enabled
(Helm value) the endpoint is the Langfuse OTLP receiver, giving prompt-level traces and Langfuse cost
views.

## Alternatives
- Langfuse only: would make the app depend on Langfuse (four stateful services) for a core feature.
- Ledger only: no prompt or response traces for debugging; acceptable minimum, hence Langfuse is optional.

## Consequences
- Pricing is user-maintained in the catalogue; a small dated seed covers common models.
- Langfuse is a Helm dependency with `enabled: false` by default.
