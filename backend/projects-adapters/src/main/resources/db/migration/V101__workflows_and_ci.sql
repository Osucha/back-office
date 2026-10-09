-- Iteration 2 (ADR 0013, 0014): workflows are graphs, code review is a workflow started from CI,
-- GitLab API access is gone. Iteration-1 pipeline tables are replaced (data is not migrated).

DROP TABLE IF EXISTS approval_request;
DROP TABLE IF EXISTS artifact;
DROP TABLE IF EXISTS run_log;
DROP TABLE IF EXISTS code_review;
DROP TABLE IF EXISTS pipeline_run;
DROP TABLE IF EXISTS pipeline_definition;

DROP INDEX IF EXISTS idx_project_gitlab_path;
ALTER TABLE project DROP COLUMN IF EXISTS repo_gitlab_path;
ALTER TABLE project DROP COLUMN IF EXISTS review_policy;
ALTER TABLE project ADD COLUMN IF NOT EXISTS ci_entrypoints jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE project_ci_token (
    id           uuid PRIMARY KEY,
    project_id   uuid NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name         text NOT NULL,
    token_hash   text NOT NULL UNIQUE,
    prefix       text NOT NULL,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

CREATE INDEX idx_project_ci_token_project ON project_ci_token (project_id);

CREATE TABLE workflow_definition (
    id          uuid PRIMARY KEY,
    project_id  uuid NOT NULL REFERENCES project (id),
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    version     integer NOT NULL DEFAULT 1,
    nodes       jsonb NOT NULL,
    edges       jsonb NOT NULL,
    triggers    jsonb NOT NULL,
    canvas      jsonb,
    enabled     boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX idx_workflow_definition_project ON workflow_definition (project_id);

CREATE TABLE workflow_run (
    id                  uuid PRIMARY KEY,
    project_id          uuid NOT NULL,
    project_key         text NOT NULL,
    workflow_id         uuid NOT NULL,
    workflow_version    integer NOT NULL,
    workflow_name       text NOT NULL,
    definition_snapshot jsonb NOT NULL,
    trigger_type        text NOT NULL,
    ci_execution_id     uuid,
    input               jsonb NOT NULL,
    issue_key           text,
    status              text NOT NULL,
    outcome             text,
    current_node_id     text,
    waiting_kind        text,
    waiting_signal      text,
    open_approval_id    uuid,
    nodes               jsonb NOT NULL,
    failure             jsonb,
    merge_request       jsonb,
    review              jsonb,
    last_signal         jsonb,
    cost_amount         numeric(18, 6) NOT NULL DEFAULT 0,
    cost_currency       text NOT NULL DEFAULT 'USD',
    created_at          timestamptz NOT NULL,
    started_at          timestamptz,
    finished_at         timestamptz
);

CREATE INDEX idx_workflow_run_project_status ON workflow_run (project_id, status);
CREATE INDEX idx_workflow_run_status ON workflow_run (status);
CREATE INDEX idx_workflow_run_workflow ON workflow_run (workflow_id, status);
CREATE INDEX idx_workflow_run_created_at ON workflow_run (created_at DESC);
CREATE INDEX idx_workflow_run_finished_at ON workflow_run (status, finished_at DESC);
CREATE INDEX idx_workflow_run_waiting ON workflow_run (status, waiting_kind, waiting_signal);
CREATE INDEX idx_workflow_run_ci_execution ON workflow_run (ci_execution_id);

CREATE TABLE artifact (
    id          uuid PRIMARY KEY,
    run_id      uuid NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    node_id     text NOT NULL,
    name        text NOT NULL,
    kind        text NOT NULL,
    media_type  text NOT NULL,
    content     text NOT NULL,
    storage_ref text,
    size_bytes  bigint NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL
);

CREATE INDEX idx_artifact_run ON artifact (run_id, created_at);

CREATE TABLE approval_request (
    id            uuid PRIMARY KEY,
    run_id        uuid NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    node_id       text NOT NULL,
    project_id    uuid NOT NULL,
    project_key   text NOT NULL,
    workflow_name text NOT NULL,
    issue_key     text,
    question      text NOT NULL,
    options       jsonb NOT NULL,
    context       jsonb NOT NULL,
    status        text NOT NULL,
    decision      jsonb,
    created_at    timestamptz NOT NULL
);

CREATE INDEX idx_approval_request_status ON approval_request (status, created_at DESC);
CREATE INDEX idx_approval_request_run ON approval_request (run_id, status);
CREATE INDEX idx_approval_request_project_status ON approval_request (project_id, status);

CREATE TABLE ci_execution (
    id               uuid PRIMARY KEY,
    project_id       uuid NOT NULL,
    project_key      text NOT NULL,
    entrypoint       text NOT NULL,
    mode             text NOT NULL,
    status           text NOT NULL,
    gate_result      text NOT NULL,
    idempotency_key  text NOT NULL,
    context          jsonb NOT NULL,
    variables        jsonb NOT NULL,
    diff_artifact_id uuid,
    changed_files    jsonb NOT NULL,
    run_id           uuid,
    outcome          text,
    summary          text,
    report           jsonb,
    cost_amount      numeric(18, 6) NOT NULL DEFAULT 0,
    cost_currency    text NOT NULL DEFAULT 'USD',
    error            text,
    created_at       timestamptz NOT NULL,
    finished_at      timestamptz
);

CREATE INDEX idx_ci_execution_key ON ci_execution (idempotency_key, created_at DESC);
CREATE INDEX idx_ci_execution_project_created ON ci_execution (project_id, created_at DESC);
CREATE INDEX idx_ci_execution_created ON ci_execution (created_at DESC);
CREATE INDEX idx_ci_execution_run ON ci_execution (run_id);

CREATE TABLE run_log (
    id       bigserial PRIMARY KEY,
    run_id   uuid NOT NULL,
    node_id  text,
    at       timestamptz NOT NULL,
    level    text NOT NULL,
    message  text NOT NULL
);

CREATE INDEX idx_run_log_run_at ON run_log (run_id, at);
