-- Governance context (ADR 0025): signed provenance attestations. Flyway range V550-V599.
-- One row per DSSE envelope (in-toto Statement v1). Rows are immutable; subject_key makes creation idempotent:
-- RUN:<runId> for the run summary, JOB:<runId>:<nodeExecutionId> for one agent job.

CREATE TABLE attestation (
    id                uuid PRIMARY KEY,
    run_id            uuid NOT NULL,
    project_id        uuid NOT NULL,
    kind              text NOT NULL,
    node_id           text,
    node_execution_id uuid,
    job_id            uuid,
    subject_key       text NOT NULL UNIQUE,
    subjects          jsonb NOT NULL,
    predicate_type    text NOT NULL,
    key_id            text NOT NULL,
    envelope          jsonb NOT NULL,
    statement_sha256  text NOT NULL,
    created_at        timestamptz NOT NULL
);

CREATE INDEX idx_attestation_run ON attestation (run_id, created_at);
CREATE INDEX idx_attestation_project_created ON attestation (project_id, created_at DESC);
