-- ADR 0056: agents run tasks, actions are nodes, no executor.
--   * agent: a library entry (global or project) with a harness (none = model answer) and optionally a model;
--   * task_template.agent_key replaces executor (inherited, nearest non-null wins; none anywhere = model-answer);
--   * the config key "agent" (CLAUDE_CODE / OPENCODE) becomes the agent key;
--   * the five built-in action templates leave the library (their steps are read as ACTION nodes).

CREATE TABLE agent (
    id               uuid PRIMARY KEY,
    key              varchar(64)  NOT NULL,
    name             varchar(200) NOT NULL,
    description      text         NOT NULL DEFAULT '',
    scope_type       varchar(16)  NOT NULL,
    project_id       uuid,
    harness          varchar(32),
    model_profile_id uuid,
    tags             jsonb        NOT NULL DEFAULT '[]'::jsonb,
    built_in         boolean      NOT NULL DEFAULT false,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    updated_by_kind  varchar(16),
    updated_by_id    uuid,
    updated_by_name  varchar(200),
    CONSTRAINT agent_scope_chk CHECK (
        (scope_type = 'GLOBAL' AND project_id IS NULL) OR (scope_type = 'PROJECT' AND project_id IS NOT NULL)
    ),
    CONSTRAINT agent_harness_chk CHECK (harness IS NULL OR harness IN ('OPENCODE', 'CLAUDE_CODE')),
    CONSTRAINT agent_key_scope_uq UNIQUE NULLS NOT DISTINCT (key, scope_type, project_id)
);

CREATE INDEX agent_project_idx ON agent (project_id);

-- The built-in agents, so the agent keys set below resolve at once; the seed keeps them in line with the shipped
-- definition afterwards (same text here, so it finds nothing to upgrade).
INSERT INTO agent (id, key, name, description, scope_type, project_id, harness, model_profile_id, tags, built_in,
                   created_at, updated_at, updated_by_kind, updated_by_id, updated_by_name)
VALUES
    (gen_random_uuid(), 'model-answer', 'Model answer',
     'One model call without tools or repository: the answer (structured with an output schema) is the step''s result.',
     'GLOBAL', NULL, NULL, NULL, '[]'::jsonb, true, now(), now(), 'SYSTEM', NULL, 'system'),
    (gen_random_uuid(), 'opencode', 'OpenCode',
     'OpenCode coding agent session in the runner sandbox with the step''s workspace, rules and skills.',
     'GLOBAL', NULL, 'OPENCODE', NULL, '[]'::jsonb, true, now(), now(), 'SYSTEM', NULL, 'system'),
    (gen_random_uuid(), 'claude-code', 'Claude Code',
     'Claude Code coding agent session in the runner sandbox with the step''s workspace, rules and skills.',
     'GLOBAL', NULL, 'CLAUDE_CODE', NULL, '[]'::jsonb, true, now(), now(), 'SYSTEM', NULL, 'system')
ON CONFLICT ON CONSTRAINT agent_key_scope_uq DO NOTHING;

ALTER TABLE task_template ADD COLUMN agent_key varchar(64);

-- 1. A template's own config key "agent" names its agent.
UPDATE task_template
SET agent_key = CASE config ->> 'agent' WHEN 'CLAUDE_CODE' THEN 'claude-code' ELSE 'opencode' END
WHERE config ->> 'agent' IN ('CLAUDE_CODE', 'OPENCODE');

-- 2. A coding executor without its own "agent": OpenCode (the former default), unless an ancestor chose the harness
--    with its config (then the template inherits that agent).
WITH RECURSIVE ancestors (template_id, ancestor_key, depth) AS (
    SELECT t.id, t.extends_key, 1 FROM task_template t WHERE t.extends_key IS NOT NULL
    UNION ALL
    SELECT a.template_id, p.extends_key, a.depth + 1
    FROM ancestors a
    JOIN task_template p ON p.key = a.ancestor_key
    WHERE p.extends_key IS NOT NULL AND p.extends_key <> p.key AND a.depth < 8
)
UPDATE task_template t
SET agent_key = 'opencode'
WHERE t.agent_key IS NULL
  AND t.executor IN ('IMPLEMENT', 'SPEC_KIT')
  AND NOT EXISTS (
      SELECT 1 FROM ancestors a JOIN task_template p ON p.key = a.ancestor_key
      WHERE a.template_id = t.id AND p.config ->> 'agent' IN ('CLAUDE_CODE', 'OPENCODE')
  );

-- 3. A model answer template below a coding ancestor says so, since the nearest agent wins.
WITH RECURSIVE ancestors (template_id, ancestor_key, depth) AS (
    SELECT t.id, t.extends_key, 1 FROM task_template t WHERE t.extends_key IS NOT NULL
    UNION ALL
    SELECT a.template_id, p.extends_key, a.depth + 1
    FROM ancestors a
    JOIN task_template p ON p.key = a.ancestor_key
    WHERE p.extends_key IS NOT NULL AND p.extends_key <> p.key AND a.depth < 8
)
UPDATE task_template t
SET agent_key = 'model-answer'
WHERE t.agent_key IS NULL
  AND t.executor = 'LLM'
  AND EXISTS (
      SELECT 1 FROM ancestors a JOIN task_template p ON p.key = a.ancestor_key
      WHERE a.template_id = t.id AND (p.executor IN ('IMPLEMENT', 'SPEC_KIT') OR p.config ? 'agent')
  );

-- The harness is the agent's job now.
UPDATE task_template SET config = config - 'agent' WHERE config ? 'agent';

-- The built-in action templates leave the library, unless another template still extends them.
DELETE FROM task_template t
WHERE t.built_in
  AND t.scope_type = 'GLOBAL'
  AND t.key IN ('fetch-issue', 'notify-jira', 'transition-issue', 'start-workflow', 'project-digest')
  AND NOT EXISTS (SELECT 1 FROM task_template c WHERE c.extends_key = t.key AND c.id <> t.id);

CREATE INDEX task_template_agent_key_idx ON task_template (agent_key);

ALTER TABLE task_template DROP COLUMN executor;
