-- ADR 0062: one instruction, parameters with sources, session and directory on the agent.
--
--   * Task steps of stored workflows (workflow_definition), of library versions (library_workflow_version) and of the
--     definition snapshots of runs that have not finished (workflow_run) are rewritten first, while task_template still
--     has its run inputs (the same lineage walk as V307: nearest template first; a project template shadows the global
--     one; library versions are seen from their source project):
--       - every run input of the step's template lineage becomes a START binding of the parameter of that name
--         (overrides.parameterBindings[name] = {"source": "START"}) unless the step binds it already, so the start forms
--         stay the same;
--       - the legacy step values overrides.parameters become VALUE bindings (non-null, non-blank values only);
--       - overrides.systemPromptAppend and overrides.promptAppend become overrides.instructionsAppend (joined by a blank
--         line after an existing instructionsAppend), overrides.promptReplace becomes overrides.instructionsReplace;
--       - the step config keys session, continueSession and directory are dropped (they are the agent's now; a legacy
--         sessionRole stays and keeps sharing a session).
--   * task_template: instructions = the system and the user prompt joined by a blank line (each trimmed, blank ones
--     skipped), instructions_mode = prompt_mode; every run input becomes a parameter (type TEXT, MULTILINE or ISSUE_KEY,
--     label, required, help, no default; a dash in the name becomes an underscore) unless the template already declares
--     a parameter of that name; then prompt_system, prompt_user, prompt_mode and run_inputs are dropped. Templates never
--     had a sandbox policy column (it was an API field only).
--   * agent: session (PER_STEP, PER_RUN, FRESH; default PER_STEP); the directory lives in agent.access (no key = RUN).
--
-- Tables of other modules are touched only when they exist (Flyway runs the modules' migrations out of order).

ALTER TABLE agent ADD COLUMN session varchar(16) NOT NULL DEFAULT 'PER_STEP';

CREATE FUNCTION adr0062_text(value jsonb) RETURNS text LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE WHEN value IS NULL OR jsonb_typeof(value) = 'null' THEN NULL
                WHEN jsonb_typeof(value) = 'string' THEN nullif(btrim(value #>> '{}'), '')
                ELSE value::text END
$fn$;

CREATE FUNCTION adr0062_param_name(name text) RETURNS text LANGUAGE sql IMMUTABLE AS $fn$
    SELECT replace(name, '-', '_')
$fn$;

CREATE FUNCTION adr0062_steps(nodes jsonb, project uuid) RETURNS jsonb LANGUAGE plpgsql AS $fn$
DECLARE
    node          jsonb;
    result        jsonb := '[]'::jsonb;
    ov            jsonb;
    cfg           jsonb;
    bindings      jsonb;
    legacy        jsonb;
    kv            record;
    template_key  text;
    append_text   text;
    replace_text  text;
    input_name    text;
    cur_key       text;
    cur_id        uuid;
    depth         int;
    t             record;
    ri            jsonb;
BEGIN
    IF nodes IS NULL OR jsonb_typeof(nodes) <> 'array' THEN
        RETURN nodes;
    END IF;
    FOR node IN SELECT value FROM jsonb_array_elements(nodes) LOOP
        template_key := nullif(btrim(coalesce(node -> 'task' ->> 'templateKey', '')), '');
        ov := node -> 'task' -> 'overrides';
        IF template_key IS NULL OR ov IS NULL OR jsonb_typeof(ov) <> 'object' THEN
            IF template_key IS NOT NULL AND (ov IS NULL OR jsonb_typeof(ov) <> 'object') THEN
                ov := '{}'::jsonb;
            ELSE
                result := result || jsonb_build_array(node);
                CONTINUE;
            END IF;
        END IF;

        bindings := CASE WHEN jsonb_typeof(ov -> 'parameterBindings') = 'object' THEN ov -> 'parameterBindings' ELSE '{}'::jsonb END;

        -- legacy step values: VALUE bindings
        legacy := ov -> 'parameters';
        IF jsonb_typeof(legacy) = 'object' THEN
            FOR kv IN SELECT key, value FROM jsonb_each(legacy) LOOP
                IF NOT bindings ? kv.key AND jsonb_typeof(kv.value) <> 'null'
                   AND NOT (jsonb_typeof(kv.value) = 'string' AND btrim(kv.value #>> '{}') = '') THEN
                    bindings := bindings || jsonb_build_object(kv.key, jsonb_build_object('source', 'VALUE', 'value', kv.value));
                END IF;
            END LOOP;
        END IF;

        -- the run inputs of the lineage: START bindings
        cur_key := template_key;
        cur_id := NULL;
        depth := 0;
        WHILE cur_key IS NOT NULL AND depth < 8 LOOP
            SELECT tt.id, tt.extends_key, tt.run_inputs INTO t
              FROM task_template tt
             WHERE tt.key = cur_key
               AND (cur_id IS NULL OR tt.id <> cur_id)
               AND (tt.project_id IS NULL OR tt.project_id = project)
             ORDER BY (tt.project_id IS NULL), tt.created_at
             LIMIT 1;
            EXIT WHEN NOT FOUND;
            IF jsonb_typeof(t.run_inputs) = 'array' THEN
                FOR ri IN SELECT value FROM jsonb_array_elements(t.run_inputs) LOOP
                    input_name := adr0062_param_name(nullif(btrim(coalesce(ri ->> 'name', '')), ''));
                    IF input_name IS NOT NULL AND NOT bindings ? input_name THEN
                        bindings := bindings || jsonb_build_object(input_name, jsonb_build_object('source', 'START'));
                    END IF;
                END LOOP;
            END IF;
            cur_id := t.id;
            cur_key := t.extends_key;
            depth := depth + 1;
        END LOOP;

        -- the step's notes and replacement
        append_text := concat_ws(E'\n\n',
            adr0062_text(ov -> 'instructionsAppend'), adr0062_text(ov -> 'systemPromptAppend'), adr0062_text(ov -> 'promptAppend'));
        replace_text := coalesce(adr0062_text(ov -> 'instructionsReplace'), adr0062_text(ov -> 'promptReplace'));
        ov := ov - 'systemPromptAppend' - 'promptAppend' - 'promptReplace' - 'parameters' - 'instructionsAppend' - 'instructionsReplace';
        IF append_text <> '' THEN
            ov := ov || jsonb_build_object('instructionsAppend', append_text);
        END IF;
        IF replace_text IS NOT NULL THEN
            ov := ov || jsonb_build_object('instructionsReplace', replace_text);
        END IF;
        ov := ov - 'parameterBindings';
        IF bindings <> '{}'::jsonb THEN
            ov := ov || jsonb_build_object('parameterBindings', bindings);
        END IF;

        -- session and directory are the agent's
        cfg := ov -> 'config';
        IF jsonb_typeof(cfg) = 'object' AND (cfg ? 'session' OR cfg ? 'continueSession' OR cfg ? 'directory') THEN
            ov := jsonb_set(ov, '{config}', cfg - 'session' - 'continueSession' - 'directory', true);
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
           SET nodes = adr0062_steps(wd.nodes, wd.project_id)
         WHERE adr0062_steps(wd.nodes, wd.project_id) IS DISTINCT FROM wd.nodes;
    END IF;

    IF to_regclass('library_workflow_version') IS NOT NULL THEN
        UPDATE library_workflow_version v
           SET nodes = adr0062_steps(v.nodes, v.source_project_id)
         WHERE adr0062_steps(v.nodes, v.source_project_id) IS DISTINCT FROM v.nodes;
    END IF;

    IF to_regclass('workflow_run') IS NOT NULL THEN
        UPDATE workflow_run r
           SET definition_snapshot = jsonb_set(r.definition_snapshot, '{nodes}', adr0062_steps(r.definition_snapshot -> 'nodes', r.project_id))
         WHERE r.status IN ('PENDING', 'RUNNING', 'WAITING')
           AND jsonb_typeof(r.definition_snapshot -> 'nodes') = 'array'
           AND adr0062_steps(r.definition_snapshot -> 'nodes', r.project_id) IS DISTINCT FROM r.definition_snapshot -> 'nodes';
    END IF;
END $$;

-- templates: one instruction
ALTER TABLE task_template ADD COLUMN instructions text;
ALTER TABLE task_template ADD COLUMN instructions_mode varchar(16) NOT NULL DEFAULT 'APPEND';

UPDATE task_template
   SET instructions = nullif(concat_ws(E'\n\n', nullif(btrim(prompt_system), ''), nullif(btrim(prompt_user), '')), ''),
       instructions_mode = coalesce(prompt_mode, 'APPEND');

-- templates: run inputs become parameters
UPDATE task_template tt
   SET parameters = coalesce(tt.parameters, '[]'::jsonb) || (
       SELECT coalesce(jsonb_agg(p ORDER BY n), '[]'::jsonb)
         FROM (
             SELECT DISTINCT ON (adr0062_param_name(ri ->> 'name')) n,
                    jsonb_strip_nulls(jsonb_build_object(
                        'name', adr0062_param_name(ri ->> 'name'),
                        'label', coalesce(nullif(btrim(ri ->> 'label'), ''), ri ->> 'name'),
                        'type', CASE WHEN ri ->> 'type' IN ('TEXT', 'MULTILINE', 'ISSUE_KEY') THEN ri ->> 'type' ELSE 'TEXT' END,
                        'required', coalesce((ri ->> 'required')::boolean, false),
                        'help', nullif(btrim(coalesce(ri ->> 'help', '')), ''))) AS p
               FROM jsonb_array_elements(tt.run_inputs) WITH ORDINALITY AS x(ri, n)
              WHERE nullif(btrim(coalesce(ri ->> 'name', '')), '') IS NOT NULL
                AND NOT EXISTS (
                    SELECT 1 FROM jsonb_array_elements(coalesce(tt.parameters, '[]'::jsonb)) d
                     WHERE d ->> 'name' = adr0062_param_name(ri ->> 'name'))
              ORDER BY adr0062_param_name(ri ->> 'name'), n
         ) declared
   )
 WHERE jsonb_typeof(tt.run_inputs) = 'array' AND jsonb_array_length(tt.run_inputs) > 0;

ALTER TABLE task_template DROP COLUMN prompt_system;
ALTER TABLE task_template DROP COLUMN prompt_user;
ALTER TABLE task_template DROP COLUMN prompt_mode;
ALTER TABLE task_template DROP COLUMN run_inputs;

DROP FUNCTION adr0062_steps(jsonb, uuid);
DROP FUNCTION adr0062_param_name(text);
DROP FUNCTION adr0062_text(jsonb);
