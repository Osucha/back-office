-- Projects & Pipelines context. Aggregates are stored as rows with JSONB documents for nested
-- collections (steps, executions, findings); columns used by list/filter queries are indexed.

CREATE TABLE project (
    id                       uuid PRIMARY KEY,
    key                      text NOT NULL UNIQUE,
    name                     text NOT NULL,
    description              text NOT NULL DEFAULT '',
    repo_url                 text NOT NULL,
    repo_default_branch      text NOT NULL DEFAULT 'main',
    repo_gitlab_path         text NOT NULL,
    jira_project_key         text,
    jira_jql                 text,
    constitution             text NOT NULL DEFAULT '',
    default_model_profile_id uuid,
    review_model_profile_id  uuid,
    review_policy            text NOT NULL DEFAULT 'BLOCK_ON_REQUEST_CHANGES',
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);

CREATE INDEX idx_project_gitlab_path ON project (repo_gitlab_path);

CREATE TABLE pipeline_definition (
    id          uuid PRIMARY KEY,
    project_id  uuid NOT NULL REFERENCES project (id),
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    version     integer NOT NULL DEFAULT 1,
    steps       jsonb NOT NULL,
    triggers    jsonb NOT NULL,
    enabled     boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX idx_pipeline_definition_project ON pipeline_definition (project_id);

CREATE TABLE pipeline_run (
    id                  uuid PRIMARY KEY,
    project_id          uuid NOT NULL,
    project_key         text NOT NULL,
    definition_id       uuid NOT NULL,
    definition_version  integer NOT NULL,
    pipeline_name       text NOT NULL,
    definition_snapshot jsonb NOT NULL,
    input               jsonb NOT NULL,
    issue_key           text,
    status              text NOT NULL,
    current_step_id     text,
    steps               jsonb NOT NULL,
    failure             jsonb,
    merge_request       jsonb,
    open_approval_id    uuid,
    cost_amount         numeric(18, 6) NOT NULL DEFAULT 0,
    cost_currency       text NOT NULL DEFAULT 'USD',
    created_at          timestamptz NOT NULL,
    started_at          timestamptz,
    finished_at         timestamptz
);

CREATE INDEX idx_pipeline_run_project_status ON pipeline_run (project_id, status);
CREATE INDEX idx_pipeline_run_status ON pipeline_run (status);
CREATE INDEX idx_pipeline_run_definition ON pipeline_run (definition_id, status);
CREATE INDEX idx_pipeline_run_created_at ON pipeline_run (created_at DESC);
CREATE INDEX idx_pipeline_run_finished_at ON pipeline_run (status, finished_at DESC);

CREATE TABLE artifact (
    id          uuid PRIMARY KEY,
    run_id      uuid NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    step_id     text NOT NULL,
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
    run_id        uuid NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    step_id       text NOT NULL,
    project_id    uuid NOT NULL,
    project_key   text NOT NULL,
    pipeline_name text NOT NULL,
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

CREATE TABLE code_review (
    id                 uuid PRIMARY KEY,
    project_id         uuid NOT NULL,
    project_key        text NOT NULL,
    merge_request_iid  bigint NOT NULL,
    merge_request_url  text,
    source_sha         text,
    requested_by       text NOT NULL,
    run_id             uuid,
    status             text NOT NULL,
    policy             text NOT NULL,
    verdict            text,
    score              integer,
    summary            text,
    findings           jsonb NOT NULL,
    usage              jsonb NOT NULL,
    cost_amount        numeric(18, 6) NOT NULL DEFAULT 0,
    cost_currency      text NOT NULL DEFAULT 'USD',
    model_ref          jsonb,
    duration_ms        bigint,
    error              text,
    created_at         timestamptz NOT NULL,
    finished_at        timestamptz
);

CREATE INDEX idx_code_review_mr ON code_review (project_id, merge_request_iid, source_sha);
CREATE INDEX idx_code_review_project_created ON code_review (project_id, created_at DESC);
CREATE INDEX idx_code_review_created ON code_review (created_at DESC);

CREATE TABLE run_log (
    id       bigserial PRIMARY KEY,
    run_id   uuid NOT NULL,
    step_id  text,
    at       timestamptz NOT NULL,
    level    text NOT NULL,
    message  text NOT NULL
);

CREATE INDEX idx_run_log_run_at ON run_log (run_id, at);
