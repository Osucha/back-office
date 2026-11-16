-- ADR 0044: a runner adopts only workspaces of its own pool. Rows without a pool get one where it is unambiguous:
-- the pool of the runner that holds the volume, else the single pool all jobs of that workspace were routed to.
-- Everything else keeps pool_id NULL: no runner sees or adopts it (a runner holding such a volume deletes it), and
-- the next job of the workspace routes it as usual (assignPool).

UPDATE runner_workspace w
   SET pool_id = r.pool_id
  FROM runner r
 WHERE w.pool_id IS NULL
   AND w.runner_id = r.id;

UPDATE runner_workspace w
   SET pool_id = j.pool_id
  FROM (SELECT workspace_name, min(pool_id) AS pool_id
          FROM agent_job
         WHERE workspace_name IS NOT NULL AND pool_id IS NOT NULL
         GROUP BY workspace_name
        HAVING count(DISTINCT pool_id) = 1) j
 WHERE w.pool_id IS NULL
   AND w.runner_id IS NULL
   AND w.name = j.workspace_name;
