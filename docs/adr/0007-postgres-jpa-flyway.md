# 0007 - PostgreSQL with Spring Data JPA, JSONB documents and Flyway

Status: accepted - 2026-09-26

## Context
Aggregates such as `PipelineDefinition` and `PipelineRun` are document-shaped (nested steps, artefacts,
findings) while queries are relational (runs by project and status, usage by model and day). LangGraph4j
needs PostgreSQL for checkpoints anyway.

## Decision
PostgreSQL 16. Spring Data JPA (Hibernate 7) in adapters with separate `*Entity` classes mapped to domain
objects; nested collections stored as JSONB via `@JdbcTypeCode(SqlTypes.JSON)`; Flyway for schema
migrations with version ranges reserved per context (V1 shared, V100+ projects, V200+ evaluation).

## Alternatives
- Spring Data JDBC: lighter, but JSONB and aggregate mapping need custom converters everywhere.
- jOOQ: excellent SQL control, more boilerplate for document-shaped aggregates.
- Event sourcing: attractive for run history but out of proportion for this tool; the `StepExecution`
  log gives enough auditability.

## Consequences
- The domain model stays free of JPA annotations; mapping code lives in adapters.
- Artefacts larger than 2 MB go through a `BlobStore` port (filesystem or PVC by default).
