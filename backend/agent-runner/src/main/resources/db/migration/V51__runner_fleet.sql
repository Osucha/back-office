-- Runner fleet v2 (ADR 0024, docs/architecture/10-agent-execution.md): pools, registered runner-controllers,
-- single-use registration tokens, the agent job queue (leased with FOR UPDATE SKIP LOCKED), job events and
-- project sandbox policies. Replaces the v1 tables runner_job / runner_job_event (API-created Jobs).
-- Every token column holds the SHA-256 hex digest of the token, never the token.

CREATE TABLE runner_pool (
    id                  text PRIMARY KEY,
    name                text        NOT NULL,
    description         text,
    labels              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    ceiling             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    allowed_images      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    warm_pool_size      integer     NOT NULL DEFAULT 2,
    max_concurrent_jobs integer     NOT NULL DEFAULT 10,
    project_ids         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

CREATE TABLE runner (
    id              uuid PRIMARY KEY,
    pool_id         text        NOT NULL REFERENCES runner_pool (id) ON DELETE CASCADE,
    name            text        NOT NULL,
    version         text        NOT NULL,
    cluster         text,
    backends        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    runtime_classes jsonb       NOT NULL DEFAULT '[]'::jsonb,
    max_concurrent  integer     NOT NULL DEFAULT 1,
    token_hash      text        NOT NULL UNIQUE,
    registered_at   timestamptz NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    revoked_at      timestamptz
);

CREATE INDEX runner_pool_idx ON runner (pool_id);

CREATE TABLE runner_registration_token (
    id          uuid PRIMARY KEY,
    pool_id     text        NOT NULL REFERENCES runner_pool (id) ON DELETE CASCADE,
    token_hash  text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_by  text,
    used_at     timestamptz,
    runner_id   uuid
);

CREATE TABLE agent_job (
    id               uuid PRIMARY KEY,
    label            text        NOT NULL UNIQUE,
    pool_id          text REFERENCES runner_pool (id) ON DELETE SET NULL,
    runner_id        uuid REFERENCES runner (id) ON DELETE SET NULL,
    status           text        NOT NULL,
    priority         integer     NOT NULL DEFAULT 0,
    mode             text        NOT NULL,
    agent            text        NOT NULL,
    model_label      text        NOT NULL,
    project_id       uuid,
    run_id           uuid,
    node_id          text,
    benchmark_id     uuid,
    attempt_id       uuid,
    spec             jsonb       NOT NULL,
    usage_context    jsonb       NOT NULL,
    requested_policy jsonb,
    policy           jsonb,
    policy_sources   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    pool_selector    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    continuation     jsonb,
    cancel_requested boolean     NOT NULL DEFAULT false,
    token_hash       text UNIQUE,
    lease_expires_at timestamptz,
    lease_count      integer     NOT NULL DEFAULT 0,
    lost_count       integer     NOT NULL DEFAULT 0,
    phase            text,
    sandbox_name     text,
    node             text,
    queued_at        timestamptz NOT NULL,
    leased_at        timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    queue_deadline   timestamptz,
    timeout_seconds  integer     NOT NULL,
    reason           text,
    completion       jsonb,
    result           jsonb,
    result_blob_key  text,
    cost_usd         numeric(18, 6)
);

CREATE INDEX agent_job_queue_idx ON agent_job (pool_id, priority DESC, queued_at) WHERE status = 'QUEUED';
CREATE INDEX agent_job_lease_idx ON agent_job (lease_expires_at) WHERE status IN ('LEASED', 'STARTING', 'RUNNING');
CREATE INDEX agent_job_runner_idx ON agent_job (runner_id) WHERE runner_id IS NOT NULL;
CREATE INDEX agent_job_status_idx ON agent_job (status, queued_at DESC);
CREATE INDEX agent_job_project_idx ON agent_job (project_id) WHERE project_id IS NOT NULL;

CREATE TABLE agent_job_event (
    id        bigserial PRIMARY KEY,
    job_id    uuid        NOT NULL REFERENCES agent_job (id) ON DELETE CASCADE,
    at        timestamptz NOT NULL,
    message   text        NOT NULL,
    progress  boolean     NOT NULL DEFAULT false
);

CREATE INDEX agent_job_event_job_at_idx ON agent_job_event (job_id, at);

CREATE TABLE project_sandbox_policy (
    project_id  uuid PRIMARY KEY,
    policy      jsonb       NOT NULL,
    updated_at  timestamptz NOT NULL,
    updated_by  text
);

-- v1 history: finished jobs keep their spec and result (evaluation attempts reference them by id); jobs that
-- were still running under v1 cannot report any more (their HMAC callback tokens are gone) and are closed.
INSERT INTO agent_job (id, label, status, mode, agent, model_label, project_id, run_id, node_id, benchmark_id, attempt_id,
                       spec, usage_context, queued_at, started_at, finished_at, timeout_seconds, reason, result)
SELECT j.id,
       j.label,
       CASE WHEN j.status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED') THEN j.status ELSE 'FAILED' END,
       j.mode,
       j.agent,
       j.model_label,
       (j.usage_context ->> 'projectId')::uuid,
       (j.usage_context ->> 'runId')::uuid,
       j.usage_context ->> 'stepId',
       (j.usage_context ->> 'benchmarkId')::uuid,
       (j.usage_context ->> 'attemptId')::uuid,
       j.spec,
       j.usage_context,
       j.created_at,
       j.started_at,
       COALESCE(j.finished_at, now()),
       j.timeout_seconds,
       CASE WHEN j.status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED') THEN NULL ELSE 'closed by the runner v2 migration (job was unfinished)' END,
       j.result
FROM runner_job j;

INSERT INTO agent_job_event (job_id, at, message, progress)
SELECT e.job_id, e.at, e.message, e.progress FROM runner_job_event e ORDER BY e.id;

DROP TABLE runner_job_event;
DROP TABLE runner_job;
