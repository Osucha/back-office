-- ADR 0038: every user may create private projects of their own. `projects.create.personal` is added to the stored
-- built-in platform roles that hold the member permissions (member, project-manager, evaluator, library-curator);
-- built-in roles are never overwritten by seeding. platform-admin receives it through the startup seeding (every
-- contributed platform permission). Audited as ROLE_UPDATE by the system; the access version is bumped so cached
-- effective permissions are recomputed. On a fresh database the roles do not exist yet and nothing happens here.

WITH added AS (
    UPDATE identity_role
       SET permissions = array_append(permissions, 'projects.create.personal')
     WHERE built_in
       AND key IN ('member', 'project-manager', 'evaluator', 'library-curator')
       AND NOT ('projects.create.personal' = ANY (permissions))
    RETURNING key
), audited AS (
    INSERT INTO audit_log (event_time, id, actor_kind, actor_id, actor_name, operation, entity_type, entity_id, project_id,
                           result, details, correlation_id, source_ip, user_agent)
    SELECT date_trunc('milliseconds', now()), gen_random_uuid(), 'SYSTEM', NULL, 'system', 'ROLE_UPDATE', 'ROLE', key, NULL,
           'SUCCESS', jsonb_build_object('removed', '[]'::jsonb, 'added', jsonb_build_array('projects.create.personal'),
                                         'reason', 'ADR 0038 private projects (migration V410)'),
           NULL, NULL, NULL
      FROM added
    RETURNING 1
)
UPDATE identity_access_version
   SET version = version + 1
 WHERE id = 1
   AND EXISTS (SELECT 1 FROM audited);
