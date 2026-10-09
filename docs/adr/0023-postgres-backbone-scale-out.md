# 0023 - Scale-out on a PostgreSQL backbone: api / worker / runner-controller roles

Status: accepted - 2026-09-28

## Context
Until now one API process did everything: HTTP, the LangGraph workflow engine, benchmark execution,
schedulers, seeding, runner orchestration, SSE fan-out, rate limits and throttling, with state in memory.
Running two replicas would execute recovered runs twice, run sweeps and seeds concurrently, split SSE
streams by replica and multiply every limit. The platform must scale horizontally, run on any managed
Kubernetes and stay simple to operate for a small team.

## Decision
- **One image, three roles** selected by `backoffice.role` (comma list): `api` (HTTP, SSE, auth), `worker`
  (workflow engine, benchmark attempts, schedulers, outbox relay) and the separate `runner-controller`
  application (ADR 0024). Small installs run `api,worker` in one Deployment.
- **PostgreSQL is the backbone**; no broker is required:
  - **Work queue** table `work_item` (kind, key, payload, run_after, attempts, lease_owner, lease_until,
    status) claimed with `SELECT … FOR UPDATE SKIP LOCKED`, leases with heartbeats, exponential retry,
    dead-letter status. Kinds: `RUN_ADVANCE` (drive one workflow run until it waits or ends),
    `BENCHMARK_ATTEMPT`, `CI_EXECUTION_COMPLETE`, `RETENTION`.
  - **Run ownership**: a run is advanced only by the worker holding its `RUN_ADVANCE` lease; LangGraph
    checkpoints stay in PostgreSQL, so another worker resumes after a crash or scale-in. Resume signals
    (gate decisions, CI signals, runner results) enqueue `RUN_ADVANCE` instead of calling the engine.
  - **Singletons** (seeding, sweeps, retention, recovery scans) run under ShedLock (JDBC provider).
  - **Events**: domain events are written to `event_log` (outbox, same transaction) and announced with
    `NOTIFY backoffice_events`; every api replica `LISTEN`s and fans out to its SSE subscribers; replay by
    `Last-Event-ID` reads `event_log` (retention 24 h).
  - **Distributed limits**: login throttling, MFA token single-use and provider rate limits use bucket4j
    with its PostgreSQL backend (or plain rows where simpler); caches are invalidated through `NOTIFY`.
- **Object storage** (S3-compatible: S3, GCS interop, Azure via S3 gateway, MinIO) for artefacts, diffs,
  runner logs and uploads, behind a `BlobStore` port; PostgreSQL keeps metadata and small (< 64 kB) content.
- **Migrations** run as a Helm pre-install/pre-upgrade Job (`backoffice.role=migrate`); application pods
  never migrate.
- **Autoscaling**: HPA on api (CPU and request rate), KEDA on workers (PostgreSQL scaler on ready
  `work_item` count) and on runner-controllers (queued agent jobs per pool).

## Alternatives
- Kafka/NATS + Redis: proven at larger scale, but two more stateful systems for a platform whose
  throughput is bounded by LLM latency (tens of jobs per second, not thousands).
- Temporal for workflow execution: excellent durability, but replaces LangGraph and adds a cluster; the
  `WorkflowEngine` port keeps the option open.
- Sticky sessions for SSE: fragile under scale-in; LISTEN/NOTIFY is simpler.

## Consequences
- PostgreSQL sizing matters (connection pooling via PgBouncer or the managed equivalent; `max_connections`
  budget per role documented).
- Every in-memory map that represents shared truth is removed or turned into a cache.
- Replacing PostgreSQL LISTEN/NOTIFY with a broker later is a change in one adapter.
