# Runbook: scaling api, workers and runners

## When to use this runbook

Latency or error alerts fire (`BackOfficeApiSlowReads`, `BackOfficeApiHighErrorRate`), work waits
(`BackOfficeWorkQueueStalled`, dashboard *Workflows & gates*), agent jobs queue up
(`BackOfficeRunnerPoolWithoutRunners`, dashboard *Runner fleet*), PostgreSQL runs out of connections, or
KEDA does not scale. Background: [09-scale-and-operations.md](../architecture/09-scale-and-operations.md),
[cloud-reference.md](../guides/cloud-reference.md#sizing-from-doc-09).

## What scales on what

| Role | Scaler | Signal | Knobs |
|---|---|---|---|
| `api` | HPA | CPU 70 % (optionally requests/s per pod through a custom-metrics adapter) | `api.autoscaling.*`, `api.resources` |
| `worker` | KEDA PostgreSQL scaler | `count(*) FROM work_item WHERE (READY and due) OR LEASED` / `targetQueryValue` (20) | `worker.autoscaling.keda.*`, min/max |
| `runner-controller` | KEDA metrics-api | `queued` of `/api/v1/runner/v2/pools/{id}/queue` / `maxConcurrent` | runner chart `autoscaling.*`, `maxConcurrent` |
| sandboxes | node autoscaler | pending sandbox pods | sandbox node pool size |
| `mode: combined` | HPA on the single Deployment | CPU | small installs only; switch to `mode: split` beyond ~20 concurrent runs |

## Checks

```bash
NS=back-office
kubectl -n $NS get hpa,scaledobject,deploy
kubectl -n $NS describe scaledobject back-office-worker | sed -n "/Conditions/,/Events/p"
kubectl -n $NS get hpa keda-hpa-back-office-worker -o wide           # the HPA KEDA manages
# queue as KEDA sees it (run inside the cluster or through port-forward to PostgreSQL)
psql "$DIRECT_URL" -c "SELECT kind, status, count(*), min(run_after) FROM work_item WHERE status IN ('READY','LEASED','DEAD') GROUP BY 1,2"
psql "$DIRECT_URL" -c "SELECT count(*), state FROM pg_stat_activity WHERE datname = current_database() GROUP BY 2"
kubectl -n keda logs deploy/keda-operator | grep -i back-office | tail
```

## Scale by hand

```bash
# api: raise the floor (HPA keeps working)
helm upgrade back-office deploy/helm/back-office -n back-office --reuse-values \
  --set api.autoscaling.minReplicas=4 --set api.autoscaling.maxReplicas=12
# workers: more headroom, or pin a fixed count while KEDA is broken
helm upgrade ... --reuse-values --set worker.autoscaling.maxReplicas=40
helm upgrade ... --reuse-values --set worker.autoscaling.type=none --set worker.replicas=6
# emergency without helm (KEDA/HPA will fight a manual scale - pause KEDA first)
kubectl -n back-office annotate scaledobject back-office-worker autoscaling.keda.sh/paused-replicas="6" --overwrite
kubectl -n back-office annotate scaledobject back-office-worker autoscaling.keda.sh/paused-replicas-      # resume
# runners: more controllers or more sandboxes per controller
helm upgrade runners deploy/helm/back-office-runner -n back-office-runners --reuse-values \
  --set autoscaling.maxReplicas=8 --set maxConcurrent=10
```

Scaling workers helps only when work is **ready**: runs waiting at a human gate or for CI hold no
worker. A high `backoffice_gate_wait_seconds` is a people problem, not a capacity problem.

## PostgreSQL connection budget

Every app pod opens `dbPoolSize` Hikari connections (default 10) plus **one direct listener connection**
(LISTEN/NOTIFY, api role; workers use it for `backoffice_work` wake-ups). Add KEDA's poll connection,
migrations, CNPG/pgbouncer overhead and a superuser reserve.

```
connections = (api_max + worker_max) × (dbPoolSize + 1) + KEDA (1-2) + migrate (2) + reserve (10)
medium:  (12 + 30) × 11 + 2 + 2 + 10 = 476   -> needs a pooler or smaller pools
with a transaction pooler: server side = pooler default_pool_size (e.g. 25) × pooler instances
                            + (api_max + worker_max) direct listener connections + reserve
```

Actions, cheapest first:

1. Put a transaction pooler in front of the Hikari pools: CNPG `cnpg.pooler.enabled`, RDS Proxy /
   Azure PgBouncer 6432 / Cloud SQL managed pooling via `externalDatabase.pooler`, or `pgbouncer.enabled`.
   The direct URL (`BACKOFFICE_DATASOURCE_DIRECT_URL`) stays on the primary - never point it at a
   transaction pooler (LISTEN breaks).
2. Lower `api.config.dbPoolSize` (virtual threads wait for connections cheaply; watch
   `hikaricp_connections_pending` / `BackOfficeHikariPoolExhausted`).
3. Raise `max_connections` (CNPG `cnpg.parameters.max_connections`, managed parameter groups) together
   with memory.
4. Lower `worker.autoscaling.maxReplicas`: more workers than the DB can serve only add contention.

## KEDA does not scale

| Symptom | Cause | Fix |
|---|---|---|
| ScaledObject `READY False`, events `error establishing postgreSQL connection` | wrong host/port/sslmode or password Secret; NetworkPolicy blocks the KEDA operator | check `worker.autoscaling.keda.sslmode` (`require` on managed DBs), allow the `keda` namespace to reach the DB (`networkPolicy.database.cidrs` covers only the app pods - DB firewalls must allow the KEDA pods too) |
| `relation "work_item" does not exist` | migrations not applied yet | wait for / fix the migrate Job ([upgrade.md](upgrade.md)) |
| ScaledObject `ACTIVE False` with work queued | query returns 0: items have `run_after` in the future (backoff), or all are `DEAD` | inspect `work_item`; retry DEAD items in Administration → Operations |
| scales up, never down | long `cooldownPeriod`/`scaleDownStabilizationSeconds` (300 s) and LEASED items counted - by design; leases are released on shutdown | lower the stabilisation window if cost matters |
| runner ScaledObject in fallback | runner token Secret missing (controller never registered) or Back Office unreachable from KEDA | register the controller; check egress from the `keda` namespace |
| two scalers fight | an HPA and a ScaledObject target the same Deployment | use one: `worker.autoscaling.type` is `keda` **or** `hpa` |
| KEDA metrics API unavailable (`v1beta1.external.metrics.k8s.io` false) | keda-operator-metrics-apiserver down, or another adapter owns the APIService | `kubectl get apiservice v1beta1.external.metrics.k8s.io`; only one external metrics provider per cluster |

## Events delayed (`BackOfficeEventListenerReconnecting`)

The LISTEN connection of an api pod keeps dropping: it points at a transaction pooler (fix the direct
URL), an idle timeout on a proxy/NAT/firewall cuts it (TCP keepalive, or bypass the proxy), or the DB
failed over (expected once per failover). SSE clients recover through `Last-Event-ID` replay from
`event_log` within 24 h, so no events are lost - only delayed.

## Where to look when it fails

- Dashboards *Platform overview* (latency, Hikari, pods by role), *Workflows & gates* (queue, pickup
  latency), *Runner fleet* (queued/running per pool, sandbox start time).
- `kubectl top pods -n back-office`, CPU throttling (`container_cpu_cfs_throttled_seconds_total`): api pods
  have no CPU limit by default for this reason.
- Related: [stuck-pipeline-run](stuck-pipeline-run.md), [runner-job-failed](runner-job-failed.md), [upgrade](upgrade.md).
