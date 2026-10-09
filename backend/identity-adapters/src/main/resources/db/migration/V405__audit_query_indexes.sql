-- Index review of the audit search (docs/architecture/performance.md, measured on 1M audit rows over 12
-- monthly partitions). Indexes only. Created on the partitioned parent, so every partition (present and
-- future) gets it; an index on a partitioned table cannot be built concurrently (partitions are small).

-- "result" filter of the audit screen (DENIED / FAILURE for security reviews): newest first per partition
-- instead of walking the primary key of every partition and discarding successes
CREATE INDEX IF NOT EXISTS audit_log_result_idx ON audit_log (result, event_time DESC);
