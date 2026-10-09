-- ADR 0030: least-privilege connections. Project credentials are managed by owners only:
-- `project.connections.manage` is added to the stored built-in owner role (built-in roles are never overwritten by
-- seeding). Maintainers deliberately do not get it. `admin.connections.manage` reaches the stored platform-admin
-- role through the startup seeding (it adds every contributed platform permission to platform-admin).
-- The change is audited as ROLE_UPDATE by the system and the access version is bumped so cached effective
-- permissions are recomputed. On a fresh database the roles do not exist yet and nothing happens here.

WITH added AS (
    UPDATE identity_role
       SET permissions = array_append(permissions, 'project.connections.manage')
     WHERE built_in
       AND key = 'owner'
       AND NOT ('project.connections.manage' = ANY (permissions))
    RETURNING key
), audited AS (
    INSERT INTO audit_log (event_time, id, actor_kind, actor_id, actor_name, operation, entity_type, entity_id, project_id,
                           result, details, correlation_id, source_ip, user_agent)
    SELECT date_trunc('milliseconds', now()), gen_random_uuid(), 'SYSTEM', NULL, 'system', 'ROLE_UPDATE', 'ROLE', key, NULL,
           'SUCCESS', jsonb_build_object('removed', '[]'::jsonb, 'added', jsonb_build_array('project.connections.manage'),
                                         'reason', 'ADR 0030 least-privilege connections (migration V408)'),
           NULL, NULL, NULL
      FROM added
    RETURNING 1
)
UPDATE identity_access_version
   SET version = version + 1
 WHERE id = 1
   AND EXISTS (SELECT 1 FROM audited);
