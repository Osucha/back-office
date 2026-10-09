-- Identity & Access context (ADR 0016-0018, docs/architecture/08-identity-and-access.md).
-- Users, groups, roles, platform grants, project memberships, personal access tokens, platform settings,
-- the access version counter and the transactional audit log. Flyway range V400-V499.

CREATE TABLE identity_user (
    id              uuid PRIMARY KEY,
    issuer          text,
    subject         text,
    email           text        NOT NULL,
    display_name    text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DEACTIVATED')),
    preferences     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL,
    created_by_kind text        NOT NULL,
    created_by_id   uuid,
    created_by_name text        NOT NULL,
    CONSTRAINT identity_user_identity_uq UNIQUE (issuer, subject),
    CONSTRAINT identity_user_bound_ck CHECK ((issuer IS NULL) = (subject IS NULL))
);
CREATE UNIQUE INDEX identity_user_email_uq ON identity_user (lower(email));
CREATE INDEX identity_user_status_idx ON identity_user (status);

CREATE TABLE identity_group (
    id             uuid PRIMARY KEY,
    name           text        NOT NULL,
    description    text,
    external_group text,
    created_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX identity_group_name_uq ON identity_group (lower(name));

CREATE TABLE identity_group_member (
    group_id uuid NOT NULL REFERENCES identity_group (id) ON DELETE CASCADE,
    user_id  uuid NOT NULL REFERENCES identity_user (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX identity_group_member_user_idx ON identity_group_member (user_id);

CREATE TABLE identity_role (
    key         text PRIMARY KEY,
    name        text    NOT NULL,
    description text,
    scope       text    NOT NULL CHECK (scope IN ('PLATFORM', 'PROJECT')),
    permissions text[]  NOT NULL DEFAULT '{}',
    built_in    boolean NOT NULL DEFAULT false
);

-- Platform grants. SYSTEM rows use the all-zero UUID as target so that the primary key needs no NULLs.
CREATE TABLE identity_grant (
    level      text NOT NULL CHECK (level IN ('SYSTEM', 'GROUP', 'USER')),
    target_id  uuid NOT NULL,
    permission text NOT NULL,
    effect     text NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    PRIMARY KEY (level, target_id, permission)
);

CREATE TABLE identity_role_assignment (
    level     text NOT NULL CHECK (level IN ('SYSTEM', 'GROUP', 'USER')),
    target_id uuid NOT NULL,
    role_key  text NOT NULL REFERENCES identity_role (key) ON DELETE CASCADE,
    PRIMARY KEY (level, target_id, role_key)
);
CREATE INDEX identity_role_assignment_role_idx ON identity_role_assignment (role_key);

CREATE TABLE identity_project_membership (
    id             uuid PRIMARY KEY,
    project_id     uuid        NOT NULL,
    project_key    text        NOT NULL,
    principal_type text        NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id   uuid        NOT NULL,
    role_key       text        NOT NULL REFERENCES identity_role (key),
    added_at       timestamptz NOT NULL,
    added_by_kind  text        NOT NULL,
    added_by_id    uuid,
    added_by_name  text        NOT NULL,
    CONSTRAINT identity_membership_uq UNIQUE (project_id, principal_type, principal_id)
);
CREATE INDEX identity_membership_principal_idx ON identity_project_membership (principal_type, principal_id);

CREATE TABLE identity_personal_token (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES identity_user (id) ON DELETE CASCADE,
    name         text        NOT NULL,
    token_hash   text        NOT NULL UNIQUE,
    prefix       text        NOT NULL,
    permissions  text[]      NOT NULL DEFAULT '{}',
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);
CREATE INDEX identity_personal_token_user_idx ON identity_personal_token (user_id);

-- One row once an administrator saved settings; until then the backoffice.auth.* defaults apply.
CREATE TABLE identity_platform_settings (
    id                        smallint PRIMARY KEY CHECK (id = 1),
    registration              text        NOT NULL CHECK (registration IN ('OPEN', 'INVITE_ONLY')),
    allowed_email_domains     text[]      NOT NULL DEFAULT '{}',
    sync_groups               boolean     NOT NULL DEFAULT false,
    personal_token_max_days   integer     NOT NULL,
    updated_at                timestamptz NOT NULL
);

-- Incremented in the same transaction as every access change; keys the effective-permission cache.
CREATE TABLE identity_access_version (
    id      smallint PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL
);
INSERT INTO identity_access_version (id, version) VALUES (1, 0);

-- Write-only audit log (ADR 0018). (event_time, id) is the primary key and the keyset cursor, and
-- prepares monthly partitioning. There is no UPDATE or DELETE in the application code.
CREATE TABLE audit_log (
    event_time        timestamptz NOT NULL,
    id                uuid        NOT NULL,
    actor_kind        text        NOT NULL,
    actor_id          uuid,
    actor_name        text        NOT NULL,
    operation         text        NOT NULL,
    entity_type       text        NOT NULL,
    entity_id         text,
    project_id        uuid,
    result            text        NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE')),
    details           jsonb,
    correlation_id    text,
    source_ip         text,
    user_agent        text,
    PRIMARY KEY (event_time, id)
);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, event_time DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, event_time DESC);
CREATE INDEX audit_log_project_idx ON audit_log (project_id, event_time DESC);
CREATE INDEX audit_log_operation_idx ON audit_log (operation, event_time DESC);
CREATE INDEX audit_log_correlation_idx ON audit_log (correlation_id);
