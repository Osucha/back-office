# Running a benchmark

## When to use this guide

You want a number, not an opinion, for questions like "is Sonnet good enough for spec writing in my
projects?" or "which model should implement tickets in this Kotlin codebase, and what does a ticket
cost?". This guide walks through the Model Evaluation context end to end: catalogue entries, task
suites, tasks of both kinds, starting a benchmark, reading the leaderboard and applying the result to
pipeline steps. Concepts are described in
[04-model-evaluation.md](../architecture/04-model-evaluation.md); the API is in
[openapi.yaml](../api/openapi.yaml) (tags `model-profiles`, `task-suites`, `benchmarks`, `usage`).

Every call below runs as a signed-in user (session cookie) or with a personal access token
(`Authorization: Bearer bo_pat_...`). The UI (Model catalogue, Task suites, Benchmarks, Costs pages)
does the same calls.

### Permissions (ADR 0017)

| Step | Permission | Built-in role that has it |
|---|---|---|
| list providers and model profiles | `models.view` | `member` (SYSTEM default) |
| create / edit / delete profiles and pricing, `POST .../test` | `models.manage` | `platform-admin` |
| read suites, benchmarks, attempts, leaderboards | `evaluation.view` | `member` |
| create / edit suites and tasks; create, start, cancel, delete benchmarks | `evaluation.run` | `evaluator` |
| `GET /api/v1/usage/...` without a project filter (e.g. `groupBy=BENCHMARK`) | `usage.view` | `auditor`, `platform-admin` |

Missing permissions answer `403` and leave an `ACCESS_DENIED` row in the audit log. Changes are
audited: `MODEL_PROFILE_CREATE/UPDATE/DELETE` (the update diff lists every pricing component),
`MODEL_TEST`, `SUITE_*`, `EVAL_TASK_*`, `BENCHMARK_CREATE/START/CANCEL/DELETE`. Benchmarks show
`createdBy`, profiles and suites `updatedBy`.

## 1. Check providers and create model profiles

```
GET /api/v1/model-profiles/providers
```

returns one row per provider with `configured` (a key or base URL is present on the server) and, for
`OLLAMA` and `OPENAI_COMPATIBLE`, the base URL. Set keys through the environment
(`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_GENAI_API_KEY`, `MISTRAL_API_KEY`, `OLLAMA_BASE_URL`,
`OPENAI_COMPATIBLE_BASE_URL` + `OPENAI_COMPATIBLE_API_KEY`) and restart.

No profiles are seeded (ADR 0045). Connect a provider (Models > LLM providers), then pick its models in
the section "Available from providers" of the Models page: `GET /api/v1/llm-providers/catalog` lists the
models of every usable connection, with prices and context windows where the provider publishes them
(OpenRouter), and "Add as profile" prefills the profile. Check prices before you trust cost figures: the
ledger computes cost from the profile that matches the call.

Create your own entry:

```json
POST /api/v1/model-profiles
{
  "displayName": "Sonnet 5 (spec writer)",
  "ref": { "provider": "ANTHROPIC", "modelId": "claude-sonnet-5",
           "options": { "reasoning": "MEDIUM", "maxOutputTokens": 8192 } },
  "pricing": { "inputPerMTok": 2, "outputPerMTok": 10 },
  "capabilities": ["REASONING", "TOOLS"],
  "contextWindow": 200000,
  "notes": "List price 2026-06"
}
```

`options.reasoning` is vendor-neutral (`NONE, LOW, MEDIUM, HIGH, XHIGH, MAX`) and mapped per provider
(Anthropic adaptive thinking + effort, OpenAI `reasoning_effort`, Gemini `thinkingLevel`, Mistral
`reasoningEffort`, Ollama `think`). Two profiles may point at the same model with different options,
which is exactly how you measure whether `HIGH` reasoning is worth its price.

Smoke-test the entry before using it:

```
POST /api/v1/model-profiles/{id}/test
-> { "ok": true, "latencyMs": 812, "usage": {...}, "cost": {"amount": 0.00013, "currency": "USD"}, "sample": "OK" }
```

A failing test returns `ok: false` with the provider error in `error` (see the
[provider errors runbook](../runbooks/provider-errors.md)). The call is booked as a `MODEL_TEST` usage entry.

## 2. Create a task suite

```json
POST /api/v1/task-suites
{ "name": "SHOP tickets Q3", "description": "Real tickets from the shop backlog",
  "codingScoreWeights": { "verification": 0.6, "judge": 0.4 } }
```

`codingScoreWeights` decides how coding attempts are scored: `0.6 * (tests passed ? 100 : 0) + 0.4 *
judge score`. Prompt tasks use the judge score directly and pass at 70.

The seeded suite `Starter: SDLC tasks` (12 tasks) is a template; copy the shape, replace the content.
Good suites have 8-20 tasks, mixed categories and difficulties, and at least two tasks per category
so the breakdowns mean something.

## 3. Add tasks

### PROMPT task (analysis, specification, review, architecture)

The candidate gets `instructions` (+ `context`) as one chat call; the judge grades the answer with the
`rubric` and optional `referenceAnswer`.

```json
POST /api/v1/task-suites/{suiteId}/tasks
{
  "name": "Spec for saved carts",
  "category": "SPECIFICATION",
  "difficulty": "MEDIUM",
  "kind": "PROMPT",
  "instructions": "Turn the ticket into a Spec Kit spec.md with: Overview, User scenarios (Given/When/Then), Functional requirements FR-1.., Key entities, Out of scope, Open questions tagged [NEEDS CLARIFICATION]. Requirements must be testable and implementation-agnostic.",
  "context": "Ticket SHOP-482: Customers should be able to save a cart for later and restore it on another device. Carts older than 30 days can be dropped. Customers log in with email; carts live in the browser session today.",
  "rubric": "- Structure (0-10): all sections present; scenarios in Given/When/Then.\n- Requirement quality (0-10): testable, unambiguous, no technology names; covers save, restore on another device, 30-day expiry, conflict with an existing cart on the current device.\n- Open questions (0-10): at least three real product decisions, each tagged.\n- Scope discipline (0-10): out-of-scope section keeps it small; nothing invented.",
  "referenceAnswer": null,
  "timeoutSeconds": 600
}
```

What makes a rubric work:

- Name 3-5 criteria, each with a 0-10 scale and a one-line definition of what 10 looks like. The judge
  returns a score per criterion plus an overall 0-100; criteria without definitions produce noise.
- Put the things you would reject in a code review or a spec review into the rubric explicitly
  ("no technology names", "assumptions labelled", "finds the three defects").
- For tasks with a known answer (root cause, planted bugs) give a `referenceAnswer`. The judge is
  told to treat it as one good answer, not the only one.
- Keep instructions and rubric aligned: do not grade for something you did not ask for.

### CODING_JOB task

The task runs in the agent runner (OpenCode harness by default, Claude Code optional per benchmark)
against a real repository. The runner clones `repository.url` at `baseRef`, runs `setupCommand`, lets
the agent work on branch `eval/<attempt>` (never pushed), then runs `verifyCommand`. `passed` is
`exit code == 0`; the judge scores the summary, diff statistics and verification output against the rubric.

```json
POST /api/v1/task-suites/{suiteId}/tasks
{
  "name": "Add validated POST /api/customers",
  "category": "CODING",
  "difficulty": "EASY",
  "kind": "CODING_JOB",
  "instructions": "Add POST /api/customers with body {email, displayName}. Email must be valid and unique (409 on duplicate); displayName 2-80 chars trimmed. Respond 201 with Location header; validation errors as RFC 9457 problem details with a per-field errors array. Follow the existing package layout. Add unit tests for validation and web tests for 201/400/409. Existing tests must stay green.",
  "rubric": "- Correctness (0-10): behaves as specified for 201, 400, 409.\n- Tests (0-10): validation and web tests exist for all three cases.\n- Fit (0-10): follows the repository conventions instead of a parallel style.\n- Hygiene (0-10): focused diff, no unrelated changes, no debug output.",
  "repository": {
    "url": "https://gitlab.example.com/samples/kotlin-sample.git",
    "baseRef": "main",
    "setupCommand": "./gradlew --offline help || true",
    "verifyCommand": "./gradlew test"
  },
  "timeoutSeconds": 1200
}
```

Requirements for the repository: the runner image can clone it (public, or credentials in the runner
integrations secret), `verifyCommand` is green on `baseRef`, and it finishes well inside
`timeoutSeconds` (the agent needs most of the budget). The seeded coding tasks point at a placeholder
URL and must be re-pointed before a benchmark that includes them can produce anything but failures.
`POST /benchmarks/{id}/start` is refused with 409 when coding tasks are selected and no runner is
available (`backoffice.runner.mode=DISABLED`).

## 4. Create and start a benchmark

```json
POST /api/v1/benchmarks
{
  "name": "Spec writers, September",
  "suiteId": "…",
  "modelProfileIds": ["<sonnet-5>", "<haiku-4-5>", "<gpt-5>"],
  "judgeModelProfileId": "<fable-5-1>",
  "repetitions": 3,
  "codingHarness": "OPENCODE",
  "taskIds": []
}
POST /api/v1/benchmarks/{id}/start   -> 202, status RUNNING
```

Rules of thumb:

- The judge is fixed per benchmark so scores are comparable across candidates. Use the strongest model
  you can afford as judge and never a model that is also a candidate.
- `repetitions` 2-3 gives you the `stability` metric (standard deviation across repetitions). Non-zero
  stability on `PROMPT` tasks is normal; large values mean the task or rubric is underspecified.
- `taskIds` empty runs the whole suite; a subset is useful to iterate on one rubric cheaply.
- Attempts are planned up front (tasks x models x repetitions) and persisted as `PENDING`, then executed
  with at most `backoffice.evaluation.concurrency-per-provider` (default 2) attempts in flight per
  provider. A restart re-queues what was in flight; coding jobs re-attach to their runner Job.
- Progress arrives on the SSE stream as `BenchmarkProgress` (`total`, `done`, `failed`) and the
  benchmark ends with `BenchmarkFinished`. `GET /benchmarks/{id}` shows the same counters and the cost so far.
- `POST /benchmarks/{id}/cancel` stops queued attempts and cancels running runner jobs; finished attempts keep their results.
- Individual failures do not fail the benchmark; it ends `FAILED` only when no attempt succeeded.

## 5. Read the leaderboard

```
GET /api/v1/benchmarks/{id}/leaderboard
```

| Field | Meaning |
|---|---|
| `meanScore` | mean judge score over succeeded attempts |
| `passRate` | share of attempts with `passed = true` (coding: tests green; prompt: score >= 70); failed and timed-out attempts count as not passed |
| `meanCost`, `totalCost` | candidate cost per attempt / overall (judge cost is on the attempt detail as `judgeCost` and in the benchmark total) |
| `scorePerDollar` | `meanScore / meanCost`, the efficiency number |
| `p50LatencyMs`, `p95LatencyMs` | wall clock per attempt, including runner time for coding tasks |
| `stability` | standard deviation of score across repetitions, averaged over tasks; null without repetitions |

`byCategory`, `byDifficulty` and `byTask` repeat the rows for each slice. The UI plots score against
cost so the Pareto frontier is visible; the same reading works from the JSON: a model that is not on
the frontier is dominated by another one that is both better and cheaper.

Drill into surprises with `GET /benchmarks/{id}/attempts?modelProfileId=…` and
`GET /benchmarks/{id}/attempts/{attemptId}` (full output, judge criteria and rationale, verification
output, runner job id, error). Read the judge rationale for the worst attempt of each model before
concluding anything; a low score is as often a rubric problem as a model problem.

## 6. Apply the result to pipeline steps

Pipeline steps refer to models by `modelProfileId` (`StepDefinition.modelProfileId`, falling back to
`Project.defaults.modelProfileId`; the review step uses `defaults.reviewModelProfileId`). Typical
outcome of a benchmark:

1. Set the cheapest model that clears your bar (for example passRate >= 0.9 and meanScore >= 80 in
   `SPECIFICATION`) as the project default for `LLM` and `SPEC_KIT` steps.
2. Keep a stronger model on the steps where the benchmark showed a real gap (`ARCHITECTURE`, hard
   `CODING`), not everywhere.
3. Prefer the profile (model plus reasoning level) that won, not just the model; `HIGH` reasoning on a
   mid-tier model often beats `LOW` on a premium one per dollar.
4. Re-run the same benchmark when a provider ships a new model or changes prices; benchmarks are cheap
   compared with a week of wrong defaults.

## 7. Cost caveats

- Every attempt costs candidate tokens plus judge tokens; judge cost is typically 20-40 % of the total
  for prompt tasks because the judge reads the task, the rubric and the output. Budget:
  `tasks x models x repetitions x (candidate + judge)`.
- Coding attempts are expensive: an agent loop reads a lot of the repository. Expect 0.5-5 USD per
  attempt on frontier models and cap `timeoutSeconds`. Claude Code reports its own cost, which the
  ledger books as `cost`; the price-list computation is kept as `estimatedCost` for comparison.
- Cost is only as good as the pricing on the profile. Placeholder prices (0/0) make a model look free.
- Cache-read tokens are priced separately when the profile has `cacheReadPerMTok`; otherwise they are
  priced as input.
- The ledger is the source of truth: `GET /api/v1/usage/summary?groupBy=BENCHMARK` and
  `GET /api/v1/usage/entries?benchmarkId=…` show every call.
- Provider rate limits: keep `EVAL_CONCURRENCY_PER_PROVIDER` at 2-3 unless you have raised tier limits.

## 8. Rate limits (ADR 0020)

The gateway limits requests and tokens per minute per provider when configured:

```yaml
backoffice:
  llm:
    rate-limits:
      anthropic:                # also openai, google, mistral, ollama, openai-compatible
        requests-per-minute: 50
        tokens-per-minute: 80000
        interactive-reserve: 0.2  # default; share benchmarks may not use
        max-wait: PT2S            # default; longer waits fail with a retryable error
```

- Benchmark attempts (`BENCHMARK_ATTEMPT`) are **batch** calls: they may use only
  `1 - interactive-reserve` of each budget, so pipeline steps, code reviews and model tests are never
  starved by a running benchmark.
- The token weight of a call is estimated before it (prompt characters / 4 + `maxOutputTokens`) and
  corrected with the provider's reported usage afterwards; set a realistic `maxOutputTokens` on judge
  and candidate profiles or the estimate blocks more budget than a call really uses.
- A call that would wait longer than `max-wait` fails with a retryable error. The benchmark executor puts
  the attempt back to `PENDING` and re-submits it after 15 s, 30 s, 60 s ... (at most 5 min,
  `backoffice.evaluation.retry.initial-backoff` / `max-backoff`), up to `retry.max-attempts` (6) times;
  after that the next run fails the attempt. Provider 429 and 5xx that survive the gateway retries are
  re-queued the same way.
- Limits are held in memory per API replica: with two replicas configure half of the vendor quota on
  each.

## Where to look when it fails

| Symptom | Look at |
|---|---|
| `POST /start` returns 409 | Benchmark not `DRAFT`, a model or the judge is disabled, or coding tasks selected without a runner (`backoffice.runner.mode`) |
| Attempts `FAILED` with `HTTP 401/403/429/5xx` | [provider-errors runbook](../runbooks/provider-errors.md) |
| Attempts `FAILED` with `structured output could not be parsed` | Judge model does not follow JSON instructions; pick a stronger judge or lower its reasoning cost (see runbook) |
| Coding attempts `FAILED` before verification | Runner logs (`runnerJobId` on the attempt detail); usually clone failure, missing toolchain or `setupCommand` error; see [runner-job-failed](../runbooks/runner-job-failed.md) |
| Coding attempts `TIMED_OUT` | Raise `timeoutSeconds` or shrink the task; check that `verifyCommand` alone fits the budget |
| Benchmark stuck `RUNNING` with `PENDING` attempts after a restart | `backoffice.evaluation.recover-on-startup` must be true; check the startup log line `re-queued benchmark attempts` |
| Scores look random | Check `stability`; rewrite the rubric with per-criterion definitions and a reference answer |
| Costs are zero | Pricing on the model profile is a placeholder |
| `403` on create/start/cancel | Caller lacks `evaluation.run` (role `evaluator`); on profile changes or `test` it is `models.manage` |
| Benchmark progresses slowly, attempts return to `PENDING` | The rate limiter refuses batch calls; log `attempt re-queued after retryable error ... reason=... rate limit reached`. Raise `tokens-per-minute` / `requests-per-minute`, lower `interactive-reserve`, or lower concurrency |
| Attempts `FAILED` with `rate limit reached for batch calls` | Retry budget used up (`backoffice.evaluation.retry.max-attempts`); the budget is too small for the matrix; see the [provider-errors runbook](../runbooks/provider-errors.md#back-office-rate-limiter) |
