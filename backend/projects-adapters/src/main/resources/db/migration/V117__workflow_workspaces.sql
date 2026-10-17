-- ADR 0044: run workspaces declared on a workflow (key, name, sources, setupCommand, roles); coding steps pick one by
-- key. Library versions keep them with library sources only.

ALTER TABLE workflow_definition ADD COLUMN workspaces jsonb;
ALTER TABLE library_workflow_version ADD COLUMN workspaces jsonb;
