-- ADR 0044: run workspaces belong to the project (shared by all its workflows; steps pick one by key) instead of
-- each workflow. The workflow-level columns of V117 are dropped.

CREATE TABLE project_workspace (
    id              uuid PRIMARY KEY,
    project_id      uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    key             text        NOT NULL,
    name            text        NOT NULL,
    description     text        NOT NULL DEFAULT '',
    sources         jsonb       NOT NULL DEFAULT '[]',
    setup_command   text,
    roles           jsonb       NOT NULL DEFAULT '[]',
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    updated_by_kind text,
    updated_by_id   uuid,
    updated_by_name text,
    CONSTRAINT project_workspace_key_unique UNIQUE (project_id, key)
);

ALTER TABLE workflow_definition DROP COLUMN IF EXISTS workspaces;
ALTER TABLE library_workflow_version DROP COLUMN IF EXISTS workspaces;
