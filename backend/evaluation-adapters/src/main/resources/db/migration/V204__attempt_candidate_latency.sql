-- ADR 0046: how long the candidate took (model answer or agent job), without the judge, queueing or retries.
ALTER TABLE benchmark_attempt ADD COLUMN candidate_latency_ms bigint;
