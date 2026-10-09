# Runbook: upgrades, failed migrations and rollback

## When to use this runbook

You upgrade Back Office (`helm upgrade`), the migrate hook Job failed, a release has to be rolled back,
you plan a schema change that must not break running pods (expand/contract), or you rotate the signing
key. Background: ADR 0023 (migrate role), [deploy-helm.md](../guides/deploy-helm.md#4-upgrade).

## How an upgrade runs

1. **Hooks, pre-upgrade**: the signing-key Job (only creates a missing Secret), then the **migrate Job**
   `<fullname>-migrate` (`BACKOFFICE_ROLE=migrate`, Flyway, exits). Helm waits for it; if it fails, the
   upgrade stops **before any pod changes** - the old version keeps running.
2. **Rollout**: api and worker Deployments roll with `maxSurge 1, maxUnavailable 0`; PDBs keep one pod of
   each; api pods sleep `preStopSleepSeconds` then drain HTTP/SSE; workers stop claiming and finish or
   release leases within `terminationGracePeriodSeconds` (90 s). Runs continue on other workers from
   their PostgreSQL checkpoints.
3. On a fresh install with an in-release database (bundled / CNPG) or External Secrets, the database
   does not exist during pre-install: then every app pod runs the migrate role as an **init container**
   (Flyway's advisory lock serialises them) - `migration.initContainer=auto`.

Application pods always run with `SPRING_FLYWAY_ENABLED=false`.

## Routine upgrade

```bash
helm diff upgrade back-office deploy/helm/back-office -n back-office -f values-prod.yaml --set api.image.tag=$SHA   # helm-diff plugin, optional
helm upgrade back-office deploy/helm/back-office -n back-office -f values-prod.yaml \
  --set api.image.tag=$SHA --set web.image.tag=$SHA --wait --timeout 20m
kubectl -n back-office get jobs,pods -l app.kubernetes.io/instance=back-office
```

Upgrade the runner fleet after the control plane (the runner protocol is backwards compatible within a
major version; the control plane accepts the previous controller version).

## The migrate Job failed

```bash
kubectl -n back-office get job back-office-migrate
kubectl -n back-office logs job/back-office-migrate --tail=200
```

| Log | Cause | Fix |
|---|---|---|
| `Connection refused` / `timeout` / `no pg_hba.conf entry` | DB unreachable from the Job (NetworkPolicy `migrate` rule, DB firewall, wrong `externalDatabase.host`, pooler used as host) | fix connectivity; `externalDatabase.host` must be the primary endpoint |
| `password authentication failed` | wrong Secret/key (`externalDatabase.existingSecret/passwordKey`) | fix the Secret, rerun |
| `Validate failed: Migration checksum mismatch` | an applied migration file was edited | never edit applied migrations; restore the file from the released version, or (after review) `flyway repair` |
| `Detected applied migration not resolved locally` | the DB is newer than the image (downgrade attempt) | deploy the newer image, or restore the DB backup of the older version |
| SQL error in `Vxxx__*.sql` | migration bug; Flyway marks it failed (PostgreSQL DDL is transactional, so the change was rolled back) | fix forward in a new image; `flyway repair` removes the failed row if needed |
| `lock timeout` / waits forever | a long transaction or an old pod holds a lock on the table being altered | find it: `SELECT pid, state, query_start, query FROM pg_stat_activity WHERE state <> 'idle' ORDER BY query_start`; migrations should use `lock_timeout` |
| Job `DeadlineExceeded` | migration longer than `migration.activeDeadlineSeconds` (1800) | raise it for that release; big backfills belong in a worker job, not in DDL migrations |

Rerun after fixing: `helm upgrade` again (the hook is recreated, `before-hook-creation`). The previous
release keeps serving meanwhile.

## Rollback

```bash
helm history back-office -n back-office
helm rollback back-office <revision> -n back-office --wait
```

`helm rollback` redeploys the old images and values; it does **not** undo migrations. That is safe only
because every migration is backwards compatible (expand/contract below): version N-1 must run on schema
N. If a release broke that rule, roll forward with a fix, or restore the database (PITR to before the
upgrade: RDS/Cloud SQL/Azure point-in-time restore, CNPG `bootstrap.recovery` from the Barman backup)
and roll back - this loses data written since.

Rollback does not re-run pre-upgrade hooks of the old version (the migrate Job of the older image would
find a newer schema and fail with "applied migration not resolved locally" if it ran with
`validateOnMigrate`); this is expected and harmless.

## Expand / migrate / contract

A schema change that renames or removes something is split across **two releases**:

| Release | Schema | Code |
|---|---|---|
| N (expand) | add the new column/table, nullable or with a default; backfill in batches (worker job or `RETENTION`-style work item), triggers/dual-write if needed | writes both old and new, reads old (or new with fallback) |
| N+1 (migrate) | - | reads and writes only the new structure |
| N+2 (contract) | drop the old column/table | - |

Rules that keep rollbacks safe:

- Never rename or drop in the same release that stops using it; never add `NOT NULL` without a default in
  one step on a large table (add nullable → backfill → `SET NOT NULL` via `NOT VALID` constraint + `VALIDATE`).
- Create indexes with `CREATE INDEX CONCURRENTLY` in their own migration (Flyway: `-- flyway:executeInTransaction=false`).
- Keep work-item payloads and event payloads backwards compatible (additive JSON): old and new workers
  process the same queue during a rollout.
- Flyway version ranges per bounded context (CLAUDE.md) and `out-of-order: true` stay as they are.

## Signing key rotation (ADR 0025)

```bash
# 1. add key 1 as active, keep key 0 for verification
deploy/scripts/gen-signing-key.sh --secret back-office-signing -n back-office --index 1 --merge
kubectl -n back-office patch secret back-office-signing --type merge -p '{"stringData":{"BACKOFFICE_SIGNING_KEYS_0_ACTIVE":"false"}}'
# 2. roll the pods (envFrom is read at start)
kubectl -n back-office rollout restart deploy/back-office-api deploy/back-office-worker
curl -s https://back-office.example.com/.well-known/back-office/keys     # both keys published
```

Never delete an old key while attestations signed with it must stay verifiable. With External Secrets
make the same change in the secret store.

## PostgreSQL major upgrade

Managed services: in-place major upgrade (RDS/Aurora, Cloud SQL, Azure flexible server) in a maintenance
window after a snapshot; scale workers to 0 first (`worker.autoscaling.type=none --set worker.replicas=0`)
so no leases are held. CNPG: import into a new `Cluster` of the new major (`bootstrap.initdb.import`) or
use its in-place major upgrade (CNPG ≥ 1.26), then switch `cnpg.clusterName`. Bundled Bitnami: dump and
restore - it is not meant for production data.

## Where to look when it fails

- `helm upgrade` hangs: it waits for the hook Job - `kubectl get jobs -w`; `--timeout` bounds it.
- New pods `CrashLoopBackOff` with `Schema-validation: missing table` - the migrate Job did not run
  (`migration.enabled=false`?) or ran against another database.
- `Error: UPGRADE FAILED: pre-upgrade hooks failed` - see "The migrate Job failed".
- Related: [scaling](scaling.md), [stuck-pipeline-run](stuck-pipeline-run.md).
