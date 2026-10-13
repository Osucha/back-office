-- ADR 0042: the cost of a run at API list prices next to the cost actually spent. They differ when a subscription paid
-- for steps (Claude Code with its owner's login on a personal runner: spent 0, API-equivalent the harness estimate).
-- Runs before this migration were all paid through the gateway, so their API-equivalent cost is their cost.

ALTER TABLE workflow_run
    ADD COLUMN api_cost_amount numeric(18, 6) NOT NULL DEFAULT 0;

UPDATE workflow_run SET api_cost_amount = cost_amount;
