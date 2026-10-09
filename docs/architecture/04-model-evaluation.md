# 04 — Model evaluation

## Goal

Answer, with numbers from the owner's own kind of work: *which model should run which step, and what
does it cost?* Public benchmarks do not cover "write a spec for my kind of ticket" or "implement a
Kotlin feature in my hexagonal codebase with my test suite".

## Building blocks

| Concept | Purpose |
|---|---|
| `ModelProfile` | Catalogue entry: provider, model id, default options (reasoning effort, max tokens), pricing |
| `TaskSuite` / `EvalTask` | The work to be evaluated, grouped; each task has a category and difficulty |
| `Benchmark` | Suite × models × repetitions, with a judge model |
| `Attempt` | One execution of one task by one model, with output, score, tokens, cost, latency |
| Leaderboard | Read model aggregating attempts |

## Task kinds

### `PROMPT` tasks (analysis, specification, review, architecture)

The task's `instructions` (+ optional `context`) are sent to the candidate model through the
`LlmGateway`. The output is judged by the judge model with the rubric and optional reference answer,
using a structured-output call that returns:

```json
{ "score": 0-100, "passed": true, "criteria": [ { "name": "…", "score": 0-10, "comment": "…" } ], "rationale": "…" }
```

Judge prompt design: rubric first, then the candidate output, then the reference; the judge is told
the task category and difficulty and asked to be strict and consistent. The judge profile is fixed
per benchmark so scores are comparable across candidates.

### `CODING_JOB` tasks

Executed in the agent runner (`mode = EVAL_CODING`) with the provider-agnostic OpenCode harness so
every model gets the same tools and loop. The runner clones `repository.url` at `baseRef`, runs
`setupCommand`, gives the agent the instructions, then runs `verifyCommand` (tests). Result:

- `passed` = verification exit code 0
- `score` = judge score of the diff against the rubric (code quality, adherence to constraints),
  combined as `0.6 * passed*100 + 0.4 * judgeScore` by default (configurable per suite)
- usage/cost from the harness event stream

Anthropic-only comparisons may use Claude Code as the harness for a "best-case" measurement; the
harness is recorded on the attempt so it is never mixed up in the leaderboard.

## Execution

`RunBenchmark` use case builds the attempt matrix and hands attempts to `BenchmarkExecutor`
(adapter) which runs them with bounded concurrency per provider (default 2) to respect rate limits.
Attempts are persisted before execution so progress survives restarts; on startup `PENDING` and
`RUNNING` attempts of a `RUNNING` benchmark are re-queued (coding jobs re-attach by Job name).

Progress is pushed over SSE (`BenchmarkProgress` events).

## Cost accounting

Every LLM call and every runner job writes a `UsageRecord` with the `ModelRef`, tokens and cost. Cost =
`inputTokens × inputPerMTok / 1e6 + outputTokens × outputPerMTok / 1e6 + cacheRead × cacheReadPerMTok /
1e6 + cacheWrite × cacheWritePerMTok / 1e6`. If the harness reports a cost (Claude Code does) the
reported figure wins and the computed one is stored as `estimatedCost` for comparison.

The `Costs` page groups the ledger by model, project, benchmark and day. The same figures are exported
as OpenTelemetry attributes so Langfuse shows them per trace when enabled.

## Leaderboard metrics

| Metric | Definition |
|---|---|
| `meanScore` | mean judge score over successful attempts |
| `passRate` | share of attempts with `passed = true` (coding) or `score ≥ 70` (prompt) |
| `meanCost` | mean cost per attempt |
| `scorePerDollar` | `meanScore / meanCost` |
| `p50Latency`, `p95Latency` | wall-clock per attempt |
| `stability` | standard deviation of score across repetitions |

Breakdowns: by category, by difficulty, per task. The UI shows a table and a score-vs-cost scatter
plot so the Pareto frontier is visible.

## Seed content

The application ships a starter suite (`Starter: SDLC tasks`) with 12 tasks:

- ANALYSIS: root-cause a failing test from a stack trace (EASY), compare two integration approaches (MEDIUM)
- SPECIFICATION: write a Spec Kit spec from a two-line ticket (MEDIUM), write acceptance criteria and edge cases for a payment retry (HARD)
- REVIEW: review a 200-line Kotlin diff with 3 planted bugs (MEDIUM)
- ARCHITECTURE: propose module boundaries for a described monolith (HARD), write an ADR (MEDIUM)
- CODING (`CODING_JOB` on a small public Kotlin sample repo): add a validated REST endpoint (EASY), fix a concurrency bug with a failing test (HARD), refactor to hexagonal ports with tests green (EXPERT), add a Flyway migration and JPA mapping (MEDIUM), implement a rate limiter with tests (HARD)

They are examples; the owner is expected to replace them with real tickets and real repositories.
