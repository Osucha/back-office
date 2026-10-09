-- Hard budget stops the LLM proxy applied to an agent job (ADR 0025); the execution plane tags the job
-- `budget-exceeded` from these columns when it ends.
ALTER TABLE budget_job_spend
    ADD COLUMN refused_reason text,
    ADD COLUMN refused_at     timestamptz;
