-- Index review of the usage queries (docs/architecture/performance.md, measured on 2M usage rows).
-- Indexes only, no semantic change. Not CONCURRENTLY: Flyway's transactional PostgreSQL lock makes a
-- concurrent build wait forever. On a large ledger create them beforehand with
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS ... (same names); this migration then does nothing.

-- summaries by DAY / MODEL / CONTEXT_KIND and the monthly total over a time range: index-only scans
CREATE INDEX IF NOT EXISTS usage_record_occurred_covering_idx ON usage_record (occurred_at)
    INCLUDE (cost, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, model_label, context_kind);

-- per-project summaries and the per-project entries list (newest first)
CREATE INDEX IF NOT EXISTS usage_record_project_occurred_idx ON usage_record (project_id, occurred_at DESC)
    INCLUDE (cost, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, model_label)
    WHERE project_id IS NOT NULL;
