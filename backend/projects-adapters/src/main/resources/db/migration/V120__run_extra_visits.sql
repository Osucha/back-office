-- Attempts granted beyond a node's maxVisits when a person retries a run whose node used them up (node id -> count).
ALTER TABLE workflow_run ADD COLUMN extra_visits jsonb NOT NULL DEFAULT '{}'::jsonb;
