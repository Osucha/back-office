-- ADR 0065: the candidate's work and the judge's verdicts at API list prices, next to the spent cost (as ADR 0042 for
-- workflow steps). Equal to the spent cost through the gateway; on a subscription (Claude Code login) the harness's
-- estimate, while the spent cost is 0.
ALTER TABLE benchmark_attempt ADD COLUMN api_cost numeric(18, 6) NOT NULL DEFAULT 0;
ALTER TABLE benchmark_attempt ADD COLUMN judge_api_cost numeric(18, 6) NOT NULL DEFAULT 0;

-- Attempts stored before: the spent cost, unless a runner job below reported its estimate.
UPDATE benchmark_attempt SET api_cost = cost, judge_api_cost = judge_cost;

-- The runner jobs' reported cost (agent_job.cost_usd: Claude Code's total_cost_usd). agent_job belongs to the
-- agent-runner module (V51); Flyway runs out of order and a database without it (an evaluation-only schema) keeps the
-- spent cost, so the statements run only when the table exists.
DO $$
BEGIN
    IF to_regclass('agent_job') IS NULL THEN
        RETURN;
    END IF;

    -- coding attempts: the candidate's job is runner_job_id
    EXECUTE $sql$
        UPDATE benchmark_attempt a
           SET api_cost = j.cost_usd
          FROM agent_job j
         WHERE j.id = a.runner_job_id
           AND j.cost_usd IS NOT NULL
    $sql$;

    -- prompt attempts of a login candidate: its answer is the attempt's earliest job (the judge's calls come after it)
    EXECUTE $sql$
        UPDATE benchmark_attempt a
           SET api_cost = first_job.cost_usd
          FROM (SELECT DISTINCT ON (j.attempt_id) j.attempt_id, j.cost_usd
                  FROM agent_job j
                 WHERE j.attempt_id IS NOT NULL
                 ORDER BY j.attempt_id, j.queued_at, j.id) first_job,
               model_profile p
         WHERE first_job.attempt_id = a.id
           AND a.kind = 'PROMPT'
           AND a.runner_job_id IS NULL
           AND p.id = a.model_profile_id
           AND p.runtime = 'CLAUDE_CODE_LOGIN'
           AND first_job.cost_usd IS NOT NULL
    $sql$;

    -- a login judge: the attempt's other jobs (not the coding candidate's runner_job_id, not a login candidate's answer)
    EXECUTE $sql$
        UPDATE benchmark_attempt a
           SET judge_api_cost = judged.total
          FROM (SELECT a2.id AS attempt_id, sum(j.cost_usd) AS total
                  FROM benchmark_attempt a2
                  JOIN benchmark b ON b.id = a2.benchmark_id
                  JOIN model_profile jp ON jp.id = b.judge_model_profile_id AND jp.runtime = 'CLAUDE_CODE_LOGIN'
                  LEFT JOIN model_profile cp ON cp.id = a2.model_profile_id
                  JOIN agent_job j ON j.attempt_id = a2.id
                 WHERE j.cost_usd IS NOT NULL
                   AND j.id IS DISTINCT FROM a2.runner_job_id
                   AND NOT (a2.kind = 'PROMPT' AND a2.runner_job_id IS NULL AND coalesce(cp.runtime, 'API') = 'CLAUDE_CODE_LOGIN'
                            AND j.id = (SELECT j1.id FROM agent_job j1 WHERE j1.attempt_id = a2.id ORDER BY j1.queued_at, j1.id LIMIT 1))
                 GROUP BY a2.id) judged
         WHERE judged.attempt_id = a.id
    $sql$;
END
$$;
