-- Test-only copies of the columns the workflow metrics read (ADR 0037; the real ones: projects-adapters V101 and V111).

ALTER TABLE workflow_run ADD COLUMN workflow_version integer NOT NULL DEFAULT 1;
ALTER TABLE workflow_run ADD COLUMN library_workflow_id uuid;
ALTER TABLE workflow_run ADD COLUMN library_version integer;
ALTER TABLE workflow_run ADD COLUMN library_modified boolean NOT NULL DEFAULT false;

CREATE TABLE workflow_definition (
    id         uuid PRIMARY KEY,
    project_id uuid NOT NULL
);
