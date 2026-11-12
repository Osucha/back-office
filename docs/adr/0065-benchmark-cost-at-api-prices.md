# 0065 — Benchmarks: cost at API prices, the candidate's own time, deadlines that hold

- Status: accepted
- Date: 2026-11-12
- Deciders: owner
- Related: ADR 0040 (personal runners), 0042 (cost at API prices), 0046 (benchmark preflight, latency), 0050 (Claude
  Code login models), 0059 (agent evaluation matrix); docs/manual/evaluation.md

## Context

The first benchmark of the Claude models of a subscription (Claude Code login on a personal runner, 36 attempts) showed:

1. Every cost was 0. `ExecuteAttemptService` drops the harness's estimate when the job ran on a subscription, and a
   login model's single answers (prompt tasks, the judge) carry no estimate at all (`LlmResult` has no field for it).
   ADR 0042 gave workflows a second amount at API prices; benchmarks, whose job is to compare cost, never got it.
   **Cost per accepted result**, the number candidates are compared on, was empty.
2. Times included queueing: a coding attempt that ran 5 minutes was reported as 2.3 hours because it waited for one of
   the personal runner's two slots. The code measured from submission, the manual promised the candidate's own time.
3. Two jobs with a 30-minute timeout were killed after 62 minutes: the controller's monitor thread (heartbeats and
   deadlines) stalled for about an hour without a log line, Back Office declared the lease lost and the slots stayed
   blocked. The runner gives the verify command 15 minutes after the agent on top of the job's timeout, past the
   controller's deadline, so a slow verification also ends as a deadline kill without a result.
4. Timed-out attempts carry no usage, so their tokens vanish from every figure and failures look free.
5. The starter task "Rate-limit owner form submissions" hung for two of three candidates; its verify command has no
   time limit. The task "Write a Spec Kit spec" kept a name ADR 0063 retired.

## Decision

1. **Two amounts per attempt**, as ADR 0042 for steps: `cost`/`judgeCost` (spent through Back Office, unchanged, 0 on
   a subscription) and `apiCost`/`judgeApiCost` (the same work at API list prices). For gateway calls they are equal;
   on a subscription they are the harness's estimate (Claude Code `total_cost_usd`); without one (a job killed before
   its final event) the tokens priced like the catalogue's enabled API profile of the same Claude family (opus, sonnet,
   haiku), else the profile's own pricing. `LlmResult.reportedCostUsd` carries the estimate of a login model's single answer (also for LLM steps of
   workflows, whose `apiCost` was 0 on a login model).
2. The leaderboard adds `meanApiCost`, `totalApiCost`, `apiCostPerAccepted`, `scorePerApiDollar` and `subscription`;
   the UI compares candidates on the API figures whenever a candidate ran on a subscription, and the benchmark list
   shows `totalApiCost`. Budgets, the ledger and the costs page keep counting spent money only (ADR 0042 §5).
3. Failed and timed-out attempts keep the usage and the estimate the runner reported up to the kill, so cost per
   accepted result pays for failures also on a subscription.
4. The candidate's time runs from the start of its job (`RunnerResult.startedAt`) or of its model call to the result;
   queueing for a runner is excluded.
5. Deadlines: the runner fits the verify command into what is left of the job's timeout (at least 60 s, at most
   `RUNNER_VERIFY_TIMEOUT`), so it posts a result before the controller's deadline. The controller checks deadlines and
   sends heartbeats on a thread of their own, independent of slow `docker` calls, and logs a warning when a monitor
   pass takes longer than a minute.
6. Migration V209 adds the columns and fills `api_cost` / `judge_api_cost` of existing attempts from the runner jobs'
   reported cost (`agent_job.cost_usd`), else from their spent cost.
   V210 recomputes the candidate's time of existing attempts from their runner jobs (`finished_at - started_at`).
8. The runner-controller image sets its credential file through `RUNNER_TOKEN_FILE` (it set the relaxed-binding name,
   which overrode `RUNNER_TOKEN_FILE`), so a personal or compose runner keeps its registration in its mounted data
   directory when the container is recreated with a new image instead of registering anew.
9. Starter suite: the PetClinic verify command limits Maven's test fork (`-Dsurefire.timeout`), the hidden acceptance
   tests have a per-test timeout, and "Write a Spec Kit spec from a two-line ticket" becomes "Write a specification
   from a two-line ticket" (unedited copies are upgraded at the next start).

## Alternatives considered

- **Count the estimate as spent cost**: rejected for the same reasons as in ADR 0042 (budgets and alerts on money never
  spent).
- **List prices on the login profiles**: their price would turn into spent cost wherever a profile's pricing is used;
  Claude Code always reports its estimate, so the profile's prices stay 0.

## Consequences

- Subscription candidates can be compared with API candidates and with each other on cost per accepted result.
- The figure is the harness's estimate at list prices, not an invoice.
- Benchmarks on a busy personal runner report comparable times; a stalled controller no longer holds slots for an hour.
