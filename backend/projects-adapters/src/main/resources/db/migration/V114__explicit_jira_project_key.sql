-- The Jira project key is no longer derived from the Back Office project key (the project key is Back Office's own
-- short code and may be renamed). Projects that use Jira but relied on that fallback get it written explicitly, so
-- their Jira steps and intake watcher keep working: a JQL filter, an intake watcher or a Jira system connection.

UPDATE project p
   SET jira_project_key = p.key
 WHERE (p.jira_project_key IS NULL OR btrim(p.jira_project_key) = '')
   AND (NULLIF(btrim(p.jira_jql), '') IS NOT NULL
        OR p.jira_system_connection_id IS NOT NULL
        OR EXISTS (SELECT 1 FROM jira_intake i WHERE i.project_id = p.id));
