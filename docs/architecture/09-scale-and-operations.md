# 09 — Scale, cloud deployment and operations

Target: any managed Kubernetes (EKS, GKE, AKS, OpenShift) or a single VM with Docker; managed PostgreSQL
and S3-compatible object storage; horizontal scaling of every stateless role; zero-downtime upgrades.
Decisions: ADR 0023 (backbone), ADR 0024 (execution plane).

## Topology

```
                         ┌──────────────── control plane cluster / namespace ────────────────┐
 browser ── ingress ──▶  │ web (nginx, N)  ──▶ api (N, HPA)  ─┐                               │
 CI jobs ── ingress ──▶  │                                    ├─▶ PostgreSQL (managed / CNPG) │
 IDE/MCP ── ingress ──▶  │                    worker (N, KEDA)┘   · app schema, LangGraph      │
                         │                        │                  checkpoints, sessions,    │
                         │                        └──▶ object storage (S3/MinIO)  work_item,  │
                         │   migrate Job (pre-upgrade hook)          event_log, shedlock      │
                         └────────────────────────────▲──────────────────────────────────────┘
                                                      │ HTTPS (outbound from the fleet only)
                ┌──────────── execution plane cluster(s) ───────────────────────────────────┐
                │ runner-controller (N per pool, KEDA)  ──▶  Agent Sandbox CRDs            │
                │      SandboxWarmPool ─▶ Sandbox pod: [smokescreen sidecar][agent runner]  │
                │      RuntimeClass gvisor | kata · NetworkPolicy default-deny              │
                └───────────────────────────────────────────────────────────────────────────┘
```

## Roles (`backoffice.role`, one image)

| Role | Runs | Scales on | State |
|---|---|---|---|
| `api` | REST, SSE, OIDC/local login, CI endpoints, runner v2 API, LLM gateway proxy, MCP | CPU, RPS (HPA) | none (sessions in PostgreSQL) |
| `worker` | `work_item` consumers (run advance, benchmark attempts, CI completion, retention), ShedLock singletons (seeding, sweeps, recovery scans), outbox relay | ready `work_item` count (KEDA PostgreSQL scaler) | none (leases in PostgreSQL) |
| `migrate` | Flyway, then exit | Helm hook | — |
| `runner-controller` (separate app) | leases agent jobs for its pool, drives sandboxes | queued jobs for its pool (KEDA metrics API scaler on `/runner/v2/pools/{id}/queue`) | none |

`api,worker` together is the default for small installs (one Deployment, replicas ≥ 2 is safe).

## Work queue (`work_item`)

| Column | Notes |
|---|---|
| `id` uuid, `kind` text, `dedupe_key` text unique where status in (READY, LEASED) | e.g. `RUN_ADVANCE:<runId>` — at most one live item per run |
| `payload` jsonb, `priority` smallint, `run_after` timestamptz | |
| `status` READY / LEASED / DONE / DEAD, `attempts` int, `max_attempts` int | |
| `lease_owner` text, `lease_until` timestamptz, `last_error` text | |
| `created_at`, `updated_at` | index `(status, run_after, priority)` |

- Claim: `UPDATE work_item SET status='LEASED', lease_owner=:me, lease_until=now()+:lease WHERE id IN (SELECT id FROM work_item WHERE status='READY' AND run_after<=now() AND kind = ANY(:kinds) ORDER BY priority DESC, run_after LIMIT :n FOR UPDATE SKIP LOCKED) RETURNING *`.
- Heartbeat extends `lease_until` every lease/3; expired leases are reclaimed by any worker.
- Completion: `DONE` (kept 7 days) or `READY` with `run_after = now() + backoff(attempts)`; after
  `max_attempts` → `DEAD` (visible in admin → operations, retryable).
- Wake-up: inserting a READY item issues `NOTIFY backoffice_work`; workers also poll every 2 s.
- Workflow runs: `RUN_ADVANCE` executes the LangGraph graph until it interrupts (gate / CI wait / runner
  job pending) or ends; runner results, gate decisions, CI signals and timeouts enqueue `RUN_ADVANCE`
  with the resume payload. Node executors never block a worker thread on a runner job: the SPEC_KIT /
  IMPLEMENT executors submit the job and interrupt; the result resumes the run.

Implementation notes (platform-runtime, as built):

- `generation` column: a request merged into a LEASED item (same dedupe key) bumps it; the worker then
  completes the item to READY (fresh attempts) instead of DONE, so the merged signal runs next. A request
  with payload `_merge=keep` (recovery scans) never overwrites a live item.
- Every worker-side write is guarded by `lease_owner = :me AND status = 'LEASED'`; a lost lease is noticed
  by the heartbeat, which interrupts the stale handler, and its late completion is refused.
- Resumes are applied only when the checkpoint still waits at the node they target (idempotent re-delivery);
  otherwise the run continues from its checkpoint. Runner-backed TASK nodes get an `<id>::await` graph node
  (`interruptBefore`); the continuation `{runId, resume: {kind: RUNNER_RESULT, nodeId}, jobId, status}`
  resumes it and it maps `AgentRunner.result(jobId)`; a continuation without a result yet is a no-op.
- Handler exceptions retry after 5 s * 2^(attempt-1) (max 5 min); graceful stop releases leases without
  counting the attempt. Properties `backoffice.platform.worker.{concurrency,lease,poll-interval,claim-batch,drain-timeout}`.
- Cancelling a run interrupts the worker thread holding it on any replica (`backoffice_cache` topic
  `workflow-run-cancel`) and cancels a pending runner job.
- Roles: `backoffice.role` sets `backoffice.platform.api-enabled` / `worker-enabled`. A replica without
  `api` keeps its web server but answers only `/actuator/**` (probes, Prometheus). `migrate` runs Flyway in a
  minimal context and exits (combined with other roles it migrates first, then starts). `spring.flyway.enabled`
  is false by default and true in the `local` profile.

## Events

`event_log(id bigserial, type, project_id, payload jsonb, occurred_at)` written in the business
transaction (outbox); `NOTIFY backoffice_events, '<id>'` after commit; api replicas `LISTEN`, load the
row, filter per subscriber (project visibility) and push SSE. `Last-Event-ID` replays from `event_log`
(retention 24 h, ShedLock job). Cache invalidation (`access_version`, compiled graphs, model catalogue)
uses channel `backoffice_cache`.

As built: `NOTIFY` is issued inside the business transaction (PostgreSQL delivers it on commit and drops it on
rollback). The listener holds one dedicated non-pooled connection per replica and reconnects every 5 s; after a
reconnect it delivers the rows created since shortly before the loss (ids already delivered are skipped) and
signals every cache subscriber with an empty key ("invalidate all"). Cache-channel payloads are
`<instance>|<topic>|<key>`; the publishing replica delivers locally at once and ignores its own notification.
Identity's access version subscribes to topic `identity-access-version`.

## Limits shared across replicas

| Limit | Mechanism |
|---|---|
| Login throttle per IP, lockout | plain rows `identity_login_attempt` (sliding window, per-key advisory lock; keeps ADR 0021 semantics exactly) / credential rows; burst audit dedupe `identity_throttle_audit` |
| MFA token single use | `mfa_token_use(jti primary key, expires_at)` |
| Provider rate limits (requests, tokens per minute; interactive reserve) | bucket4j PostgreSQL buckets (`llm_rate_bucket`, advisory lock, `bucket4j_jdk17-postgresql` 8.20) per provider and dimension, refilled per minute; a batch call must find weight + reserve available and consumes only its weight, so the reserve holds cluster-wide. `backoffice.llm.rate-limit-store=local` keeps the in-process windows |
| Budgets (ADR 0025) | usage ledger sums + `budget_state` row per scope with optimistic locking |

## Storage and data lifecycle

| Data | Where | Retention (defaults, configurable) |
|---|---|---|
| Artefacts ≤ 64 kB | PostgreSQL | with the run |
| Artefacts > 64 kB, diffs, runner logs, uploads | object storage `runs/<runId>/…`, `jobs/<jobId>/…` | 180 days (logs 30) |
| `event_log` | PostgreSQL | 24 h |
| `work_item` DONE | PostgreSQL | 7 days |
| Audit log | PostgreSQL, monthly partitions `audit_log_pYYYY_MM` + `audit_log_default` (V404, kept 3 months ahead by the identity retention job) | 2 years, then export (`backoffice.retention.audit.*`) |
| LangGraph checkpoints of finished runs | PostgreSQL (`lg4j*` tables) | 30 days after finish (projects retention job) |
| Sessions | Spring Session JDBC | expiry + cleanup job |

## Observability

- **Traces**: OpenTelemetry (Micrometer bridge) for HTTP, JDBC, LLM calls (`gen_ai.*`), work items, runner
  protocol; trace id in logs and in audit rows next to the correlation id.
- **Metrics** (Prometheus scrape or OTLP): `backoffice_work_items{kind,status}`, `backoffice_work_latency_seconds`,
  `backoffice_runs_active{holder}`, `backoffice_gate_wait_seconds`, `backoffice_runner_jobs{pool,status}`,
  `backoffice_sandbox_start_seconds`, `backoffice_llm_tokens_total{provider,model,kind}`,
  `backoffice_llm_cost_usd_total`, `backoffice_budget_utilisation{scope}`, `backoffice_sse_subscribers`,
  JVM/HTTP/Hikari defaults.
  Implemented by platform-runtime / projects: `backoffice_work_items{kind,status}` (READY/LEASED/DEAD, refreshed
  every 15 s), `backoffice_work_oldest_ready_age_seconds`, `backoffice_work_latency_seconds{kind}` (due -> leased),
  `backoffice_work_duration_seconds{kind,outcome}`, `backoffice_event_listener_connected`,
  `backoffice_event_listener_reconnects_total`, `backoffice_events_delivered_total`, `backoffice_runs_active{holder}`;
  `backoffice_sse_subscribers` is reported in the operations overview (`sseSubscribers`) rather than as a meter.
  `backoffice_gate_wait_seconds` is not implemented yet.
- **Logs**: JSON (ECS format) to stdout; the `AUDIT` logger stays a separate stream.
- **Dashboards and alerts**: Grafana dashboards and PrometheusRules shipped in `deploy/observability/`.

## SLOs (starting point)

| SLO | Target |
|---|---|
| API availability (non-5xx on `/api/**`) | 99.9 % monthly |
| p95 API latency (reads) | < 300 ms |
| Work item pickup (READY → LEASED) p95 | < 5 s |
| Sandbox start with warm pool p95 | < 3 s (cold < 60 s) |
| Event delivery (commit → SSE) p95 | < 2 s |

## Capacity guide

| Size | Users / concurrent runs | api | worker | PostgreSQL | Runner pool |
|---|---|---|---|---|---|
| Small | 10 / 5 | 1 × (api,worker) 1 vCPU 1.5 GiB | — | 2 vCPU 4 GiB | 1 controller, 5 sandboxes 2 vCPU 4 GiB |
| Medium | 100 / 50 | 3 × 1 vCPU 1 GiB | 3 × 1 vCPU 1.5 GiB | 4 vCPU 16 GiB + PgBouncer | 2 controllers, 50 sandboxes |
| Large | 1000 / 500 | 6–12 | 10–30 (KEDA) | 16 vCPU 64 GiB, read replica for insights | several pools/clusters |

Connection budget: api 10, worker 10 per pod (Hikari), via PgBouncer (transaction mode is incompatible with
LISTEN; the listener uses one dedicated direct connection per pod).

## Upgrades and resilience

- Rolling updates with `maxUnavailable: 0`; PodDisruptionBudgets; topology spread across zones.
- Graceful shutdown: api drains HTTP and closes SSE with `retry`; workers stop claiming, finish or release
  leases within `terminationGracePeriodSeconds` (60 s); runner-controllers stop leasing, let sandboxes
  finish or hand back leases.
- Backward-compatible migrations only (expand → migrate → contract across two releases).
- Backups: managed PostgreSQL PITR (or CloudNativePG + object storage); object storage versioning.

## Maintainability practices

- Hexagonal modules and ArchUnit rules (GR-A*), deny-by-default gate (GR-A4), detekt blocking with a
  baseline for legacy findings, dependency-cruiser on the frontend, OpenAPI contract first.
- Renovate for dependencies (grouped, weekly, automerge patch for test deps), SBOM and image signing
  (cosign keyless) in CI, Trivy gates.
- Load tests (k6) in `perf/` and the query-plan review (`:app:perfTest`) with a documented baseline in
  [performance.md](performance.md); performance regressions checked before releases.
