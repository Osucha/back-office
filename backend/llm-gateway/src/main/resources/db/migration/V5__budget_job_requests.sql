-- Per-job cap on metered LLM proxy calls (ADR 0029): counted before each call, compared with
-- JobCredential.maxRequests (backoffice.runner.max-llm-requests-per-job).
ALTER TABLE budget_job_spend
    ADD COLUMN requests bigint NOT NULL DEFAULT 0;
