-- Model Evaluation context: catalogue, task suites, benchmarks and attempts (docs/architecture/04-model-evaluation.md).

CREATE TABLE model_profile (
    id                 uuid PRIMARY KEY,
    display_name       varchar(200)   NOT NULL,
    provider           varchar(32)    NOT NULL,
    model_id           varchar(200)   NOT NULL,
    options            jsonb          NOT NULL DEFAULT '{}'::jsonb,
    input_per_mtok     numeric(18, 6) NOT NULL,
    output_per_mtok    numeric(18, 6) NOT NULL,
    cache_read_per_mtok  numeric(18, 6),
    cache_write_per_mtok numeric(18, 6),
    currency           varchar(3)     NOT NULL DEFAULT 'USD',
    capabilities       jsonb          NOT NULL DEFAULT '[]'::jsonb,
    context_window     integer,
    notes              text,
    enabled            boolean        NOT NULL DEFAULT true,
    created_at         timestamptz    NOT NULL,
    updated_at         timestamptz    NOT NULL
);

CREATE INDEX model_profile_provider_model_idx ON model_profile (provider, model_id);

CREATE TABLE task_suite (
    id                  uuid PRIMARY KEY,
    name                varchar(200)     NOT NULL,
    description         text,
    weight_verification double precision NOT NULL DEFAULT 0.6,
    weight_judge        double precision NOT NULL DEFAULT 0.4,
    created_at          timestamptz      NOT NULL,
    updated_at          timestamptz      NOT NULL
);

CREATE TABLE eval_task (
    id               uuid PRIMARY KEY,
    suite_id         uuid             NOT NULL REFERENCES task_suite (id) ON DELETE CASCADE,
    position         integer          NOT NULL DEFAULT 0,
    name             varchar(200)     NOT NULL,
    category         varchar(32)      NOT NULL,
    difficulty       varchar(16)      NOT NULL,
    kind             varchar(16)      NOT NULL,
    instructions     text             NOT NULL,
    context          text,
    rubric           text             NOT NULL,
    reference_answer text,
    repo_url         varchar(1000),
    repo_base_ref    varchar(200),
    repo_setup_command  text,
    repo_verify_command text,
    timeout_seconds  bigint           NOT NULL DEFAULT 900,
    weight           double precision NOT NULL DEFAULT 1
);

CREATE INDEX eval_task_suite_idx ON eval_task (suite_id, position);

CREATE TABLE benchmark (
    id                     uuid PRIMARY KEY,
    name                   varchar(200) NOT NULL,
    suite_id               uuid         NOT NULL,
    task_ids               jsonb        NOT NULL DEFAULT '[]'::jsonb,
    model_profile_ids      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    judge_model_profile_id uuid         NOT NULL,
    repetitions            integer      NOT NULL DEFAULT 1,
    coding_harness         varchar(32)  NOT NULL DEFAULT 'OPENCODE',
    status                 varchar(16)  NOT NULL,
    created_at             timestamptz  NOT NULL,
    started_at             timestamptz,
    finished_at            timestamptz
);

CREATE INDEX benchmark_status_idx ON benchmark (status);
CREATE INDEX benchmark_suite_idx ON benchmark (suite_id);

CREATE TABLE benchmark_attempt (
    id                 uuid PRIMARY KEY,
    benchmark_id       uuid           NOT NULL REFERENCES benchmark (id) ON DELETE CASCADE,
    task_id            uuid           NOT NULL,
    task_name          varchar(200)   NOT NULL,
    category           varchar(32)    NOT NULL,
    difficulty         varchar(16)    NOT NULL,
    kind               varchar(16)    NOT NULL,
    model_profile_id   uuid           NOT NULL,
    model_display_name varchar(200)   NOT NULL,
    provider           varchar(32)    NOT NULL,
    repetition         integer        NOT NULL,
    status             varchar(16)    NOT NULL,
    output             text,
    verification       jsonb,
    score              double precision,
    passed             boolean,
    judge_rationale    text,
    judge_criteria     jsonb          NOT NULL DEFAULT '[]'::jsonb,
    input_tokens       bigint         NOT NULL DEFAULT 0,
    output_tokens      bigint         NOT NULL DEFAULT 0,
    cache_read_tokens  bigint         NOT NULL DEFAULT 0,
    cache_write_tokens bigint         NOT NULL DEFAULT 0,
    cost               numeric(18, 6) NOT NULL DEFAULT 0,
    judge_cost         numeric(18, 6) NOT NULL DEFAULT 0,
    currency           varchar(3)     NOT NULL DEFAULT 'USD',
    duration_ms        bigint,
    runner_job_id      uuid,
    error              text,
    created_at         timestamptz    NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz
);

CREATE INDEX benchmark_attempt_benchmark_idx ON benchmark_attempt (benchmark_id, status);
CREATE INDEX benchmark_attempt_task_idx ON benchmark_attempt (benchmark_id, task_id);
CREATE INDEX benchmark_attempt_model_idx ON benchmark_attempt (benchmark_id, model_profile_id);
