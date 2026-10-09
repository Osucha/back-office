# Performance tests

Two tools, both documented with their latest results in
[docs/architecture/performance.md](../docs/architecture/performance.md):

- **Query-plan review** (`backend/app/src/test/kotlin/pl/mos/backoffice/app/perf`): seeds a large dataset
  into a throw-away PostgreSQL and records `EXPLAIN ANALYZE` of the hot queries with and without the
  review indexes.
- **Load tests** (`perf/k6`): k6 scenarios against a running API, with thresholds taken from the SLOs in
  [09-scale-and-operations.md](../docs/architecture/09-scale-and-operations.md).

Run both before a release and compare with the recorded baseline; a regression of more than 20 % on a
p95 or a threshold failure needs an explanation in the release notes.

## Query-plan review

```bash
cd backend
./gradlew :app:perfTest --no-daemon                        # 50 projects, 200k runs, 2M usage rows, 1M audit rows
./gradlew :app:perfTest -Dperf.runs=50000 -Dperf.usage=500000 -Dperf.audit=200000   # smaller, faster
```

Needs Docker (Testcontainers `postgres:17-alpine`). The report is written to
`backend/app/build/perf/query-plans.md`. "Before" numbers are measured inside a transaction that drops the
indexes of the review migrations (`V104__query_review_indexes.sql`, `V2__usage_query_indexes.sql`,
`V405__audit_query_indexes.sql`) and is rolled back afterwards.

The same generator can seed an existing database (for load tests against realistic volumes). It is
idempotent (skips when project `PERF1` exists) and copies the JSON documents of the newest run and CI
execution, so create one real CI execution first (the `ci.js` scenario does):

```bash
./gradlew :app:perfTest -Dperf.jdbcUrl=jdbc:postgresql://localhost:5499/backoffice -Dperf.user=backoffice -Dperf.password=backoffice
```

## Load tests (k6)

| Scenario | File | What | Thresholds |
|---|---|---|---|
| Login | `k6/login.js` | local account login (`/auth/config` → `/auth/login`) + `/me` | p95 < 800 ms (Argon2id), errors < 0.1 % |
| Reads | `k6/reads.js` | home (`/dashboard`, `/me`, `/approvals`), runs list (filters, deep page, per project), CI executions list, run detail (+ artefacts, logs) | p95 of every read < 300 ms, errors < 0.1 % |
| CI execution | `k6/ci.js` | `POST /ci/executions` with a `bo_ci_` token + poll until finished | POST p95 < 500 ms, poll p95 < 300 ms, completion p95 < 15 s, 99 % complete |
| SSE | `k6/sse.js` | N clients hold `/events` subscriptions while the read scenario runs | 99 % of subscriptions held, reads p95 < 300 ms |

Common environment variables: `BASE_URL` (default `http://localhost:8095`), `BO_EMAIL` / `BO_PASSWORD`
(a local account with `platform-admin`), `DURATION` (default `1m`), `VUS`, `READ_P95_MS`.

### Against a local install

1. Start a dedicated PostgreSQL (never the shared dev database; schema versions differ between branches):

   ```bash
   docker run -d --name bo-perf-pg -p 5499:5432 -e POSTGRES_DB=backoffice -e POSTGRES_USER=backoffice -e POSTGRES_PASSWORD=backoffice postgres:17-alpine
   ```

2. Build and start the API on a free port with a local bootstrap admin and the login throttle raised
   (one k6 host is one IP):

   ```bash
   cd backend && ./gradlew :app:bootJar --no-daemon
   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5499/backoffice \
   SERVER_PORT=8095 BACKOFFICE_PUBLIC_URL=http://localhost:8095 BACKOFFICE_COOKIE_SECURE=false \
   RUNNER_TOKEN_SECRET=perf BACKOFFICE_AUTH_LOCAL_SECRET_KEY=$(openssl rand -base64 32) \
   BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_EMAIL=perf-admin@example.com \
   BACKOFFICE_AUTH_LOCAL_BOOTSTRAP_ADMIN_PASSWORD=Load-Test-Passw0rd-2026 \
   BACKOFFICE_AUTH_LOCAL_THROTTLE_MAX_ATTEMPTS=1000000 \
   java -jar app/build/libs/app.jar
   ```

   The bootstrap admin must change its password on first login (`PASSWORD_CHANGE_REQUIRED`): log in once
   in the UI (or `POST /api/v1/auth/password/change`) and set the password the scripts use
   (default `K6-Baseline-Secret-77`, or pass `BO_PASSWORD`).

3. Seed volume (optional but recommended): run `k6/ci.js` once for a few seconds so a real run exists,
   then the generator with `-Dperf.jdbcUrl=...` (above), then restart nothing — the API reads the new rows.

4. Run the scenarios, with a local k6 or with Docker (on Docker Desktop use `host.docker.internal`,
   `--network host` does not reach the host there):

   ```bash
   k6 run perf/k6/reads.js
   docker run --rm -v "$PWD/perf/k6:/scripts" -e BASE_URL=http://host.docker.internal:8095 grafana/k6 run /scripts/reads.js
   ```

   Add `--summary-export=perf/results/<scenario>.json` to keep the numbers.

### Against a cluster install

Point `BASE_URL` at the ingress (`https://backoffice.example.com`), create a dedicated local account with
`platform-admin` for the test and pass it as `BO_EMAIL` / `BO_PASSWORD`, and raise
`auth.local.throttle.maxAttempts` for the duration of the test (or run k6 from several IPs). Run the load
generator close to the cluster (same region) so network latency does not dominate, and watch the Grafana
dashboards (`deploy/observability/`) for Hikari pool saturation, GC and PostgreSQL CPU while it runs.
Never run the load tests against production data without the owners' consent: the CI scenario creates a
project and executions.

### Limits of the stock k6 image

- SSE bodies cannot be read incrementally, so `sse.js` measures held subscriptions and read latency
  under subscriber load, not commit → event latency (SLO p95 < 2 s). Use the `xk6-sse` extension for that.
- Without provider keys the CI review step fails immediately; the CI numbers are the platform overhead.
