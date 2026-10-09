-- ADR 0030: least-privilege connections (Jira credentials) scoped to the platform or one project.
-- The secret (classic Atlassian API token) is stored only sealed: AES-256-GCM with a random data key per secret
-- (secret_ciphertext), the data key wrapped with the key-encryption key secret_key_id (secret_wrapped_key) from
-- BACKOFFICE_SECRETS_KEY. Both values are base64 and include their nonces. The plaintext never reaches the database.

CREATE TABLE connection (
    id                   uuid PRIMARY KEY,
    scope                text        NOT NULL CHECK (scope IN ('PLATFORM', 'PROJECT')),
    project_id           uuid REFERENCES project (id) ON DELETE CASCADE,
    type                 text        NOT NULL,
    name                 text        NOT NULL,
    site_url             text        NOT NULL,
    account_email        text        NOT NULL,
    capabilities         jsonb       NOT NULL,
    allowed_project_keys jsonb       NOT NULL DEFAULT '[]'::jsonb,
    verify_project_key   text,
    secret_key_id        text        NOT NULL,
    secret_wrapped_key   text        NOT NULL,
    secret_ciphertext    text        NOT NULL,
    secret_rotated_at    timestamptz,
    secret_expires_at    date,
    verification         jsonb,
    created_at           timestamptz NOT NULL,
    created_by           jsonb,
    updated_at           timestamptz NOT NULL,
    updated_by           jsonb,
    CONSTRAINT connection_scope_project CHECK ((scope = 'PROJECT') = (project_id IS NOT NULL))
);

CREATE INDEX idx_connection_project ON connection (project_id);
CREATE UNIQUE INDEX uq_connection_name ON connection (COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
