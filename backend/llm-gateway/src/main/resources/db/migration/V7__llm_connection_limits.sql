-- ADR 0046: optional request limits of a provider connection (e.g. a free tier's requests a day), kept by Back Office.
ALTER TABLE llm_provider_connection
    ADD COLUMN requests_per_minute integer CHECK (requests_per_minute > 0),
    ADD COLUMN requests_per_day    integer CHECK (requests_per_day > 0);

-- Requests counted per connection in fixed UTC buckets: 'm:<minute>' and 'd:<date>'. Rows older than two days are removed.
CREATE TABLE llm_connection_request_count (
    connection_key text        NOT NULL,
    bucket         text        NOT NULL,
    requests       integer     NOT NULL,
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (connection_key, bucket)
);
CREATE INDEX llm_connection_request_count_updated_idx ON llm_connection_request_count (updated_at);
