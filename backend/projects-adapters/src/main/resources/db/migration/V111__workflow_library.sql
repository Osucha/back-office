-- ADR 0037: workflow library. Versioned workflows published from projects, linked copies in projects, and the
-- library version each run executed (for workflow metrics).

CREATE TABLE library_workflow (
    id          uuid PRIMARY KEY,
    key         text NOT NULL UNIQUE,
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    tags        jsonb NOT NULL DEFAULT '[]'::jsonb,
    deprecated  boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    updated_by  jsonb
);

-- Versions are immutable; the source columns survive the deletion of the source project or workflow.
CREATE TABLE library_workflow_version (
    library_workflow_id     uuid NOT NULL REFERENCES library_workflow (id) ON DELETE CASCADE,
    version                 integer NOT NULL,
    change_note             text NOT NULL DEFAULT '',
    nodes                   jsonb NOT NULL,
    edges                   jsonb NOT NULL,
    triggers                jsonb NOT NULL,
    canvas                  jsonb,
    published_at            timestamptz NOT NULL,
    published_by            jsonb,
    source_project_id       uuid,
    source_project_key      text,
    source_workflow_id      uuid,
    source_workflow_version integer,
    PRIMARY KEY (library_workflow_id, version)
);

ALTER TABLE workflow_definition ADD COLUMN library_workflow_id uuid REFERENCES library_workflow (id);
ALTER TABLE workflow_definition ADD COLUMN library_key text;
ALTER TABLE workflow_definition ADD COLUMN library_version integer;
ALTER TABLE workflow_definition ADD COLUMN library_modified boolean NOT NULL DEFAULT false;

CREATE INDEX idx_workflow_definition_library ON workflow_definition (library_workflow_id) WHERE library_workflow_id IS NOT NULL;

-- No foreign key: runs outlive library entries (the metrics of a deleted entry stay attributable by id).
ALTER TABLE workflow_run ADD COLUMN library_workflow_id uuid;
ALTER TABLE workflow_run ADD COLUMN library_version integer;
ALTER TABLE workflow_run ADD COLUMN library_modified boolean NOT NULL DEFAULT false;

CREATE INDEX idx_workflow_run_library ON workflow_run (library_workflow_id, created_at) WHERE library_workflow_id IS NOT NULL;
CREATE INDEX idx_workflow_run_workflow_created ON workflow_run (workflow_id, created_at);
