-- Budgets with hard stops (ADR 0025, docs/architecture/11-governance.md "Budgets").
-- scope_key is 'PLATFORM' or the project id; period is the UTC month 'YYYY-MM'.

CREATE TABLE budget_config (
    scope_key          text PRIMARY KEY,
    scope              text          NOT NULL,
    project_id         uuid,
    monthly_limit_usd  numeric(18, 6),
    alert_at_percent   integer       NOT NULL DEFAULT 80,
    hard_stop          boolean       NOT NULL DEFAULT true,
    updated_at         timestamptz   NOT NULL,
    updated_by         text
);

-- running spend per scope and month; updated with optimistic locking on version
CREATE TABLE budget_state (
    scope_key      text          NOT NULL,
    period         text          NOT NULL,
    spent_usd      numeric(18, 6) NOT NULL DEFAULT 0,
    alert_sent     boolean       NOT NULL DEFAULT false,
    exceeded_sent  boolean       NOT NULL DEFAULT false,
    version        bigint        NOT NULL DEFAULT 0,
    updated_at     timestamptz   NOT NULL,
    PRIMARY KEY (scope_key, period)
);

-- spend of one agent job through the LLM proxy (per-job caps from the job credential)
CREATE TABLE budget_job_spend (
    job_id      uuid PRIMARY KEY,
    spent_usd   numeric(18, 6) NOT NULL DEFAULT 0,
    tokens      bigint         NOT NULL DEFAULT 0,
    version     bigint         NOT NULL DEFAULT 0,
    updated_at  timestamptz    NOT NULL
);
