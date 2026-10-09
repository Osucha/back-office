# Runbook: the work queue (`work_item`), leases, the LISTEN bridge and scaling workers

Background: ADR 0023, docs/architecture/09-scale-and-operations.md. Code: `backend/platform-runtime`
(`JdbcWorkQueue`, `WorkerLoop`, `PgNotificationListener`, `PlatformEventStream`, `ShedLockClusterLock`).

## How it is supposed to work

- Background work is a row in `work_item` (`kind`, `dedupe_key`, `payload`, `status`, `attempts`,
  `max_attempts`, `lease_owner`, `lease_until`, `generation`, `last_error`). Kinds: `RUN_ADVANCE` (drive one
  workflow run, key `RUN_ADVANCE:<runId>`), `BENCHMARK_ATTEMPT` (key `BENCHMARK_ATTEMPT:<attemptId>`), later
  `CI_EXECUTION_COMPLETE`, `RETENTION`.
- Every replica with the `worker` role (`backoffice.role`, default `api,worker`) claims READY items with
  `FOR UPDATE SKIP LOCKED`, leases them for `backoffice.platform.worker.lease` (60 s) and heartbeats every
  lease/3. Inserts wake workers with `NOTIFY backoffice_work`; workers also poll every 2 s.
- Outcomes: DONE; READY again after a backoff (`Retry`, handler exception: 5 s * 2^(attempt-1), max 5 min);
  DEAD after `max_attempts` (RUN_ADVANCE 10, BENCHMARK_ATTEMPT retry budget + 3).
- A second request with the same dedupe key while an item is live merges its payload and bumps
  `generation`; if the item was LEASED it runs again after the current run (the signal is never lost).
- A run waiting for a runner job holds **no** work item: the runner control plane enqueues the continuation
  when the job ends.
- Operations API (platform admins, `admin.settings.manage`): `GET /api/v1/admin/operations/overview`,
  `GET /api/v1/admin/operations/work-items?status=DEAD&kind=...`, `POST .../work-items/{id}/retry`.

## Useful SQL

```sql
-- what is queued / running / dead, per kind
SELECT kind, status, count(*), min(run_after) FROM work_item WHERE status <> 'DONE' GROUP BY kind, status;
-- the item of one run
SELECT * FROM work_item WHERE dedupe_key = 'RUN_ADVANCE:<runId>' ORDER BY created_at DESC;
-- leases held per replica
SELECT lease_owner, count(*), min(lease_until) FROM work_item WHERE status = 'LEASED' GROUP BY lease_owner;
-- live replicas
SELECT id, roles, version, heartbeat_at FROM platform_instance ORDER BY heartbeat_at DESC;
-- singletons (seeding, sweeps, recovery, retention)
SELECT * FROM shedlock ORDER BY lock_until DESC;
```

## Symptoms, checks, fixes

### 1. Items stay READY (`backoffice_work_oldest_ready_age_seconds` grows)

- No worker: `platform_instance` has no fresh row with `worker` in `roles`. Scale the worker Deployment
  (or run `api,worker`). Worker-only replicas answer only `/actuator/**`.
- Workers full: every worker has `concurrency` items in flight (`backoffice_work_duration_seconds` long).
  Add workers (KEDA scales on the READY count) or raise `backoffice.platform.worker.concurrency`.
- `run_after` in the future: the item is backing off (`last_error` says why). Nothing to do, or use the
  operations retry for DEAD items only.
- No handler for the kind on any worker (version skew during a rollout): finish the rollout.

### 2. Items DEAD

- Look at `last_error` (operations -> work items, filter `DEAD`). Fix the cause (provider credentials, a
  bug, a missing run), then retry from the UI/API: the item is READY again with a fresh attempt budget
  (audited `WORK_ITEM_RETRY`). A retry fails with 409 when a live item with the same key exists: that one
  already carries the work.
- A DEAD `RUN_ADVANCE` leaves the run `RUNNING`; the recovery scan (every 10 min, ShedLock
  `workflow-recovery`) enqueues a `RECOVER` advance for RUNNING runs, so the run continues from its
  checkpoint once the cause is fixed.

### 3. Lease expiry (a worker crashed, was OOM-killed, lost the database or was paused)

- Symptom: log `work item lease expired id=... now=READY`, `last_error = 'lease of <owner> expired'`,
  `attempts` increased. Any worker reclaims expired leases on its next poll; the next owner continues
  (workflow runs resume from the last LangGraph checkpoint, so a node that was running is executed again).
- If the old owner is still alive (long GC pause, network partition) its heartbeat finds the lease gone,
  logs `work item lease lost, interrupting` and interrupts its copy; its late completion is refused
  (`lease lost before completion`).
- Frequent expiries with healthy pods: handlers block longer than the lease without heartbeats reaching the
  database (database saturated). Check Hikari metrics; raise `backoffice.platform.worker.lease`.

### 4. LISTEN connection lost

- Symptom: `backoffice_event_listener_connected = 0`, log `LISTEN connection lost, reconnecting`, SSE
  clients of that replica get no live events, workers react only on the 2 s poll.
- The bridge reconnects every `backoffice.platform.events.reconnect-delay` (5 s) and then delivers the
  `event_log` rows written meanwhile (no duplicates) and invalidates caches subscribed to `backoffice_cache`.
- The listener needs one direct connection per replica **outside PgBouncer transaction mode**; if all
  replicas show 0, check that `spring.datasource.url` (used for the dedicated connection) points at a
  session-mode endpoint or directly at PostgreSQL, and `max_connections`.

### 5. Scaling workers

- Scale out: add replicas with `backoffice.role=worker` (no downtime; they claim with SKIP LOCKED). Budget
  10 pooled + 1 LISTEN connection per pod.
- Scale in / rollout: SIGTERM -> the worker stops claiming, waits up to `drain-timeout` (30 s) for in-flight
  items, then releases the remaining leases without counting the attempt; Spring waits up to
  `spring.lifecycle.timeout-per-shutdown-phase` (45 s); keep `terminationGracePeriodSeconds` >= 60.
- Singletons (seeding, wait-timeout sweep, recovery scans, retention) run on one replica at a time under
  ShedLock; a crashed holder blocks them at most for `lockAtMost` (5-10 min).

### 6. Retention

`event_log` rows older than 24 h, DONE items older than 7 days and replicas silent for a day are deleted
hourly by one worker (ShedLock `platform-retention`); tune `backoffice.platform.retention.*`.
