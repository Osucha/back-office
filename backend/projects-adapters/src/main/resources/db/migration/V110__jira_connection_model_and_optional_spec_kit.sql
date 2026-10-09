-- ADR 0035: the Jira connection model.
--   * personal connections (scope USER, owned by one user, used only with that user's action);
--   * scoped Atlassian API tokens (token_kind SCOPED: cloud id + declared scopes, called through the API gateway);
--   * the classic-token acknowledgement ("this token can do everything your account can");
--   * per project: the system connection and an ordered list of further connections.
-- ADR 0036: Spec Kit is optional per project (off for new projects).

-- ---- connections ---------------------------------------------------------------------------------------------------

ALTER TABLE connection DROP CONSTRAINT IF EXISTS connection_scope_check;
ALTER TABLE connection ADD CONSTRAINT connection_scope_check CHECK (scope IN ('PLATFORM', 'PROJECT', 'USER'));

ALTER TABLE connection
    ADD COLUMN IF NOT EXISTS owner_user_id        uuid,
    ADD COLUMN IF NOT EXISTS owner_name           text,
    ADD COLUMN IF NOT EXISTS token_kind           text    NOT NULL DEFAULT 'CLASSIC',
    ADD COLUMN IF NOT EXISTS cloud_id             text,
    ADD COLUMN IF NOT EXISTS token_scopes         jsonb   NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS classic_acknowledged boolean NOT NULL DEFAULT false;

ALTER TABLE connection ADD CONSTRAINT connection_token_kind CHECK (token_kind IN ('CLASSIC', 'SCOPED'));
ALTER TABLE connection ADD CONSTRAINT connection_scope_owner CHECK ((scope = 'USER') = (owner_user_id IS NOT NULL));
ALTER TABLE connection ADD CONSTRAINT connection_scoped_cloud_id CHECK (token_kind <> 'SCOPED' OR cloud_id IS NOT NULL);

-- Connections created before ADR 0035 followed the dedicated reader/writer-account guidance of ADR 0030; they keep
-- working without asking anybody to acknowledge their classic token again.
UPDATE connection SET classic_acknowledged = true WHERE token_kind = 'CLASSIC';

CREATE INDEX IF NOT EXISTS idx_connection_owner ON connection (owner_user_id);

-- names are unique per owner: the platform, one project, or one user
DROP INDEX IF EXISTS uq_connection_name;
CREATE UNIQUE INDEX uq_connection_name
    ON connection (scope, COALESCE(project_id, owner_user_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

-- ---- projects --------------------------------------------------------------------------------------------------------

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS spec_kit_enabled          boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS jira_system_connection_id uuid REFERENCES connection (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS jira_connection_ids       jsonb   NOT NULL DEFAULT '[]'::jsonb;

-- The system connection of every project that uses Jira (ADR 0035, migration rule):
--   a project uses Jira when it has a Jira project key or JQL filter, a project connection, an enabled intake watcher,
--   or a workflow with a Jira step (fetch-issue, notify-jira, transition-issue);
--   candidates are its own project connections and every platform connection whose allow-list is empty or contains the
--   project's Jira key (jira_project_key, else the project key); the legacy environment credential is not stored and
--   cannot be chosen (projects without a stored candidate keep the ADR 0030 rule, which includes it);
--   the system connection is the least-privileged reader-or-writer: a connection with ISSUE_READ first, then the
--   fewest capabilities, then project before platform, then verification OK > WARNING > not verified > FAILED, then
--   name and id;
--   every other candidate becomes an additional connection in the same order, so the least-privilege fallback of
--   SYSTEM steps sees exactly the connections it saw before.
WITH jira_projects AS (
    SELECT p.id, upper(COALESCE(NULLIF(btrim(p.jira_project_key), ''), p.key)) AS jira_key
      FROM project p
     WHERE NULLIF(btrim(p.jira_project_key), '') IS NOT NULL
        OR NULLIF(btrim(p.jira_jql), '') IS NOT NULL
        OR EXISTS (SELECT 1 FROM connection c WHERE c.project_id = p.id)
        OR EXISTS (SELECT 1 FROM jira_intake i WHERE i.project_id = p.id AND i.enabled)
        OR EXISTS (
               SELECT 1
                 FROM workflow_definition w, jsonb_array_elements(w.nodes) n
                WHERE w.project_id = p.id
                  AND n -> 'task' ->> 'templateKey' IN ('fetch-issue', 'notify-jira', 'transition-issue')
           )
), candidates AS (
    SELECT jp.id AS project_id, c.id AS connection_id,
           row_number() OVER (
               PARTITION BY jp.id
               ORDER BY (c.capabilities ? 'ISSUE_READ') DESC,
                        jsonb_array_length(c.capabilities),
                        CASE WHEN c.scope = 'PROJECT' THEN 0 ELSE 1 END,
                        CASE c.verification ->> 'status' WHEN 'OK' THEN 0 WHEN 'WARNING' THEN 1 WHEN 'FAILED' THEN 3 ELSE 2 END,
                        lower(c.name), c.id
           ) AS rank
      FROM jira_projects jp
      JOIN connection c
        ON (c.scope = 'PROJECT' AND c.project_id = jp.id)
        OR (c.scope = 'PLATFORM' AND (jsonb_array_length(c.allowed_project_keys) = 0 OR c.allowed_project_keys ? jp.jira_key))
)
UPDATE project p
   SET jira_system_connection_id = s.system_id,
       jira_connection_ids = s.additional
  FROM (
      SELECT project_id,
             (array_agg(connection_id ORDER BY rank))[1] AS system_id,
             COALESCE(jsonb_agg(to_jsonb(connection_id::text) ORDER BY rank) FILTER (WHERE rank > 1), '[]'::jsonb) AS additional
        FROM candidates
       GROUP BY project_id
  ) s
 WHERE p.id = s.project_id
   AND p.jira_system_connection_id IS NULL;

-- ADR 0036: Spec Kit stays on for every project that already uses it: a workflow node whose template is a Spec Kit
-- template (built-in spec-kit-*, or a library template whose own or inherited executor is SPEC_KIT; the task library
-- table exists only when the tasks module has migrated, which is checked first).
UPDATE project p
   SET spec_kit_enabled = true
 WHERE EXISTS (
           SELECT 1
             FROM workflow_definition w, jsonb_array_elements(w.nodes) n
            WHERE w.project_id = p.id
              AND n -> 'task' ->> 'templateKey' LIKE 'spec-kit-%'
       );

DO $$
BEGIN
    IF to_regclass('task_template') IS NOT NULL THEN
        EXECUTE $sql$
            WITH RECURSIVE spec_kit_templates AS (
                SELECT t.key FROM task_template t WHERE t.executor = 'SPEC_KIT'
                UNION
                SELECT t.key FROM task_template t JOIN spec_kit_templates s ON t.extends_key = s.key WHERE t.executor IS NULL
            )
            UPDATE project p
               SET spec_kit_enabled = true
             WHERE NOT p.spec_kit_enabled
               AND EXISTS (
                       SELECT 1
                         FROM workflow_definition w, jsonb_array_elements(w.nodes) n
                        WHERE w.project_id = p.id
                          AND n -> 'task' ->> 'templateKey' IN (SELECT key FROM spec_kit_templates)
                   )
        $sql$;
    END IF;
END
$$;
