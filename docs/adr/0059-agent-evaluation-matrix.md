# 0059 — Agent evaluation: who did each step, agents in benchmarks, cost of an accepted result

- Status: accepted
- Date: 2026-11-06
- Deciders: owner
- Related: ADR 0025 (insights), 0037 (workflow metrics), 0042 (API cost), 0046/0047 (benchmarks), 0056 (stage 4 of its
  direction), 0057 (the agent is the environment)

## Context

Back Office exists to find cheaper ways to get accepted work: the same task done by different agents (harness, model,
environment) and different workflows, measured the same way. Two gaps remained:

- **Production runs** did not record who did a step: a node execution had a template but no agent and no model, so the
  insights could not compare agents on the same task.
- **Benchmarks** compared models with one harness for the whole benchmark; the agent (with its instructions, rules and
  skills) was not a candidate.

And the headline number was missing: what one accepted result costs, failures and rework included.

## Decision

1. **Every task step records its agent and model.** A node execution of a `TASK` step stores the agent (key, name,
   harness) and the model (profile id, label) it ran with. Older executions show none.
2. **Cost of an accepted result** is the headline metric everywhere:
   - step level: the cost of every execution of a step kind / the executions that were *accepted*: succeeded, last
     execution of their node in the run, and the run ended successfully (rework and failed attempts are paid by the
     accepted ones);
   - workflow level: the cost of every run in scope / successful runs (the former "cost per successful run", renamed),
     shown with the human wait time and the first-pass rate next to it;
   - benchmark level: the candidate's total cost / passed attempts.
3. **Agent insights** (`GET /insights/agents`): over the task steps of the runs the caller may view in `[from, to)`,
   one row per agent and model (executions, succeeded, failed, first-pass rate, cost, API cost, cost per accepted step,
   duration), and the same broken down per template, so agents are compared on the same task.
4. **Benchmarks compare candidates**: a candidate is an agent with a model (the agent's model when none is chosen).
   Prompt tasks are done by model answer agents (their environment is the system prompt), coding tasks by coding agents
   (their environment in the job, ADR 0057). A pair that cannot work (a coding task for a model answer agent and the
   reverse) creates no attempt and shows "not applicable". A benchmark created with models only (older API and stored
   benchmarks) means: each model as the agent `model-answer` for prompt tasks and as the agent of its coding harness for
   coding tasks, as before. The leaderboard has one row per candidate, with the cost per accepted (passed) attempt.
5. **Reference workflow.** A default workflow "Reference: one Claude Code session" (fetch the issue, one Claude Code
   step that implements it end to end and opens the merge request, a human review gate) is the yardstick: run the same
   issues through it and through the cheaper workflows and compare the cost of an accepted result. New projects get
   it with the other defaults; existing ones through "Add missing default workflows".

## Alternatives considered

- **Human time in money.** Back Office knows how long a run waited for people, not how long they worked. The wait is
  shown next to the cost instead of being converted with an invented rate.
- **Accepted = merged.** Merges happen in GitLab after the run; Back Office does not track them for every project.
  A successful run (its gates approved) is the accepted result.

## Consequences

- OpenAPI: `NodeExecution.agent` (`StepAgentRecord`: agent and model); `/insights/agents` with `AgentInsights`;
  `BenchmarkInput.candidates`, `Benchmark.candidateRefs`, `LeaderboardRow.candidate`, `notApplicable` and
  `costPerAccepted`; `Attempt.candidate`; `/projects/{id}/default-workflows`. MCP tool `get_agent_insights`.
- Benchmarks created with models only run as before (no agent environment) and show one row per model and agent.
- The evaluation context reads agents through the `TaskCatalog` port (shared kernel); it never imports the task library.
