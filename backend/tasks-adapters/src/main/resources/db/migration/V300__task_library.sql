-- Task Library context: interfaces, templates and skills (docs/architecture/07-task-library.md, ADR 0015).
-- Templates and skills are unique per (key, scope); PROJECT scope carries the project id, GLOBAL has NULL,
-- hence NULLS NOT DISTINCT so only one GLOBAL entry per key can exist.

CREATE TABLE task_interface (
    id          uuid PRIMARY KEY,
    key         varchar(64)  NOT NULL,
    name        varchar(200) NOT NULL,
    description text         NOT NULL DEFAULT '',
    inputs      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    outputs     jsonb        NOT NULL DEFAULT '[]'::jsonb,
    ports       jsonb        NOT NULL DEFAULT '["success","failure"]'::jsonb,
    built_in    boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT task_interface_key_uq UNIQUE (key)
);

CREATE TABLE task_template (
    id               uuid PRIMARY KEY,
    key              varchar(64)  NOT NULL,
    name             varchar(200) NOT NULL,
    description      text         NOT NULL DEFAULT '',
    abstract         boolean      NOT NULL DEFAULT false,
    extends_key      varchar(64),
    implements       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    executor         varchar(32),
    scope_type       varchar(16)  NOT NULL,
    project_id       uuid,
    model_profile_id uuid,
    prompt_system    text,
    prompt_user      text,
    prompt_mode      varchar(16)  NOT NULL DEFAULT 'APPEND',
    skills           jsonb        NOT NULL DEFAULT '[]'::jsonb,
    remove_skills    jsonb        NOT NULL DEFAULT '[]'::jsonb,
    config           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    output_schema    text,
    confidence_field varchar(200),
    produces         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    consumes         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    tags             jsonb        NOT NULL DEFAULT '[]'::jsonb,
    built_in         boolean      NOT NULL DEFAULT false,
    version          integer      NOT NULL DEFAULT 1,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT task_template_scope_chk CHECK (
        (scope_type = 'GLOBAL' AND project_id IS NULL) OR (scope_type = 'PROJECT' AND project_id IS NOT NULL)
    ),
    CONSTRAINT task_template_key_scope_uq UNIQUE NULLS NOT DISTINCT (key, scope_type, project_id)
);

CREATE INDEX task_template_project_idx ON task_template (project_id);
CREATE INDEX task_template_extends_idx ON task_template (extends_key);
CREATE INDEX task_template_implements_idx ON task_template USING gin (implements);
CREATE INDEX task_template_skills_idx ON task_template USING gin (skills);

CREATE TABLE skill (
    id          uuid PRIMARY KEY,
    key         varchar(64)  NOT NULL,
    name        varchar(200) NOT NULL,
    description text         NOT NULL DEFAULT '',
    content     text         NOT NULL,
    scope_type  varchar(16)  NOT NULL,
    project_id  uuid,
    tags        jsonb        NOT NULL DEFAULT '[]'::jsonb,
    built_in    boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT skill_scope_chk CHECK (
        (scope_type = 'GLOBAL' AND project_id IS NULL) OR (scope_type = 'PROJECT' AND project_id IS NOT NULL)
    ),
    CONSTRAINT skill_key_scope_uq UNIQUE NULLS NOT DISTINCT (key, scope_type, project_id)
);

CREATE INDEX skill_project_idx ON skill (project_id);
