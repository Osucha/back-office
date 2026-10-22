-- ADR 0051: tokens of a project's repositories (several per repository, one per permission level). The token is
-- stored only sealed, like connection secrets (V107): AES-256-GCM with a random data key (secret_ciphertext), the
-- data key wrapped with the key-encryption key secret_key_id (secret_wrapped_key), associated data
-- "scm-connection:<id>". repository_key names an entry of project.repositories; a connection whose repository was
-- removed stays until it is deleted (shown as orphaned, never selected).

CREATE TABLE scm_connection (
    id                 uuid PRIMARY KEY,
    project_id         uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    repository_key     text        NOT NULL,
    name               text        NOT NULL,
    provider           text        NOT NULL,
    capabilities       jsonb       NOT NULL,
    secret_key_id      text        NOT NULL,
    secret_wrapped_key text        NOT NULL,
    secret_ciphertext  text        NOT NULL,
    secret_expires_at  date,
    verification       jsonb,
    created_at         timestamptz NOT NULL,
    created_by         jsonb,
    updated_at         timestamptz NOT NULL,
    updated_by         jsonb
);

CREATE INDEX idx_scm_connection_project ON scm_connection (project_id);
CREATE UNIQUE INDEX uq_scm_connection_name ON scm_connection (project_id, lower(name));
