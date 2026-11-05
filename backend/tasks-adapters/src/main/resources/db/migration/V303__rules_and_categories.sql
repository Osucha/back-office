-- ADR 0055: rules (always in the agent's context) next to skills (loaded on demand); a category instead of
-- interfaces; interfaces removed.

CREATE TABLE rule (
    id              uuid PRIMARY KEY,
    key             varchar(64)  NOT NULL,
    name            varchar(200) NOT NULL,
    description     text         NOT NULL DEFAULT '',
    content         text         NOT NULL,
    scope_type      varchar(16)  NOT NULL,
    project_id      uuid,
    automatic       boolean      NOT NULL DEFAULT false,
    tags            jsonb        NOT NULL DEFAULT '[]'::jsonb,
    built_in        boolean      NOT NULL DEFAULT false,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    updated_by_kind varchar(16),
    updated_by_id   uuid,
    updated_by_name varchar(200),
    CONSTRAINT rule_scope_chk CHECK (
        (scope_type = 'GLOBAL' AND project_id IS NULL) OR (scope_type = 'PROJECT' AND project_id IS NOT NULL)
    ),
    CONSTRAINT rule_key_scope_uq UNIQUE NULLS NOT DISTINCT (key, scope_type, project_id)
);

CREATE INDEX rule_project_idx ON rule (project_id);
CREATE INDEX rule_automatic_idx ON rule (automatic) WHERE automatic;

ALTER TABLE task_template
    ADD COLUMN category     varchar(32),
    ADD COLUMN rules        jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN remove_rules jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX task_template_rules_idx ON task_template USING gin (rules);

-- The built-in skills that are rules in fact move, in every scope (a project copy stays a project copy). Their
-- content keeps its SKILL.md frontmatter until the seed upgrades unedited built-ins.
INSERT INTO rule (id, key, name, description, content, scope_type, project_id, automatic, tags, built_in,
                  created_at, updated_at, updated_by_kind, updated_by_id, updated_by_name)
SELECT id, key, name, description, content, scope_type, project_id, false, tags, built_in,
       created_at, updated_at, updated_by_kind, updated_by_id, updated_by_name
FROM skill
WHERE key IN ('agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro');

DELETE FROM skill WHERE key IN ('agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro');

UPDATE task_template t
SET rules = (SELECT coalesce(jsonb_agg(e ORDER BY o), '[]'::jsonb) FROM jsonb_array_elements(t.skills) WITH ORDINALITY AS x(e, o)
             WHERE e #>> '{}' IN ('agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro')),
    skills = (SELECT coalesce(jsonb_agg(e ORDER BY o), '[]'::jsonb) FROM jsonb_array_elements(t.skills) WITH ORDINALITY AS x(e, o)
              WHERE e #>> '{}' NOT IN ('agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro')),
    remove_rules = (SELECT coalesce(jsonb_agg(e ORDER BY o), '[]'::jsonb) FROM jsonb_array_elements(t.remove_skills) WITH ORDINALITY AS x(e, o)
                    WHERE e #>> '{}' IN ('agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro')),
    remove_skills = (SELECT coalesce(jsonb_agg(e ORDER BY o), '[]'::jsonb) FROM jsonb_array_elements(t.remove_skills) WITH ORDINALITY AS x(e, o)
                     WHERE e #>> '{}' NOT IN ('agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro'))
WHERE t.skills ?| ARRAY['agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro']
   OR t.remove_skills ?| ARRAY['agent-ground-rules', 'ask-dont-guess', 'kotlin-hexagonal', 'angular-ng-zorro'];

-- Category from the first implemented interface, else from the executor; abstract roots stay without one.
UPDATE task_template
SET category = CASE implements ->> 0
        WHEN 'specifier' THEN 'SPECIFICATION'
        WHEN 'clarifier' THEN 'SPECIFICATION'
        WHEN 'requirement-writer' THEN 'SPECIFICATION'
        WHEN 'planner' THEN 'PLANNING'
        WHEN 'task-planner' THEN 'PLANNING'
        WHEN 'implementer' THEN 'IMPLEMENTATION'
        WHEN 'reviewer' THEN 'REVIEW'
        WHEN 'analyst' THEN 'ANALYSIS'
        WHEN 'change-analyst' THEN 'ANALYSIS'
        ELSE CASE executor
            WHEN 'FETCH_ISSUE' THEN 'JIRA'
            WHEN 'NOTIFY' THEN 'JIRA'
            WHEN 'TRANSITION_ISSUE' THEN 'JIRA'
            WHEN 'START_WORKFLOW' THEN 'OPERATIONS'
            WHEN 'PROJECT_DIGEST' THEN 'OPERATIONS'
            ELSE NULL
        END
    END
WHERE NOT abstract;

DROP INDEX IF EXISTS task_template_implements_idx;
ALTER TABLE task_template DROP COLUMN implements;
DROP TABLE task_interface;
