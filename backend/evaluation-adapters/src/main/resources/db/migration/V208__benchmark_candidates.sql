-- ADR 0059: benchmarks compare candidates (an agent with a model); attempts record the agent they ran with.
-- candidates: [{agentKey, agentName, harness, modelProfileId}]; empty = a benchmark of models (as before ADR 0059).
ALTER TABLE benchmark ADD COLUMN candidates jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE benchmark_attempt ADD COLUMN agent_key varchar(100);
ALTER TABLE benchmark_attempt ADD COLUMN agent_name varchar(200);
ALTER TABLE benchmark_attempt ADD COLUMN harness varchar(32);

-- Attempts before ADR 0059 ran as the agent a benchmark of models implies: model-answer for prompt tasks, the agent of
-- the benchmark's coding harness for coding tasks.
UPDATE benchmark_attempt a
   SET agent_key  = CASE WHEN a.kind = 'PROMPT' THEN 'model-answer'
                         WHEN b.coding_harness = 'CLAUDE_CODE' THEN 'claude-code'
                         ELSE 'opencode' END,
       agent_name = CASE WHEN a.kind = 'PROMPT' THEN 'Model answer'
                         WHEN b.coding_harness = 'CLAUDE_CODE' THEN 'Claude Code'
                         ELSE 'OpenCode' END,
       harness    = CASE WHEN a.kind = 'PROMPT' THEN NULL
                         WHEN b.coding_harness = 'CLAUDE_CODE' THEN 'CLAUDE_CODE'
                         ELSE 'OPENCODE' END
  FROM benchmark b
 WHERE b.id = a.benchmark_id AND a.agent_key IS NULL;

CREATE INDEX benchmark_attempt_agent_idx ON benchmark_attempt (benchmark_id, agent_key, model_profile_id);
