-- ADR 0065: the candidate's own time runs from the start of its job, without queueing for a runner. Attempts stored
-- before measured from the submission; recompute them from the runner jobs where those are known.
DO $$
BEGIN
    IF to_regclass('agent_job') IS NULL THEN
        RETURN;
    END IF;

    -- coding attempts: the candidate's job is runner_job_id
    UPDATE benchmark_attempt a
       SET candidate_latency_ms = (EXTRACT(EPOCH FROM (j.finished_at - j.started_at)) * 1000)::bigint
      FROM agent_job j
     WHERE j.id = a.runner_job_id
       AND a.candidate_latency_ms IS NOT NULL
       AND j.started_at IS NOT NULL
       AND j.finished_at IS NOT NULL
       AND j.finished_at >= j.started_at;

    -- prompt attempts answered by a Claude Code login model: the attempt's earliest job is the candidate's answer
    UPDATE benchmark_attempt a
       SET candidate_latency_ms = (EXTRACT(EPOCH FROM (first_job.finished_at - first_job.started_at)) * 1000)::bigint
      FROM (
            SELECT DISTINCT ON (attempt_id) attempt_id, started_at, finished_at
              FROM agent_job
             WHERE attempt_id IS NOT NULL
             ORDER BY attempt_id, queued_at
           ) first_job,
           model_profile p
     WHERE first_job.attempt_id = a.id
       AND a.kind = 'PROMPT'
       AND a.runner_job_id IS NULL
       AND a.candidate_latency_ms IS NOT NULL
       AND p.id = a.model_profile_id
       AND p.runtime = 'CLAUDE_CODE_LOGIN'
       AND first_job.started_at IS NOT NULL
       AND first_job.finished_at IS NOT NULL
       AND first_job.finished_at >= first_job.started_at;
END $$;
