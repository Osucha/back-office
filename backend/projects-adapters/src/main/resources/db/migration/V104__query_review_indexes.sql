-- Index review of the hot queries (docs/architecture/performance.md, measured on 200k runs / 100k CI
-- executions). Indexes only, no semantic change. Not CONCURRENTLY: Flyway's transactional PostgreSQL lock
-- makes a concurrent build wait forever. On a large install create them beforehand with
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS ... (same names); this migration then does nothing.

-- runs list / dashboard filtered by status, newest first (status=FAILED, active runs): no sort, no heap filter
CREATE INDEX IF NOT EXISTS idx_workflow_run_status_created ON workflow_run (status, created_at DESC);
