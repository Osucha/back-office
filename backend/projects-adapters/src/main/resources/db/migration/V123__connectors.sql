-- ADR 0064: connectors, the tools agents use (MCP servers with credentials, GITLAB_READ). Scope platform (project_id
-- NULL, administrators) or project (project owners); a project connector shadows the platform connector of the same key.
-- The secret of an MCP_HTTP connector is stored only sealed, like connection secrets (V107, V119): AES-256-GCM with a
-- random data key (secret_ciphertext), the data key wrapped with the key-encryption key secret_key_id
-- (secret_wrapped_key), associated data "connector:<id>". last_test is the result of the last test from Back Office.
-- The tasks module (V310) turns agent MCP servers and step agentTools into connectors.

CREATE TABLE connector (
    id                 uuid PRIMARY KEY,
    key                text        NOT NULL,
    name               text        NOT NULL,
    description        text        NOT NULL DEFAULT '',
    kind               text        NOT NULL,
    project_id         uuid REFERENCES project (id) ON DELETE CASCADE,
    url                text,
    auth               text        NOT NULL DEFAULT 'NONE',
    header_name        text,
    repository         text,
    secret_key_id      text,
    secret_wrapped_key text,
    secret_ciphertext  text,
    last_test          jsonb,
    created_at         timestamptz NOT NULL,
    created_by         jsonb,
    updated_at         timestamptz NOT NULL,
    updated_by         jsonb,
    CONSTRAINT ck_connector_secret CHECK ((secret_key_id IS NULL) = (secret_ciphertext IS NULL) AND (secret_key_id IS NULL) = (secret_wrapped_key IS NULL))
);

CREATE UNIQUE INDEX uq_connector_platform_key ON connector (key) WHERE project_id IS NULL;
CREATE UNIQUE INDEX uq_connector_project_key ON connector (project_id, key) WHERE project_id IS NOT NULL;
