-- ADR 0057: the agent is the environment (instructions, rules, skills, MCP servers); templates lose their rules and
-- skills. ADR 0058: templates declare parameters and may set values for the parameters of their lineage.
--   * agent gets instructions, rules, skills and mcp_servers;
--   * a template that is not a pristine built-in and has rules or skills of its own gets an agent of its own
--     (<key>-agent, same scope, the harness of its current agent, the ground rules plus its rules, its skills) and
--     names it; pristine built-ins are reshaped by the seed;
--   * task_template drops skills, remove_skills, rules, remove_rules and gets parameters and parameter_values.

ALTER TABLE agent
    ADD COLUMN instructions text  NOT NULL DEFAULT '',
    ADD COLUMN rules        jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN skills       jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN mcp_servers  jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX agent_rules_idx ON agent USING gin (rules);
CREATE INDEX agent_skills_idx ON agent USING gin (skills);

DO $$
DECLARE
    t           record;
    parent      record;
    cur_id      uuid;
    cur_key     varchar(64);
    depth       int;
    eff_agent   varchar(64);
    eff_harness varchar(32);
    candidate   varchar(64);
    agent_rules jsonb;
BEGIN
    FOR t IN
        SELECT * FROM task_template
        WHERE NOT (built_in AND version = 1 AND (updated_by_kind IS NULL OR updated_by_kind = 'SYSTEM'))
          AND (jsonb_array_length(skills) > 0 OR jsonb_array_length(rules) > 0)
        ORDER BY created_at, id
    LOOP
        -- the agent that runs the template now: its own, else the nearest ancestor's (a project template that
        -- extends its own key extends the global one: the current row is never its own parent)
        eff_agent := t.agent_key;
        cur_id := t.id;
        cur_key := t.extends_key;
        depth := 0;
        WHILE eff_agent IS NULL AND cur_key IS NOT NULL AND depth < 8 LOOP
            SELECT p.id, p.agent_key, p.extends_key INTO parent
            FROM task_template p
            WHERE p.key = cur_key AND p.id <> cur_id AND (p.project_id IS NULL OR p.project_id = t.project_id)
            ORDER BY (p.project_id IS NULL), p.created_at
            LIMIT 1;
            EXIT WHEN NOT FOUND;
            eff_agent := parent.agent_key;
            cur_id := parent.id;
            cur_key := parent.extends_key;
            depth := depth + 1;
        END LOOP;

        eff_harness := NULL;
        IF eff_agent IS NOT NULL THEN
            SELECT a.harness INTO eff_harness
            FROM agent a
            WHERE a.key = eff_agent AND (a.project_id IS NULL OR a.project_id = t.project_id)
            ORDER BY (a.project_id IS NULL)
            LIMIT 1;
        END IF;

        -- a key no agent has in any scope; a template whose candidates are taken keeps its agent (never fail)
        candidate := left(t.key, 58) || '-agent';
        IF EXISTS (SELECT 1 FROM agent WHERE key = candidate) THEN
            candidate := left(t.key, 53) || '-agent-' || left(md5(t.id::text), 4);
        END IF;
        CONTINUE WHEN EXISTS (SELECT 1 FROM agent WHERE key = candidate);

        -- the ground rules (when they exist), then the template's own rules, in order and without duplicates
        SELECT coalesce(jsonb_agg(k ORDER BY o), '[]'::jsonb) INTO agent_rules
        FROM (
            SELECT DISTINCT ON (k) k, o FROM (
                SELECT to_jsonb(g.k) AS k, g.o FROM (VALUES ('agent-ground-rules', 1), ('ask-dont-guess', 2)) AS g(k, o)
                WHERE EXISTS (SELECT 1 FROM rule r WHERE r.key = g.k AND (r.project_id IS NULL OR r.project_id = t.project_id))
                UNION ALL
                SELECT e, 100 + n FROM jsonb_array_elements(t.rules) WITH ORDINALITY AS x(e, n)
            ) AS all_rules
            ORDER BY k, o
        ) AS distinct_rules;

        INSERT INTO agent (id, key, name, description, scope_type, project_id, harness, model_profile_id, tags, built_in,
                           created_at, updated_at, updated_by_kind, updated_by_id, updated_by_name,
                           instructions, rules, skills, mcp_servers)
        VALUES (gen_random_uuid(), candidate, left(t.name || ' (agent)', 200),
                'Created from the rules and skills of template ' || t.key || ' (ADR 0057).',
                t.scope_type, t.project_id, eff_harness, NULL, '[]'::jsonb, false,
                now(), now(), 'SYSTEM', NULL, 'system',
                '', agent_rules, t.skills, '[]'::jsonb);

        UPDATE task_template SET agent_key = candidate WHERE id = t.id;
    END LOOP;
END $$;

DROP INDEX IF EXISTS task_template_skills_idx;
DROP INDEX IF EXISTS task_template_rules_idx;

ALTER TABLE task_template
    DROP COLUMN skills,
    DROP COLUMN remove_skills,
    DROP COLUMN rules,
    DROP COLUMN remove_rules,
    ADD COLUMN parameters       jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN parameter_values jsonb NOT NULL DEFAULT '{}'::jsonb;
