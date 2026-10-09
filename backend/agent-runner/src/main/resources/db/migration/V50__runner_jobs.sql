-- Agent runner jobs (Kubernetes Job / docker run) and the log events posted back by the runner.

CREATE TABLE runner_job (
    id               uuid PRIMARY KEY,
    label            text NOT NULL UNIQUE,
    mode             text NOT NULL,
    agent            text NOT NULL,
    model_label      text NOT NULL,
    status           text NOT NULL,
    spec             jsonb NOT NULL,
    result           jsonb,
    usage_context    jsonb NOT NULL,
    created_at       timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    timeout_seconds  integer NOT NULL,
    external_ref     text
);

CREATE INDEX idx_runner_job_status ON runner_job (status);
CREATE INDEX idx_runner_job_created_at ON runner_job (created_at DESC);

CREATE TABLE runner_job_event (
    id        bigserial PRIMARY KEY,
    job_id    uuid NOT NULL REFERENCES runner_job (id) ON DELETE CASCADE,
    at        timestamptz NOT NULL,
    message   text NOT NULL,
    progress  boolean NOT NULL DEFAULT false
);

CREATE INDEX idx_runner_job_event_job_at ON runner_job_event (job_id, at);
