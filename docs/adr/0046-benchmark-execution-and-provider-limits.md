# 0046 — Benchmark execution targets, provider limits and a one-step local stack

- Status: accepted
- Date: 2026-10-19
- Deciders: owner
- Related: ADR 0020 (provider rate limits), 0024 (runner fleet), 0040 (personal runners), 0045 (provider connections),
  0021 (local accounts); docs/manual/evaluation.md, docs/manual/integrations-llm.md, docs/guides/local-development.md

## Context

The first benchmark on free OpenRouter models showed four gaps:

- **Lost work.** When the judge got a 429 after the candidate had answered, the retry started the attempt over and asked
  the candidate again; six retries per attempt multiplied the calls. 38 candidate answers produced one result.
- **Errors that never clear were retried.** A daily quota used up (`free-models-per-day`) was treated like a short rate
  limit, and a model the key cannot use (403) failed each of its 21 attempts one by one, every failure another call.
- **No limits of our own.** A free tier allows 50 requests a day; nothing stopped Back Office before the provider did.
- **Invisible execution.** Coding attempts were submitted with no runner pool to go to (no shared pool existed, the
  personal runner was not chosen) and stayed `RUNNING` without any hint.

The local stack also needed four commands and a Keycloak the owner does not use.

## Decision

1. **Persistent provider failures.** `IntegrationException` gets `persistent`: every further call of the kind fails the
   same way until somebody acts. The gateway sets it for 401, 402, 403, 404, a 429 that names a daily quota or credits,
   a missing connection and a reached daily limit of a connection; such failures are not retried.
2. **Benchmarks stop early and keep answers.** A retry after the judge failed reuses the stored answer (the attempt
   stays with its output when requeued). A persistent failure of a candidate ends the waiting attempts of that model with
   "skipped, ... failed: ..."; a persistent failure of the judge ends all waiting attempts. Attempts already with a
   runner finish on their own.
3. **Check before start.** `POST /benchmarks/preflight` (`evaluation.run`) sends the one-line model test to every
   candidate and the judge; "Create and start" runs it first and offers "Start anyway" when a model does not answer.
4. **Execution target.** A benchmark has `runnerTarget` `SHARED` (the shared fleet) or `PERSONAL` (the creator's personal
   runner, `RunnerJobSpec.personalRunnerOf`). `GET /benchmarks/execution-targets` lists both with their pools and online
   runners (port `AgentRunner.executionTargets`); start refuses coding tasks when the chosen target has no pool, the form
   warns when no runner is online and the benchmark page says that coding attempts are waiting for a runner.
5. **Connection limits.** A provider connection may set requests per minute and per day. `ConnectionQuota` counts every
   request through the connection, from the gateway and from the agents' LLM proxy, in fixed UTC buckets in PostgreSQL
   (`llm_connection_request_count`, llm-gateway V7), so all replicas share one count. Per minute is retryable, per day is
   persistent until 00:00 UTC. The OpenRouter preset fills 20 a minute and 50 a day (its free tier without credits);
   the providers page shows today's count.
6. **One-step local stack.** `deploy/compose/start.cmd` (and `start.sh`) builds and starts the `full` profile and opens
   the UI. Keycloak moves to the profile `keycloak`; OIDC is off unless an issuer is configured, and local accounts sign
   in. The bootstrap administrator also covers an installation that drops its identity provider: an active platform
   administrator with the bootstrap e-mail and no password yet gets the bootstrap password once (change forced at the
   first sign-in; an existing password is never touched). With `backoffice.runner.local-fleet.registration-token` the API
   creates the shared pool `default` and accepts that registration token, so the bundled runner-controller registers by
   itself (laptop stacks only).

## Consequences

- Free tiers can be used for benchmarks without burning the day's quota on retries; failures say why at once.
- Limits are counted per connection key; environment endpoints have none. Requests refused by our limit are not sent.
- The fixed local registration token and the DEV isolation ceiling of the compose stack are for laptops only; the Helm
  chart sets neither.
- Benchmarks created before V203 run on the shared fleet, as before.
- Follow-up after the first real benchmark: a timed out provider call is no longer repeated inside the call (the
  attempt retries later with its own backoff), the judge answers within 3000 output tokens, the leaderboard times are
  the candidate's own (`benchmark_attempt.candidate_latency_ms`, evaluation V204) over succeeded attempts, the form
  warns about a judge that is also a candidate and about sample repositories, and the starter coding tasks run against
  Spring PetClinic pinned to a commit (suites still on the placeholder repository are upgraded at startup).
- Column sorting: client-side tables sort in the browser (`tableSort`, `th[boSort]`); the server-paged lists (runs, CI
  executions, users, audit log, usage entries, agent jobs) take `sort=field.asc|desc` (`SortSpec`, a whitelist of
  fields per list mapped to columns, natural order without it; the audit log only by time, keyset cursor both ways).
  The UI keeps either in the URL.
- `backend/.dockerignore` excluded `**/out`, which also dropped the source packages `application/port/out`, so the
  `full` profile could not build the API image; it now excludes only the IDE output directories (`out`, `*/out`).
