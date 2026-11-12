-- ADR 0063: delivering changes is an action; sources belong to the workflow; no Spec Kit.
--
--   * Workflows of every project (workflow_definition), library versions (library_workflow_version) and the definition
--     snapshots of runs that have not finished (workflow_run) are rewritten first, while task_template still holds the
--     git keys (the same lineage walk as V307/V308: nearest template first; a project template shadows the global one;
--     library versions are seen from their source project). For every TASK node:
--       - a step whose effective push is true (its config `push`, else the nearest template of its lineage that sets
--         `push`; built-ins implement-opencode, implement-claude-code and implement-task-list had push true) and that
--         does not only prepare the run directory becomes the step plus an ACTION node `<step id>-deliver` (DELIVER_CHANGES)
--         to the right of it, carrying the step's git keys (branchTemplate, commitSubjectTemplate, mergeRequest,
--         mergeRequestTitleTemplate, mergeRequestDescriptionTemplate, mergeRequestDraft; each from the step, else from the
--         nearest template that sets it; the step's repositories with PUSH_MR as `repositories`). The step's `success`
--         edges start at the delivery's `success`, the step's `success` goes to the delivery and the delivery's
--         `failure` goes where the step's `failure` went. Steps that pushed only by default (no `push` key anywhere, e.g.
--         Spec Kit phases) get no delivery: their work stays in the run directory for a later delivery;
--       - the step's `repositories` join the workflow's `sources` ({key, library}; union, first seen first); when a
--         project repository is listed and a coding step relied on the default, the project's primary repository joins
--         too (library versions keep library sources only);
--       - the step's `setupCommand` is appended to the workflow's setup command (" && ") unless it is already a part of it;
--       - a step of spec-kit-specify / spec-kit-plan / spec-kit-tasks, or with a `phase` SPECIFY / PLAN / TASKS, names the
--         replacement template write-spec / write-plan / write-task-list;
--       - the keys push, phase, repositories, setupCommand and the git keys leave the step's config (collectArtifacts stays:
--         it is read as extra output files).
--   * task_template: `collectArtifacts` becomes the column output_files; a template with a `phase` or `push: true` gets
--     `codingAgent: true` (the work still needs a coding agent); the keys push, phase, repositories, setupCommand and the
--     git keys leave the config. Built-in templates nobody edited are replaced by the seed at startup anyway.
--
-- Tables of other modules are touched only when they exist (Flyway runs the modules' migrations out of order; V122 of
-- the projects module, which adds the `sources` columns, sorts before this one).

CREATE FUNCTION adr0063_bool(value jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE jsonb_typeof(value)
               WHEN 'boolean' THEN (value #>> '{}')::boolean
               WHEN 'string' THEN CASE lower(btrim(value #>> '{}')) WHEN 'true' THEN true WHEN 'false' THEN false ELSE NULL END
               ELSE NULL END
$fn$;

-- The configs of the lineage of template_key seen from project, nearest first.
CREATE FUNCTION adr0063_lineage(template_key text, project uuid) RETURNS jsonb LANGUAGE plpgsql AS $fn$
DECLARE
    configs  jsonb := '[]'::jsonb;
    cur_key  text := template_key;
    cur_id   uuid;
    depth    int := 0;
    t        record;
BEGIN
    WHILE cur_key IS NOT NULL AND depth < 8 LOOP
        SELECT tt.id, tt.extends_key, tt.config INTO t
          FROM task_template tt
         WHERE tt.key = cur_key
           AND (cur_id IS NULL OR tt.id <> cur_id)
           AND (tt.project_id IS NULL OR tt.project_id = project)
         ORDER BY (tt.project_id IS NULL), tt.created_at
         LIMIT 1;
        EXIT WHEN NOT FOUND;
        configs := configs || jsonb_build_array(CASE WHEN jsonb_typeof(t.config) = 'object' THEN t.config ELSE '{}'::jsonb END);
        cur_id := t.id;
        cur_key := t.extends_key;
        depth := depth + 1;
    END LOOP;
    RETURN configs;
END $fn$;

-- The value of key in config, else in the nearest config of lineage that sets it (not null); NULL when none does.
CREATE FUNCTION adr0063_effective(config jsonb, lineage jsonb, key text) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE WHEN config ? key AND jsonb_typeof(config -> key) <> 'null' THEN config -> key
                ELSE (SELECT c -> key FROM jsonb_array_elements(lineage) WITH ORDINALITY AS l(c, n)
                       WHERE c ? key AND jsonb_typeof(c -> key) <> 'null' ORDER BY n LIMIT 1) END
$fn$;

-- An id not in taken: candidate, else candidate shortened with -2, -3, ...
CREATE FUNCTION adr0063_unique(candidate text, taken text[]) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $fn$
DECLARE
    id text := candidate;
    n  int := 2;
BEGIN
    WHILE id = ANY (taken) LOOP
        id := left(candidate, 37) || '-' || n;
        n := n + 1;
    END LOOP;
    RETURN id;
END $fn$;

-- One workflow ({nodes, edges, sources, setupCommand}) rewritten; library_only keeps library sources only.
CREATE FUNCTION adr0063_workflow(def jsonb, project uuid, library_only boolean) RETURNS jsonb LANGUAGE plpgsql AS $fn$
DECLARE
    git_keys      text[] := ARRAY['branchTemplate', 'commitSubjectTemplate', 'mergeRequest', 'mergeRequestTitleTemplate',
                                  'mergeRequestDescriptionTemplate', 'mergeRequestDraft'];
    step_keys     text[] := ARRAY['push', 'phase', 'repositories', 'setupCommand', 'branchTemplate', 'commitSubjectTemplate',
                                  'mergeRequest', 'mergeRequestTitleTemplate', 'mergeRequestDescriptionTemplate', 'mergeRequestDraft'];
    nodes         jsonb := def -> 'nodes';
    edges         jsonb := CASE WHEN jsonb_typeof(def -> 'edges') = 'array' THEN def -> 'edges' ELSE '[]'::jsonb END;
    sources       jsonb := CASE WHEN jsonb_typeof(def -> 'sources') = 'array' THEN def -> 'sources' ELSE '[]'::jsonb END;
    setup         text := nullif(btrim(coalesce(def ->> 'setupCommand', '')), '');
    result        jsonb := '[]'::jsonb;
    deliveries    jsonb := '[]'::jsonb;
    node          jsonb;
    ov            jsonb;
    cfg           jsonb;
    lineage       jsonb;
    template_key  text;
    new_key       text;
    phase         text;
    pushes        boolean;
    prepares      boolean;
    coding        boolean;
    explicit      boolean;
    gcfg          jsonb;
    push_keys     jsonb;
    entry         jsonb;
    entry_key     text;
    entry_lib     boolean;
    step_setup    text;
    k             text;
    v             jsonb;
    ids           text[];
    edge_ids      text[];
    deliver_id    text;
    d             jsonb;
    fail_target   text;
    primary_key   text;
    any_explicit  boolean := false;
    default_coder boolean := false;
BEGIN
    IF nodes IS NULL OR jsonb_typeof(nodes) <> 'array' THEN
        RETURN def;
    END IF;
    SELECT coalesce(array_agg(n ->> 'id'), ARRAY[]::text[]) INTO ids FROM jsonb_array_elements(nodes) n;
    SELECT coalesce(array_agg(e ->> 'id'), ARRAY[]::text[]) INTO edge_ids FROM jsonb_array_elements(edges) e;

    FOR node IN SELECT value FROM jsonb_array_elements(nodes) LOOP
        template_key := nullif(btrim(coalesce(node -> 'task' ->> 'templateKey', '')), '');
        IF node ->> 'kind' IS DISTINCT FROM 'TASK' OR template_key IS NULL THEN
            result := result || jsonb_build_array(node);
            CONTINUE;
        END IF;
        ov := CASE WHEN jsonb_typeof(node -> 'task' -> 'overrides') = 'object' THEN node -> 'task' -> 'overrides' ELSE '{}'::jsonb END;
        cfg := CASE WHEN jsonb_typeof(ov -> 'config') = 'object' THEN ov -> 'config' ELSE '{}'::jsonb END;
        lineage := adr0063_lineage(template_key, project);

        -- Spec Kit: the replacement template
        phase := upper(nullif(btrim(coalesce(cfg ->> 'phase', '')), ''));
        new_key := CASE template_key
                       WHEN 'spec-kit-specify' THEN 'write-spec'
                       WHEN 'spec-kit-plan' THEN 'write-plan'
                       WHEN 'spec-kit-tasks' THEN 'write-task-list'
                       ELSE CASE phase WHEN 'SPECIFY' THEN 'write-spec' WHEN 'PLAN' THEN 'write-plan' WHEN 'TASKS' THEN 'write-task-list'
                                       ELSE template_key END END;

        -- the effective push of the step
        prepares := coalesce(adr0063_bool(adr0063_effective(cfg, lineage, 'prepareOnly')), false);
        pushes := coalesce(adr0063_bool(adr0063_effective(cfg, lineage, 'push')), false) AND NOT prepares;
        coding := pushes OR prepares
            OR coalesce(adr0063_bool(adr0063_effective(cfg, lineage, 'codingAgent')), false)
            OR nullif(btrim(coalesce(adr0063_effective(cfg, lineage, 'phase') #>> '{}', '')), '') IS NOT NULL
            OR EXISTS (SELECT 1 FROM agent a WHERE a.key = ov ->> 'agentKey' AND a.harness IS NOT NULL
                          AND (a.project_id IS NULL OR a.project_id = project));

        -- the step's repositories join the sources
        explicit := jsonb_typeof(cfg -> 'repositories') = 'array' AND jsonb_array_length(cfg -> 'repositories') > 0;
        push_keys := '[]'::jsonb;
        IF explicit THEN
            any_explicit := true;
            FOR entry IN SELECT value FROM jsonb_array_elements(cfg -> 'repositories') LOOP
                IF jsonb_typeof(entry) = 'string' THEN
                    entry_key := nullif(btrim(entry #>> '{}'), '');
                    entry_lib := false;
                ELSIF jsonb_typeof(entry) = 'object' THEN
                    entry_key := nullif(btrim(coalesce(entry ->> 'key', '')), '');
                    entry_lib := coalesce(adr0063_bool(entry -> 'library'), false);
                    IF entry_key IS NOT NULL AND NOT entry_lib AND upper(coalesce(entry ->> 'access', '')) = 'PUSH_MR'
                       AND NOT push_keys @> jsonb_build_array(entry_key) THEN
                        push_keys := push_keys || jsonb_build_array(entry_key);
                    END IF;
                ELSE
                    entry_key := NULL;
                END IF;
                IF entry_key IS NOT NULL AND (entry_lib OR NOT library_only)
                   AND NOT sources @> jsonb_build_array(jsonb_build_object('key', entry_key, 'library', entry_lib)) THEN
                    sources := sources || jsonb_build_array(jsonb_build_object('key', entry_key, 'library', entry_lib));
                END IF;
            END LOOP;
        ELSIF coding THEN
            default_coder := true;
        END IF;

        -- the step's setup command joins the workflow's
        step_setup := nullif(btrim(coalesce(cfg ->> 'setupCommand', '')), '');
        IF step_setup IS NOT NULL AND (setup IS NULL OR NOT (step_setup = ANY (string_to_array(setup, ' && ')))) THEN
            setup := concat_ws(' && ', setup, step_setup);
        END IF;

        -- the git settings of the delivery, then the step without them
        gcfg := '{}'::jsonb;
        FOREACH k IN ARRAY git_keys LOOP
            v := adr0063_effective(cfg, lineage, k);
            IF v IS NOT NULL THEN
                gcfg := gcfg || jsonb_build_object(k, v);
            END IF;
        END LOOP;
        IF nullif(btrim(coalesce(adr0063_effective(cfg, lineage, 'phase') #>> '{}', '')), '') IS NOT NULL AND NOT gcfg ? 'mergeRequest' THEN
            gcfg := gcfg || jsonb_build_object('mergeRequest', false);
        END IF;
        IF jsonb_array_length(push_keys) > 0 THEN
            gcfg := gcfg || jsonb_build_object('repositories', push_keys);
        END IF;
        IF cfg ?| step_keys THEN
            cfg := cfg - step_keys;
            ov := jsonb_set(ov, '{config}', cfg, true);
            node := jsonb_set(node, '{task,overrides}', ov, true);
        END IF;
        IF new_key <> template_key THEN
            node := jsonb_set(node, '{task,templateKey}', to_jsonb(new_key), true);
        END IF;
        result := result || jsonb_build_array(node);

        IF pushes THEN
            deliver_id := adr0063_unique(rtrim(left(node ->> 'id', 32), '-') || '-deliver', ids);
            ids := ids || deliver_id;
            result := result || jsonb_build_array(jsonb_build_object(
                'id', deliver_id,
                'kind', 'ACTION',
                'name', 'Deliver changes',
                'position', jsonb_build_object(
                    'x', coalesce((node -> 'position' ->> 'x')::numeric, 0) + 168,
                    'y', coalesce((node -> 'position' ->> 'y')::numeric, 0) + 160),
                'maxVisits', coalesce(node -> 'maxVisits', '3'::jsonb),
                'action', jsonb_build_object('type', 'DELIVER_CHANGES', 'config', gcfg)));
            deliveries := deliveries || jsonb_build_array(jsonb_build_object('step', node ->> 'id', 'deliver', deliver_id));
        END IF;
    END LOOP;

    -- the edges: the step's success leaves from the delivery; the delivery fails where the step failed
    FOR d IN SELECT value FROM jsonb_array_elements(deliveries) LOOP
        SELECT e ->> 'to' INTO fail_target FROM jsonb_array_elements(edges) e
         WHERE e ->> 'from' = d ->> 'step' AND lower(e ->> 'fromPort') = 'failure' LIMIT 1;
        SELECT coalesce(jsonb_agg(CASE WHEN e ->> 'from' = d ->> 'step' AND lower(e ->> 'fromPort') = 'success'
                                       THEN jsonb_set(e, '{from}', to_jsonb(d ->> 'deliver')) ELSE e END ORDER BY n), '[]'::jsonb)
          INTO edges FROM jsonb_array_elements(edges) WITH ORDINALITY AS x(e, n);
        k := adr0063_unique((d ->> 'deliver') || '-in', edge_ids);
        edge_ids := edge_ids || k;
        edges := edges || jsonb_build_array(jsonb_build_object('id', k, 'from', d ->> 'step', 'fromPort', 'success', 'to', d ->> 'deliver'));
        IF fail_target IS NOT NULL THEN
            k := adr0063_unique((d ->> 'deliver') || '-failure', edge_ids);
            edge_ids := edge_ids || k;
            edges := edges || jsonb_build_array(jsonb_build_object('id', k, 'from', d ->> 'deliver', 'fromPort', 'failure', 'to', fail_target));
        END IF;
    END LOOP;

    -- a listed project repository would replace the default primary: keep it for the steps that relied on it
    IF any_explicit AND default_coder AND NOT library_only AND to_regclass('project') IS NOT NULL
       AND EXISTS (SELECT 1 FROM jsonb_array_elements(sources) s WHERE NOT coalesce((s ->> 'library')::boolean, false)) THEN
        SELECT r ->> 'key' INTO primary_key FROM project p, jsonb_array_elements(p.repositories) r
         WHERE p.id = project AND coalesce((r ->> 'primary')::boolean, false) LIMIT 1;
        IF primary_key IS NOT NULL AND NOT sources @> jsonb_build_array(jsonb_build_object('key', primary_key, 'library', false)) THEN
            sources := jsonb_build_array(jsonb_build_object('key', primary_key, 'library', false)) || sources;
        END IF;
    END IF;

    RETURN jsonb_build_object('nodes', result, 'edges', edges, 'sources', sources, 'setupCommand', setup);
END $fn$;

DO $$
DECLARE
    has_sources boolean := EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'workflow_definition' AND column_name = 'sources');
    has_lib_sources boolean := EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'library_workflow_version' AND column_name = 'sources');
    r record;
    w jsonb;
BEGIN
    IF to_regclass('workflow_definition') IS NOT NULL AND has_sources THEN
        FOR r IN SELECT id, project_id, nodes, edges, sources, setup_command FROM workflow_definition LOOP
            w := adr0063_workflow(jsonb_build_object('nodes', r.nodes, 'edges', r.edges, 'sources', r.sources, 'setupCommand', r.setup_command),
                                  r.project_id, false);
            IF w -> 'nodes' IS DISTINCT FROM r.nodes OR w -> 'edges' IS DISTINCT FROM r.edges OR w -> 'sources' IS DISTINCT FROM r.sources
               OR (w ->> 'setupCommand') IS DISTINCT FROM r.setup_command THEN
                UPDATE workflow_definition
                   SET nodes = w -> 'nodes', edges = w -> 'edges', sources = w -> 'sources', setup_command = w ->> 'setupCommand'
                 WHERE id = r.id;
            END IF;
        END LOOP;
    END IF;

    IF to_regclass('library_workflow_version') IS NOT NULL AND has_lib_sources THEN
        FOR r IN SELECT library_workflow_id, version, source_project_id, nodes, edges, sources, setup_command FROM library_workflow_version LOOP
            w := adr0063_workflow(jsonb_build_object('nodes', r.nodes, 'edges', r.edges, 'sources', r.sources, 'setupCommand', r.setup_command),
                                  r.source_project_id, true);
            IF w -> 'nodes' IS DISTINCT FROM r.nodes OR w -> 'edges' IS DISTINCT FROM r.edges OR w -> 'sources' IS DISTINCT FROM r.sources
               OR (w ->> 'setupCommand') IS DISTINCT FROM r.setup_command THEN
                UPDATE library_workflow_version
                   SET nodes = w -> 'nodes', edges = w -> 'edges', sources = w -> 'sources', setup_command = w ->> 'setupCommand'
                 WHERE library_workflow_id = r.library_workflow_id AND version = r.version;
            END IF;
        END LOOP;
    END IF;

    IF to_regclass('workflow_run') IS NOT NULL THEN
        FOR r IN SELECT id, project_id, definition_snapshot AS s FROM workflow_run
                  WHERE status IN ('PENDING', 'RUNNING', 'WAITING') AND jsonb_typeof(definition_snapshot -> 'nodes') = 'array' LOOP
            w := adr0063_workflow(r.s, r.project_id, false);
            IF w -> 'nodes' IS DISTINCT FROM r.s -> 'nodes' OR w -> 'edges' IS DISTINCT FROM r.s -> 'edges'
               OR w -> 'sources' IS DISTINCT FROM coalesce(r.s -> 'sources', '[]'::jsonb)
               OR (w ->> 'setupCommand') IS DISTINCT FROM (r.s ->> 'setupCommand') THEN
                UPDATE workflow_run
                   SET definition_snapshot = r.s || jsonb_build_object('nodes', w -> 'nodes', 'edges', w -> 'edges', 'sources', w -> 'sources',
                                                                       'setupCommand', w -> 'setupCommand')
                 WHERE id = r.id;
            END IF;
        END LOOP;
    END IF;
END $$;

-- templates: output files, no git keys, no Spec Kit phase
ALTER TABLE task_template ADD COLUMN output_files jsonb NOT NULL DEFAULT '[]'::jsonb;

UPDATE task_template tt
   SET output_files = (
       SELECT coalesce(jsonb_agg(f ORDER BY n), '[]'::jsonb)
         FROM (
             SELECT DISTINCT ON (f) f, n
               FROM (
                   SELECT btrim(x.v) AS f, x.n
                     FROM unnest(CASE jsonb_typeof(tt.config -> 'collectArtifacts')
                                     WHEN 'array' THEN ARRAY(SELECT jsonb_array_elements_text(tt.config -> 'collectArtifacts'))
                                     WHEN 'string' THEN string_to_array(tt.config ->> 'collectArtifacts', ',')
                                     ELSE ARRAY[]::text[] END) WITH ORDINALITY AS x(v, n)
               ) raw
              WHERE f <> ''
              ORDER BY f, n
         ) files
   ),
       config = tt.config - 'collectArtifacts'
 WHERE jsonb_typeof(tt.config) = 'object' AND tt.config ? 'collectArtifacts';

UPDATE task_template tt
   SET config = (tt.config - ARRAY['push', 'phase', 'repositories', 'setupCommand', 'branchTemplate', 'commitSubjectTemplate', 'mergeRequest',
                                   'mergeRequestTitleTemplate', 'mergeRequestDescriptionTemplate', 'mergeRequestDraft'])
                || CASE WHEN NOT tt.config ? 'codingAgent'
                         AND (nullif(btrim(coalesce(tt.config ->> 'phase', '')), '') IS NOT NULL OR coalesce(adr0063_bool(tt.config -> 'push'), false))
                        THEN jsonb_build_object('codingAgent', true) ELSE '{}'::jsonb END
 WHERE jsonb_typeof(tt.config) = 'object'
   AND tt.config ?| ARRAY['push', 'phase', 'repositories', 'setupCommand', 'branchTemplate', 'commitSubjectTemplate', 'mergeRequest',
                          'mergeRequestTitleTemplate', 'mergeRequestDescriptionTemplate', 'mergeRequestDraft'];

DROP FUNCTION adr0063_workflow(jsonb, uuid, boolean);
DROP FUNCTION adr0063_unique(text, text[]);
DROP FUNCTION adr0063_effective(jsonb, jsonb, text);
DROP FUNCTION adr0063_lineage(text, uuid);
DROP FUNCTION adr0063_bool(jsonb);
