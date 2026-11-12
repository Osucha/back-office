-- ADR 0064: agents use connectors.
--
--   * agent.connectors (connector keys) replaces agent.mcp_servers: every MCP server of an agent becomes a PLATFORM
--     connector (kind MCP_HTTP, auth NONE, key = the server name; a platform connector with that key and another URL
--     gets the next free key-2, key-3, ...; one with the same URL is reused) whose key the agent lists.
--   * the step setting agentTools (ADR 0051 step 4) goes: every project whose steps used gitlab-read (workflows of the
--     project, library versions published from it, snapshots of its runs that have not finished) gets the project
--     connector gitlab-read (GITLAB_READ, the primary repository) unless it has a connector with that key; agentTools
--     leaves the step configs (and the template configs). The built-in coding agents list gitlab-read (seed).
--
-- The connector table belongs to the projects module (V123, which sorts before this one); tables of other modules are
-- touched only when they exist (Flyway runs the modules' migrations out of order).

ALTER TABLE agent ADD COLUMN connectors jsonb NOT NULL DEFAULT '[]'::jsonb;

DO $mig$
DECLARE
    has_connector boolean := to_regclass('connector') IS NOT NULL;
    system_actor  jsonb := '{"kind":"SYSTEM","userId":null,"displayName":"system"}'::jsonb;
    a             record;
    server        jsonb;
    server_name   text;
    server_url    text;
    candidate     text;
    n             int;
    keys          jsonb;
BEGIN
    FOR a IN SELECT id, name, mcp_servers FROM agent
              WHERE jsonb_typeof(mcp_servers) = 'array' AND jsonb_array_length(mcp_servers) > 0 ORDER BY created_at, id LOOP
        keys := '[]'::jsonb;
        FOR server IN SELECT value FROM jsonb_array_elements(a.mcp_servers) LOOP
            server_name := server ->> 'name';
            server_url := server ->> 'url';
            CONTINUE WHEN server_name IS NULL OR server_url IS NULL OR server_name !~ '^[a-z][a-z0-9-]{0,39}$';
            candidate := server_name;
            IF has_connector THEN
                n := 2;
                -- reuse a platform MCP connector with the same URL, else find a free key
                WHILE EXISTS (SELECT 1 FROM connector c WHERE c.project_id IS NULL AND c.key = candidate
                                 AND NOT (c.kind = 'MCP_HTTP' AND c.url = server_url AND c.auth = 'NONE')) LOOP
                    candidate := left(server_name, 36) || '-' || n;
                    n := n + 1;
                END LOOP;
                IF NOT EXISTS (SELECT 1 FROM connector c WHERE c.project_id IS NULL AND c.key = candidate) THEN
                    INSERT INTO connector (id, key, name, description, kind, project_id, url, auth, created_at, created_by, updated_at, updated_by)
                    VALUES (gen_random_uuid(), candidate, server_name,
                            'MCP server of agent ' || a.name || ' (moved from the agent, ADR 0064)', 'MCP_HTTP', NULL, server_url, 'NONE',
                            now(), system_actor, now(), system_actor);
                END IF;
            END IF;
            IF NOT keys ? candidate THEN
                keys := keys || to_jsonb(candidate);
            END IF;
        END LOOP;
        UPDATE agent SET connectors = keys WHERE id = a.id;
    END LOOP;
END
$mig$;

ALTER TABLE agent DROP COLUMN mcp_servers;

-- true when a step config lists the agent tool gitlab-read (an array, or legacy text)
CREATE FUNCTION adr0064_uses_gitlab_read(config jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE jsonb_typeof(config -> 'agentTools')
               WHEN 'array' THEN EXISTS (SELECT 1 FROM jsonb_array_elements_text(config -> 'agentTools') t WHERE lower(btrim(t)) = 'gitlab-read')
               WHEN 'string' THEN 'gitlab-read' = ANY (string_to_array(replace(lower(config ->> 'agentTools'), ' ', ''), ','))
               ELSE false END
$fn$;

-- the nodes with agentTools removed from every step config
CREATE FUNCTION adr0064_strip(nodes jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $fn$
    SELECT COALESCE(jsonb_agg(
               CASE WHEN jsonb_typeof(n #> '{task,overrides,config}') = 'object' AND (n #> '{task,overrides,config}') ? 'agentTools'
                    THEN jsonb_set(n, '{task,overrides,config}', (n #> '{task,overrides,config}') - 'agentTools')
                    ELSE n END
               ORDER BY i), '[]'::jsonb)
      FROM jsonb_array_elements(nodes) WITH ORDINALITY AS e(n, i)
$fn$;

-- true when a step of nodes uses gitlab-read
CREATE FUNCTION adr0064_nodes_use(nodes jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE WHEN jsonb_typeof(nodes) = 'array' THEN EXISTS (
        SELECT 1 FROM jsonb_array_elements(nodes) n
         WHERE jsonb_typeof(n #> '{task,overrides,config}') = 'object' AND adr0064_uses_gitlab_read(n #> '{task,overrides,config}')) ELSE false END
$fn$;

-- true when a step of nodes has the key agentTools at all
CREATE FUNCTION adr0064_nodes_have(nodes jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $fn$
    SELECT CASE WHEN jsonb_typeof(nodes) = 'array' THEN EXISTS (
        SELECT 1 FROM jsonb_array_elements(nodes) n
         WHERE jsonb_typeof(n #> '{task,overrides,config}') = 'object' AND (n #> '{task,overrides,config}') ? 'agentTools') ELSE false END
$fn$;

DO $mig$
DECLARE
    system_actor jsonb := '{"kind":"SYSTEM","userId":null,"displayName":"system"}'::jsonb;
    projects     uuid[] := ARRAY[]::uuid[];
    p            uuid;
BEGIN
    IF to_regclass('workflow_definition') IS NOT NULL THEN
        projects := projects || ARRAY(SELECT DISTINCT project_id FROM workflow_definition WHERE adr0064_nodes_use(nodes));
        UPDATE workflow_definition SET nodes = adr0064_strip(nodes) WHERE adr0064_nodes_have(nodes);
    END IF;
    IF to_regclass('library_workflow_version') IS NOT NULL THEN
        projects := projects || ARRAY(SELECT DISTINCT source_project_id FROM library_workflow_version
                                       WHERE source_project_id IS NOT NULL AND adr0064_nodes_use(nodes));
        UPDATE library_workflow_version SET nodes = adr0064_strip(nodes) WHERE adr0064_nodes_have(nodes);
    END IF;
    IF to_regclass('workflow_run') IS NOT NULL THEN
        projects := projects || ARRAY(SELECT DISTINCT project_id FROM workflow_run
                                       WHERE status IN ('PENDING', 'RUNNING', 'WAITING') AND adr0064_nodes_use(definition_snapshot -> 'nodes'));
        UPDATE workflow_run SET definition_snapshot = jsonb_set(definition_snapshot, '{nodes}', adr0064_strip(definition_snapshot -> 'nodes'))
         WHERE status IN ('PENDING', 'RUNNING', 'WAITING') AND adr0064_nodes_have(definition_snapshot -> 'nodes');
    END IF;
    IF to_regclass('connector') IS NOT NULL AND to_regclass('project') IS NOT NULL THEN
        FOR p IN SELECT DISTINCT unnest(projects) LOOP
            CONTINUE WHEN NOT EXISTS (SELECT 1 FROM project WHERE id = p);
            CONTINUE WHEN EXISTS (SELECT 1 FROM connector WHERE project_id = p AND key = 'gitlab-read');
            INSERT INTO connector (id, key, name, description, kind, project_id, auth, created_at, created_by, updated_at, updated_by)
            VALUES (gen_random_uuid(), 'gitlab-read', 'GitLab (read only)',
                    'glab with the project''s verified read-only CI_READ token of the primary repository: pipelines, job logs, merge requests (the former agent tool, ADR 0064)',
                    'GITLAB_READ', p, 'NONE', now(), system_actor, now(), system_actor);
        END LOOP;
    END IF;
    -- templates never offered agentTools, but a hand-edited config may hold it
    UPDATE task_template SET config = config - 'agentTools' WHERE jsonb_typeof(config) = 'object' AND config ? 'agentTools';
END
$mig$;

DROP FUNCTION adr0064_nodes_have(jsonb);
DROP FUNCTION adr0064_nodes_use(jsonb);
DROP FUNCTION adr0064_strip(jsonb);
DROP FUNCTION adr0064_uses_gitlab_read(jsonb);
