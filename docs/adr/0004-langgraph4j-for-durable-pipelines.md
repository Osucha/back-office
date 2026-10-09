# 0004 - LangGraph4j with Postgres checkpoints as the pipeline engine

Status: accepted - 2026-09-26

## Context
Pipelines run for hours (coding agents), must pause for a human for days, survive restarts and loop back
on revision. The owner asked to prefer proven components over home-grown engines and named LangGraph.

## Decision
LangGraph4j 1.9 (JVM port of LangGraph). A `PipelineDefinition` is compiled to a `StateGraph`; human
gates are `interruptBefore` nodes; checkpoints are stored in PostgreSQL with `PostgresSaverV2`; resume is
`GraphInput.resume(decision)` on the run thread id. Long steps are delegated to Kubernetes Jobs and the
node waits for the callback, so a restart re-attaches instead of re-running.

## Alternatives
- Temporal: the strongest durable-execution engine with signals for human input; rejected because it adds
  a server, its own database schema and a second programming model for a one-person deployment.
- Argo Workflows: Kubernetes-native DAGs with suspend steps; rejected because pipeline logic (routing on
  confidence, revision loops, LLM steps) would live in YAML generated at runtime and the UI would need to
  mirror Argo state.
- Spring state machine or an own executor: would reinvent checkpointing and interrupts.

## Consequences
- Graph state holds routing data only; the domain model (`PipelineRun`, `StepExecution`) is the source of
  truth for the UI.
- Runner-backed nodes must be idempotent (deterministic Job names).
- If scale ever demands it, the `PipelineEngine` port allows swapping in Temporal without touching the domain.
