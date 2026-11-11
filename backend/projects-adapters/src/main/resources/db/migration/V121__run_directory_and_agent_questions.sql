-- ADR 0060: one run directory instead of run workspaces; agents ask questions.
--
--   * workflow_definition / library_workflow_version get setup_command (runs once per run directory);
--   * approval_request gets questions and question_round; kind allows AGENT_QUESTIONS;
--   * project workspaces (V118, ADR 0044) go. What they gave the steps that named them stays:
--       - a workspace's setup command becomes the setup command of the workflows of its project whose steps name it
--         (config.workspace = its key); a workflow with steps of several such workspaces takes the first by key;
--       - a coding step that named a workspace with sources and had no repositories of its own gets the sources as its
--         config.repositories, exactly as the workspace gave them (ADR 0044 effective config): the project's primary
--         repository among the sources (else the first project repository source) gets PUSH_MR when the step pushes,
--         every other source READ, library sources {key, access READ, library true}. Whether a step pushes and runs a
--         coding agent is read from the step config, else from its template lineage (task_template / agent, nearest
--         value wins), else the defaults (it pushes; a coding agent unless the lineage names a model answer). A step
--         whose sources are only the project's primary repository keeps no list: the default (the primary repository
--         with the access its push setting gives) is the same, and stays so when push changes later.
--   * the step key `workspace` is dropped when workflows are read (WorkflowJson); `sessionRole` is read as the step's
--     own session role (steps that shared one keep sharing their session).

ALTER TABLE workflow_definition ADD COLUMN setup_command text;
ALTER TABLE library_workflow_version ADD COLUMN setup_command text;

ALTER TABLE approval_request ADD COLUMN questions jsonb;
ALTER TABLE approval_request ADD COLUMN question_round integer;
ALTER TABLE approval_request ADD COLUMN max_question_rounds integer;
ALTER TABLE approval_request DROP CONSTRAINT IF EXISTS approval_request_kind_check;
ALTER TABLE approval_request ADD CONSTRAINT approval_request_kind_check CHECK (kind IN ('GATE', 'WRITE_CONFIRMATION', 'AGENT_QUESTIONS'));

DO $$
DECLARE
    ws           record;
    wf           record;
    node         jsonb;
    new_nodes    jsonb;
    changed      boolean;
    cfg          jsonb;
    repos        jsonb;
    primary_key  text;
    own_primary  text;
    template_key text;
    agent_key    text;
    harness      text;
    pushes       boolean;
    lineage_push jsonb;
    lineage_prep jsonb;
    has_library  boolean := to_regclass('task_template') IS NOT NULL AND to_regclass('agent') IS NOT NULL;
    t            record;
    cur_key      text;
    cur_id       uuid;
    depth        int;
    entries      jsonb;
    src          jsonb;
BEGIN
    IF to_regclass('project_workspace') IS NULL THEN
        RETURN;
    END IF;

    -- 1. setup commands: the workflows whose steps name a workspace with one
    FOR ws IN SELECT * FROM project_workspace WHERE setup_command IS NOT NULL AND btrim(setup_command) <> '' ORDER BY project_id, key LOOP
        UPDATE workflow_definition wd
           SET setup_command = ws.setup_command
         WHERE wd.project_id = ws.project_id
           AND wd.setup_command IS NULL
           AND EXISTS (
               SELECT 1 FROM jsonb_array_elements(wd.nodes) n
                WHERE n -> 'task' -> 'overrides' -> 'config' ->> 'workspace' = ws.key
           );
    END LOOP;

    -- 2. sources: into the steps that named a workspace and had no repositories of their own
    FOR wf IN
        SELECT wd.id, wd.project_id, wd.nodes, p.repositories AS project_repositories
          FROM workflow_definition wd
          JOIN project p ON p.id = wd.project_id
         WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(wd.nodes) n WHERE n -> 'task' -> 'overrides' -> 'config' ? 'workspace')
    LOOP
        new_nodes := '[]'::jsonb;
        changed := false;
        SELECT r ->> 'key' INTO primary_key
          FROM jsonb_array_elements(wf.project_repositories) r
         WHERE (r ->> 'primary')::boolean IS TRUE
         LIMIT 1;

        FOR node IN SELECT value FROM jsonb_array_elements(wf.nodes) LOOP
            cfg := node -> 'task' -> 'overrides' -> 'config';
            SELECT * INTO ws FROM project_workspace
             WHERE project_id = wf.project_id AND key = cfg ->> 'workspace';
            repos := cfg -> 'repositories';
            IF cfg IS NULL OR NOT FOUND OR jsonb_array_length(ws.sources) = 0
               OR (repos IS NOT NULL AND jsonb_typeof(repos) = 'array' AND jsonb_array_length(repos) > 0) THEN
                new_nodes := new_nodes || jsonb_build_array(node);
                CONTINUE;
            END IF;

            -- the sources are only the project's primary repository: the default gives the same
            IF jsonb_array_length(ws.sources) = 1
               AND coalesce((ws.sources -> 0 ->> 'library')::boolean, false) = false
               AND ws.sources -> 0 ->> 'key' = primary_key THEN
                new_nodes := new_nodes || jsonb_build_array(node);
                CONTINUE;
            END IF;

            -- the step's agent and push setting: the step, else the template lineage, else the defaults
            template_key := node -> 'task' ->> 'templateKey';
            agent_key := nullif(btrim(coalesce(node -> 'task' -> 'overrides' ->> 'agentKey', '')), '');
            IF agent_key IS NULL AND cfg ->> 'agent' IN ('CLAUDE_CODE', 'OPENCODE') THEN
                agent_key := CASE cfg ->> 'agent' WHEN 'CLAUDE_CODE' THEN 'claude-code' ELSE 'opencode' END;
            END IF;
            lineage_push := NULL;
            lineage_prep := NULL;
            IF has_library THEN
                cur_key := template_key;
                cur_id := NULL;
                depth := 0;
                WHILE cur_key IS NOT NULL AND depth < 8 LOOP
                    EXECUTE 'SELECT id, agent_key, extends_key, config FROM task_template
                              WHERE key = $1 AND ($2::uuid IS NULL OR id <> $2) AND (project_id IS NULL OR project_id = $3)
                              ORDER BY (project_id IS NULL), created_at LIMIT 1'
                       INTO t USING cur_key, cur_id, wf.project_id;
                    EXIT WHEN t.id IS NULL;
                    IF agent_key IS NULL THEN agent_key := t.agent_key; END IF;
                    IF lineage_push IS NULL AND t.config ? 'push' THEN lineage_push := t.config -> 'push'; END IF;
                    IF lineage_prep IS NULL AND t.config ? 'prepareOnly' THEN lineage_prep := t.config -> 'prepareOnly'; END IF;
                    cur_id := t.id;
                    cur_key := t.extends_key;
                    depth := depth + 1;
                END LOOP;
                harness := NULL;
                IF agent_key IS NOT NULL THEN
                    EXECUTE 'SELECT harness FROM agent WHERE key = $1 AND (project_id IS NULL OR project_id = $2)
                              ORDER BY (project_id IS NULL) LIMIT 1'
                       INTO harness USING agent_key, wf.project_id;
                END IF;
                -- a model answer ignored its workspace: nothing to carry over
                IF agent_key IS NULL OR agent_key = 'model-answer' OR harness IS NULL THEN
                    new_nodes := new_nodes || jsonb_build_array(node);
                    CONTINUE;
                END IF;
            END IF;
            pushes := lower(coalesce(cfg ->> 'push', lineage_push #>> '{}', 'true')) <> 'false'
                  AND lower(coalesce(cfg ->> 'prepareOnly', lineage_prep #>> '{}', 'false')) <> 'true';

            -- ADR 0044 effective config: the project's primary among the sources, else the first project source, pushes
            SELECT s ->> 'key' INTO own_primary
              FROM jsonb_array_elements(ws.sources) WITH ORDINALITY AS x(s, n)
             WHERE coalesce((s ->> 'library')::boolean, false) = false
             ORDER BY (s ->> 'key' = primary_key) DESC NULLS LAST, n
             LIMIT 1;
            entries := '[]'::jsonb;
            FOR src IN SELECT value FROM jsonb_array_elements(ws.sources) LOOP
                IF coalesce((src ->> 'library')::boolean, false) THEN
                    entries := entries || jsonb_build_array(jsonb_build_object('key', src ->> 'key', 'access', 'READ', 'library', true));
                ELSE
                    entries := entries || jsonb_build_array(jsonb_build_object(
                        'key', src ->> 'key',
                        'access', CASE WHEN pushes AND src ->> 'key' = own_primary THEN 'PUSH_MR' ELSE 'READ' END));
                END IF;
            END LOOP;
            node := jsonb_set(node, '{task,overrides,config,repositories}', entries, true);
            new_nodes := new_nodes || jsonb_build_array(node);
            changed := true;
        END LOOP;

        IF changed THEN
            UPDATE workflow_definition SET nodes = new_nodes WHERE id = wf.id;
        END IF;
    END LOOP;
END $$;

DROP TABLE IF EXISTS project_workspace;
