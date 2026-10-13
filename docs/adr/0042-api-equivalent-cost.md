# 0042 — Cost at API prices next to the spent cost

- Status: accepted
- Date: 2026-10-13
- Deciders: owner
- Related: ADR 0008 (usage ledger), 0025 (budgets), 0037 (workflow metrics), 0040 (personal runners);
  docs/architecture/11-governance.md, docs/manual/costs-insights.md

## Context

Since ADR 0040, Claude Code steps can run on a personal runner with their owner's own login. Back Office spends
nothing on them, so their cost is 0 and budgets ignore them, as intended. But in a company where everyone has a
subscription licence, the cost of tasks and workflows is still the question: which workflow is expensive, which step
dominates, what would the same work cost through the API. The harness reports what the work would have cost at API
prices (Claude Code's `total_cost_usd`, a client-side estimate), and Back Office threw it away.

## Decision

1. Every step and run has two amounts: **cost** (spent through Back Office: gateway, ledger, budgets; unchanged) and
   **apiCost**, the same work at API list prices. For steps through the gateway they are equal; for subscription
   steps cost is 0 and apiCost is the harness estimate, else the model profile's pricing of the tokens.
2. Storage: `NodeExecution.apiCost` in the run's node documents (executions stored before default to their cost) and
   `workflow_run.api_cost_amount` (migration V115 copies the cost of existing runs). API fields `apiCost` on
   `NodeExecution` and `WorkflowRunSummary`; workflow metrics add `totalApiCost`, `apiCostPerRun`,
   `apiCostPerSuccessfulRun` and per node `apiCost`.
3. Workflow metrics compute the run's apiCost as the ledger cost plus what subscriptions paid for
   (`api_cost_amount - cost_amount`, never negative), so gateway spend stays ledger-based as in ADR 0037.
4. The UI shows the spent cost as before and, where it differs, `· API $x` next to it (runs, steps, run list, metrics
   tables), plus a metrics card "At API prices per successful run".
5. Budgets, the costs page and the ledger keep counting only spent money.

## Alternatives considered

- **Count the estimate as cost**: budgets and the costs page would report money that was never spent and alerts would
  fire on subscription work.
- **A ledger entry for subscription work**: the ledger records what the gateway metered; mixing in client-side
  estimates would blur that line and every ledger consumer would need a filter.

## Consequences

- Workflows and steps can be compared by cost whoever pays, also in a company where everyone works on a subscription.
- The figure for subscription steps is an estimate of the harness at list prices, not an invoice.
