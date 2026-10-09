-- ADR 0028: projects are created by project managers, not by every member; owners and maintainers assign
-- who decides an open human gate (run.assign).
--
-- Built-in roles are stored and never overwritten by seeding, so existing installs are changed here, once:
--   1. `projects.create` is removed from the stored built-in member, evaluator and library-curator roles
--      (in code they are member + one permission); platform-admin keeps it.
--   2. `run.assign` is added to the stored built-in owner and maintainer roles.
-- The new built-in role `project-manager` is created by the startup seeding (it creates missing built-ins and
-- audits ROLE_CREATE). On a fresh database the roles do not exist yet and nothing happens here.
-- Every changed role is audited as ROLE_UPDATE by the system and the access version is bumped so that cached
-- effective permissions are recomputed. Later administrator edits are kept (Flyway runs this once).

WITH removed AS (
    UPDATE identity_role
       SET permissions = array_remove(permissions, 'projects.create')
     WHERE built_in
       AND key IN ('member', 'evaluator', 'library-curator')
       AND 'projects.create' = ANY (permissions)
    RETURNING key
), added AS (
    UPDATE identity_role
       SET permissions = array_append(permissions, 'run.assign')
     WHERE built_in
       AND key IN ('owner', 'maintainer')
       AND NOT ('run.assign' = ANY (permissions))
    RETURNING key
), changes AS (
    SELECT key, jsonb_build_array('projects.create') AS removed, '[]'::jsonb AS added FROM removed
    UNION ALL
    SELECT key, '[]'::jsonb, jsonb_build_array('run.assign') FROM added
), audited AS (
    INSERT INTO audit_log (event_time, id, actor_kind, actor_id, actor_name, operation, entity_type, entity_id, project_id,
                           result, details, correlation_id, source_ip, user_agent)
    SELECT date_trunc('milliseconds', now()), gen_random_uuid(), 'SYSTEM', NULL, 'system', 'ROLE_UPDATE', 'ROLE', key, NULL,
           'SUCCESS', jsonb_build_object('removed', removed, 'added', added, 'reason', 'ADR 0028 manager-led projects (migration V406)'),
           NULL, NULL, NULL
      FROM changes
    RETURNING 1
)
UPDATE identity_access_version
   SET version = version + 1
 WHERE id = 1
   AND EXISTS (SELECT 1 FROM audited);
