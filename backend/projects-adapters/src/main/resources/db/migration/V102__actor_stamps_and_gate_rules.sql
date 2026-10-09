-- Iteration 3 (ADR 0017, 0018): who did what, and gate approver rules copied onto approvals.
-- Actors are stored denormalised as {"kind": "USER|PERSONAL_TOKEN|CI_TOKEN|SYSTEM", "userId": uuid|null,
-- "displayName": "..."} so history stays readable after a user is renamed or deactivated.
-- Gate approver rules and checklists of workflow definitions live inside the existing nodes JSONB
-- (`gate.approvers`, `gate.checklist`); decisions keep `checkedItems` and `decidedBy` inside `decision`.

ALTER TABLE project ADD COLUMN created_by jsonb;
ALTER TABLE project_ci_token ADD COLUMN created_by jsonb;
ALTER TABLE workflow_definition ADD COLUMN updated_by jsonb;
ALTER TABLE workflow_run ADD COLUMN started_by jsonb;

ALTER TABLE approval_request ADD COLUMN approvers jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE approval_request ADD COLUMN checklist jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE approval_request ADD COLUMN started_by jsonb;

-- list queries are now filtered by the caller's projects (project_id IN (...)) and ordered by recency
CREATE INDEX idx_workflow_run_project_created ON workflow_run (project_id, created_at DESC);
CREATE INDEX idx_approval_request_status_project_created ON approval_request (status, project_id, created_at DESC);
