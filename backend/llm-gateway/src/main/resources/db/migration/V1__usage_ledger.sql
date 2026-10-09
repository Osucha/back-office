-- Usage ledger: one row per LLM call or runner job (docs/adr/0008-usage-ledger-and-langfuse.md).
CREATE TABLE usage_record (
    id                 uuid PRIMARY KEY,
    occurred_at        timestamptz    NOT NULL,
    provider           varchar(32)    NOT NULL,
    model_id           varchar(200)   NOT NULL,
    model_label        varchar(240)   NOT NULL,
    input_tokens       bigint         NOT NULL DEFAULT 0,
    output_tokens      bigint         NOT NULL DEFAULT 0,
    cache_read_tokens  bigint         NOT NULL DEFAULT 0,
    cache_write_tokens bigint         NOT NULL DEFAULT 0,
    cost               numeric(18, 6) NOT NULL DEFAULT 0,
    estimated_cost     numeric(18, 6) NOT NULL DEFAULT 0,
    currency           varchar(3)     NOT NULL DEFAULT 'USD',
    duration_ms        bigint,
    context_kind       varchar(32)    NOT NULL,
    project_id         uuid,
    run_id             uuid,
    step_id            varchar(200),
    review_id          uuid,
    benchmark_id       uuid,
    attempt_id         uuid
);

CREATE INDEX usage_record_occurred_at_idx ON usage_record (occurred_at);
CREATE INDEX usage_record_project_id_idx ON usage_record (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX usage_record_run_id_idx ON usage_record (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX usage_record_benchmark_id_idx ON usage_record (benchmark_id) WHERE benchmark_id IS NOT NULL;
CREATE INDEX usage_record_model_label_idx ON usage_record (model_label);
