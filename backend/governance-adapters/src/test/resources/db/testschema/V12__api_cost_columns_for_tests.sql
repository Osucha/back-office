-- Test-only copies of the run cost columns the workflow metrics read (ADR 0042; the real ones: projects-adapters V101
-- and V115).

ALTER TABLE workflow_run ADD COLUMN cost_amount numeric(18, 6) NOT NULL DEFAULT 0;
ALTER TABLE workflow_run ADD COLUMN api_cost_amount numeric(18, 6) NOT NULL DEFAULT 0;
