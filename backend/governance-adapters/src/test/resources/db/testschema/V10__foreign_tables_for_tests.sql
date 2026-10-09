-- Test-only copies of the tables of other contexts that the governance read models query (the real ones live in
-- projects-adapters V101/V102, llm-gateway V1 and agent-runner V50). Only the columns governance reads.

CREATE TABLE workflow_run (
    id               uuid PRIMARY KEY,
    project_id       uuid NOT NULL,
    project_key      text NOT NULL,
    workflow_id      uuid NOT NULL,
    workflow_name    text NOT NULL,
    issue_key        text,
    status           text NOT NULL,
    outcome          text,
    nodes            jsonb NOT NULL,
    merge_request    jsonb,
    created_at       timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz
);

CREATE TABLE approval_request (
    id         uuid PRIMARY KEY,
    run_id     uuid NOT NULL,
    node_id    text NOT NULL,
    project_id uuid NOT NULL,
    status     text NOT NULL,
    decision   jsonb,
    created_at timestamptz NOT NULL
);

CREATE TABLE usage_record (
    id                 uuid PRIMARY KEY,
    occurred_at        timestamptz    NOT NULL,
    model_label        varchar(240)   NOT NULL,
    input_tokens       bigint         NOT NULL DEFAULT 0,
    output_tokens      bigint         NOT NULL DEFAULT 0,
    cost               numeric(18, 6) NOT NULL DEFAULT 0,
    project_id         uuid,
    run_id             uuid,
    step_id            varchar(200)
);

CREATE TABLE runner_job (
    id          uuid PRIMARY KEY,
    label       text NOT NULL,
    agent       text NOT NULL,
    model_label text NOT NULL,
    status      text NOT NULL,
    spec        jsonb NOT NULL,
    result      jsonb,
    created_at  timestamptz NOT NULL,
    started_at  timestamptz,
    finished_at timestamptz
);
