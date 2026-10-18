-- ADR 0045: LLM provider connections added in Back Office instead of environment variables. Several per provider type;
-- the API key is sealed with the installation's secret key (envelope AES-256-GCM, BACKOFFICE_SECRETS_KEY) and never
-- returned by the API. Model profiles name a connection by key.

CREATE TABLE llm_provider_connection (
    id                 uuid PRIMARY KEY,
    key                text        NOT NULL UNIQUE,
    name               text        NOT NULL,
    provider           text        NOT NULL CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GOOGLE', 'MISTRAL', 'OLLAMA', 'OPENAI_COMPATIBLE')),
    base_url           text,
    secret_key_id      text,
    secret_wrapped_key text,
    secret_ciphertext  text,
    enabled            boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    updated_by_kind    text,
    updated_by_id      uuid,
    updated_by_name    text,
    CONSTRAINT llm_provider_connection_secret_complete CHECK (
        (secret_key_id IS NULL AND secret_wrapped_key IS NULL AND secret_ciphertext IS NULL)
        OR (secret_key_id IS NOT NULL AND secret_wrapped_key IS NOT NULL AND secret_ciphertext IS NOT NULL))
);
