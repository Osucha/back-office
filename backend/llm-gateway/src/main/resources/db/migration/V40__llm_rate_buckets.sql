-- Provider rate-limit buckets shared by every replica (ADR 0020 / 0023): bucket4j PostgreSQL proxy
-- (advisory-lock based). Key `<PROVIDER>:<requests|tokens>:<capacity>`, state = serialised bucket.
CREATE TABLE llm_rate_bucket (
    id     varchar(128) PRIMARY KEY,
    state  bytea
);
