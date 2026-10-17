-- ADR 0044: persistent workspaces of a run. A workspace lives on the runner that first leased one of its jobs (a Docker
-- volume or a PVC named like the row) and is used by one job at a time (active_job_id, claimed at lease). Released
-- workspaces (released_at) are deleted by their runner on its next sync; a workspace used again starts empty.

CREATE TABLE runner_workspace (
    name          text PRIMARY KEY,
    run_id        uuid        NOT NULL,
    workspace_key text        NOT NULL,
    pool_id       text,
    runner_id     uuid,
    active_job_id uuid,
    created_at    timestamptz NOT NULL,
    last_used_at  timestamptz NOT NULL,
    release_after timestamptz,
    released_at   timestamptz
);

CREATE INDEX runner_workspace_run_id_idx ON runner_workspace (run_id);
CREATE INDEX runner_workspace_runner_id_idx ON runner_workspace (runner_id) WHERE released_at IS NULL;

ALTER TABLE agent_job ADD COLUMN workspace_name text;

CREATE INDEX agent_job_workspace_name_idx ON agent_job (workspace_name) WHERE workspace_name IS NOT NULL;
