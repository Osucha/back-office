# 04 — Model evaluation

## Goal

Answer, with numbers from the owner's own kind of work: *which agent with which model should run which
step, and what does an accepted result cost?* Public benchmarks do not cover "write a spec for my kind of
ticket" or "implement a Kotlin feature in my hexagonal codebase with my test suite".

## Building blocks

| Concept | Purpose |
|---|---|
| `ModelProfile` | Catalogue entry: provider, model id, default options (reasoning effort, max tokens), pricing |
| `TaskSuite` / `EvalTask` | The work to be evaluated, grouped; each task has a category and difficulty |
| `Benchmark` | Suite × candidates × repetitions, with a judge model (ADR 0059; before it: suite × models) |
| Candidate (`BenchmarkCandidate` / `CandidateRef`) | An agent of the task library with a model profile (the agent's own model when none is given), ADR 0059 |
| `Attempt` | One execution of one task by one candidate (agent key, name, harness and model), with output, score, tokens, cost, latency |
| Leaderboard | Read model aggregating attempts, one row per candidate |

## Candidates (ADR 0059)

A benchmark compares **candidates**: an agent (harness, model, instructions, rules, skills, connectors, ADR 0056/0057,
0064) with a model profile. A benchmark has no project, so a coding candidate gets only the **platform** `MCP_HTTP`
connectors its agent lists, through the shared-kernel port `ConnectorCatalog` (`ConnectorCatalogService` in
projects-core); `GITLAB_READ` and project connectors are skipped with a note. `BenchmarkInput.candidates` holds `{agentKey, modelProfileId?}`; without a model the agent's own
model is used (an agent without one is refused). The evaluation context reads agents through the `TaskCatalog` port of
the shared kernel (`agentEnvironment`); it never imports the task library. Stored in `benchmark.candidates` (JSONB,
`V208__benchmark_candidates.sql`); attempts store `agent_key`, `agent_name`, `harness`.

| Task kind | Agent kind | Runs as |
|---|---|---|
| `PROMPT` | model answer (no harness: `model-answer`, `analyst`, `reviewer`, own agents without harness) | one chat call; the agent's environment is the system prompt |
| `CODING_JOB` | coding agent (`OPENCODE` or `CLAUDE_CODE` harness) | an agent job with the agent's environment in the job, as in a workflow step (ADR 0057) |
| any other pair | | no attempt; counted per candidate as `notApplicable` |

`BenchmarkExecutionPlanner` builds the matrix tasks × applicable candidates × repetitions. The environment is read when
the candidate is asked (not for a judge retry or a submitted job); an agent deleted after the start fails the attempt.

**Benchmarks of models (legacy).** A benchmark created with `modelProfileIds` only (the API before ADR 0059 and every
stored benchmark; `Benchmark.legacy`) runs as before: each model as `model-answer` on prompt tasks and as the agent of
`codingHarness` (`opencode` or `claude-code`) on coding tasks, every pair applicable, no agent environment. V208 fills
the agent of the existing attempts the same way; `Benchmark.candidateRefs` are derived from the models, so such a
leaderboard has one row per model and agent.

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

Executed in the agent runner (`mode = EVAL_CODING`) with the harness of the candidate's agent (legacy
benchmarks: the benchmark's `codingHarness`, OpenCode by default, so every model gets the same tools and
loop). The runner clones `repository.url` at `baseRef`, runs
`setupCommand`, gives the agent the instructions, then runs `verifyCommand` (tests). Result:

- `passed` = verification exit code 0
- `score` = judge score of the diff against the rubric (code quality, adherence to constraints),
  combined as `0.6 * passed*100 + 0.4 * judgeScore` by default (configurable per suite)
- usage/cost from the harness event stream

The harness is part of the candidate and recorded on the attempt, so Claude Code and OpenCode runs of
the same model are separate leaderboard rows and never mixed up.

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

**Two amounts per attempt (ADR 0065, as ADR 0042 for workflow steps).** `cost` / `judgeCost` is what Back Office
spent (0 on a subscription); `apiCost` / `judgeApiCost` is the same work at API list prices. Through the gateway both
are the profile's pricing of the tokens. A Claude Code login model (a coding job with `RunnerResult.subscription`, a
single answer through `OwnLoginLlm`, also as judge) is worth the harness's estimate (`RunnerResult.reportedCostUsd`,
`LlmResult.reportedCostUsd`: Claude Code's `total_cost_usd`); without one (a job killed before its final event) its
tokens are priced like the enabled API profile of the same Claude family (`opus`, `sonnet`, `haiku` in the model id or
name; the most expensive, then the highest model id: `ApiPricing`, behind `ModelProfileRepository.apiPricingOf`), else
the profile's own pricing. Failed and timed-out coding attempts keep the usage and both amounts the runner reported.
`Benchmark.totalApiCost` sums `apiCost + judgeApiCost`; budgets and the ledger keep counting spent money only. V209
adds the columns and fills them for older attempts from the runner jobs' `agent_job.cost_usd` (the coding candidate's
`runner_job_id`, a login candidate's first prompt job, a login judge's other jobs of the attempt), else the spent cost.

**The candidate's own time (ADR 0046/0065).** `candidateLatencyMs` runs from the start of the candidate's job on its
runner (`RunnerResult.startedAt`, the job's first RUNNING heartbeat) or of its model call to the result; waiting for a
runner slot is excluded (a result without a start falls back to the attempt's start). A login model's answer measures
its PROMPT job the same way.

## Leaderboard metrics

| Metric | Definition |
|---|---|
| `meanScore` | mean judge score over successful attempts |
| `passRate` | share of attempts with `passed = true` (coding) or `score ≥ 70` (prompt) |
| `meanCost` | mean cost per attempt |
| `costPerAccepted` | ADR 0059: `totalCost / passed attempts` (failed attempts are paid by the passed ones); null without a passed attempt |
| `notApplicable` | ADR 0059: tasks of the benchmark the candidate cannot do (a coding task for a model answer agent and the reverse) |
| `scorePerDollar` | `meanScore / meanCost` |
| `meanApiCost`, `totalApiCost` | ADR 0065: the candidate's `apiCost` per attempt and in total, failed and timed-out attempts included |
| `apiCostPerAccepted` | ADR 0065: `totalApiCost / passed attempts`; null without a passed attempt |
| `scorePerApiDollar` | ADR 0065: `meanScore / meanApiCost` |
| `subscription` | ADR 0065: the candidate's model is a Claude Code login model (spent cost 0): compare it on the API figures |
| `p50Latency`, `p95Latency` | the candidate's own time (`candidateLatencyMs`, queueing excluded) over succeeded attempts |
| `stability` | standard deviation of score across repetitions |

Rows are per candidate (`LeaderboardRow.candidate`, label `agent · model`). Breakdowns: by category, by
difficulty, per task. The UI shows a table and a score-vs-cost scatter plot so the Pareto frontier is
visible. The same cost of an accepted result is measured on production runs, per step and per workflow
([11-governance.md](11-governance.md#agent-insights-get-insightsagents)).

## Seed content

The application ships a starter suite (`Starter: SDLC tasks`) with 12 tasks:

- ANALYSIS: root-cause a failing test from a stack trace (EASY), compare two integration approaches (MEDIUM)
- SPECIFICATION: write a feature specification from a two-line ticket (MEDIUM), write acceptance criteria and edge cases for a payment retry (HARD)
- REVIEW: review a 200-line Kotlin diff with 3 planted bugs (MEDIUM)
- ARCHITECTURE: propose module boundaries for a described monolith (HARD), write an ADR (MEDIUM)
- CODING (`CODING_JOB` on a small public Kotlin sample repo): add a validated REST endpoint (EASY), fix a concurrency bug with a failing test (HARD), refactor to hexagonal ports with tests green (EXPERT), add a Flyway migration and JPA mapping (MEDIUM), implement a rate limiter with tests (HARD)

They are examples; the owner is expected to replace them with real tickets and real repositories.
