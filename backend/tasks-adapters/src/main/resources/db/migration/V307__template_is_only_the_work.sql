-- ADR 0061: the template is only the work; the step chooses the agent and the model.
--
--   * Task steps of stored workflows (workflow_definition), of library versions (library_workflow_version) and of the
--     definition snapshots of runs that have not finished (workflow_run, so a waiting run continues with the same agent)
--     that name no agent of their own (overrides.agentKey, or the legacy config key "agent") get the agent their template
--     lineage named (task_template.agent_key, nearest template first; a project template shadows the global one, as the
--     resolver does; library versions are seen from their source project).
--   * A step without a model of its own whose lineage named one (task_template.model_profile_id) gets that model, unless
--     the step's agent has a model: the old order was step, agent, template, so the agent's model stays in front.
--   * Then task_template.agent_key and task_template.model_profile_id are dropped.
--
-- The step rewrite lives here, in the Task Library's range, because it must run before the columns are dropped and
-- Flyway runs the modules' migrations out of order (V121 of the projects module reads task_template the same way).
-- Tables of other modules are touched only when they exist. A stored step without an agent now runs with "opencode"
-- when its work needs a coding agent (Spec Kit phase, push, prepareOnly), else with "model-answer".

CREATE FUNCTION adr0061_step_agents(nodes jsonb, project uuid) RETURNS jsonb LANGUAGE plpgsql AS $fn$
DECLARE
    node          jsonb;
    result        jsonb := '[]'::jsonb;
    ov            jsonb;
    cfg           jsonb;
    template_key  text;
    step_agent    text;
    lineage_agent text;
    lineage_model uuid;
    agent_model   uuid;
    cur_key       text;
    cur_id        uuid;
    depth         int;
    t             record;
BEGIN
    IF nodes IS NULL OR jsonb_typeof(nodes) <> 'array' THEN
        RETURN nodes;
    END IF;
    FOR node IN SELECT value FROM jsonb_array_elements(nodes) LOOP
        template_key := nullif(btrim(coalesce(node -> 'task' ->> 'templateKey', '')), '');
        IF template_key IS NULL THEN
            result := result || jsonb_build_array(node);
            CONTINUE;
        END IF;
        ov := node -> 'task' -> 'overrides';
        IF ov IS NULL OR jsonb_typeof(ov) <> 'object' THEN
            ov := '{}'::jsonb;
        END IF;
        cfg := ov -> 'config';
        step_agent := nullif(btrim(coalesce(ov ->> 'agentKey', '')), '');
        IF step_agent IS NULL AND jsonb_typeof(cfg) = 'object' AND cfg ->> 'agent' IN ('CLAUDE_CODE', 'OPENCODE') THEN
            step_agent := CASE cfg ->> 'agent' WHEN 'CLAUDE_CODE' THEN 'claude-code' ELSE 'opencode' END;
        END IF;

        -- the lineage: nearest template first; a project template that extends its own key continues with the global one
        lineage_agent := NULL;
        lineage_model := NULL;
        cur_key := template_key;
        cur_id := NULL;
        depth := 0;
        WHILE cur_key IS NOT NULL AND depth < 8 LOOP
            SELECT tt.id, tt.agent_key, tt.model_profile_id, tt.extends_key INTO t
              FROM task_template tt
             WHERE tt.key = cur_key
               AND (cur_id IS NULL OR tt.id <> cur_id)
               AND (tt.project_id IS NULL OR tt.project_id = project)
             ORDER BY (tt.project_id IS NULL), tt.created_at
             LIMIT 1;
            EXIT WHEN NOT FOUND;
            IF lineage_agent IS NULL THEN lineage_agent := t.agent_key; END IF;
            IF lineage_model IS NULL THEN lineage_model := t.model_profile_id; END IF;
            cur_id := t.id;
            cur_key := t.extends_key;
            depth := depth + 1;
        END LOOP;

        IF step_agent IS NULL AND lineage_agent IS NOT NULL THEN
            ov := ov || jsonb_build_object('agentKey', lineage_agent);
        END IF;
        IF lineage_model IS NOT NULL AND nullif(btrim(coalesce(ov ->> 'modelProfileId', '')), '') IS NULL THEN
            agent_model := NULL;
            SELECT a.model_profile_id INTO agent_model
              FROM agent a
             WHERE a.key = coalesce(step_agent, lineage_agent, 'model-answer')
               AND (a.project_id IS NULL OR a.project_id = project)
             ORDER BY (a.project_id IS NULL)
             LIMIT 1;
            IF agent_model IS NULL THEN
                ov := ov || jsonb_build_object('modelProfileId', lineage_model::text);
            END IF;
        END IF;

        IF ov IS DISTINCT FROM coalesce(node -> 'task' -> 'overrides', '{}'::jsonb) THEN
            node := jsonb_set(node, '{task,overrides}', ov, true);
        END IF;
        result := result || jsonb_build_array(node);
    END LOOP;
    RETURN result;
END $fn$;

DO $$
BEGIN
    IF to_regclass('workflow_definition') IS NOT NULL THEN
        UPDATE workflow_definition wd
           SET nodes = adr0061_step_agents(wd.nodes, wd.project_id)
         WHERE adr0061_step_agents(wd.nodes, wd.project_id) IS DISTINCT FROM wd.nodes;
    END IF;

    IF to_regclass('library_workflow_version') IS NOT NULL THEN
        UPDATE library_workflow_version v
           SET nodes = adr0061_step_agents(v.nodes, v.source_project_id)
         WHERE adr0061_step_agents(v.nodes, v.source_project_id) IS DISTINCT FROM v.nodes;
    END IF;

    IF to_regclass('workflow_run') IS NOT NULL THEN
        UPDATE workflow_run r
           SET definition_snapshot = jsonb_set(r.definition_snapshot, '{nodes}', adr0061_step_agents(r.definition_snapshot -> 'nodes', r.project_id))
         WHERE r.status IN ('PENDING', 'RUNNING', 'WAITING')
           AND jsonb_typeof(r.definition_snapshot -> 'nodes') = 'array'
           AND adr0061_step_agents(r.definition_snapshot -> 'nodes', r.project_id) IS DISTINCT FROM r.definition_snapshot -> 'nodes';
    END IF;
END $$;

DROP FUNCTION adr0061_step_agents(jsonb, uuid);

DROP INDEX IF EXISTS task_template_agent_key_idx;
ALTER TABLE task_template DROP COLUMN agent_key;
ALTER TABLE task_template DROP COLUMN model_profile_id;
