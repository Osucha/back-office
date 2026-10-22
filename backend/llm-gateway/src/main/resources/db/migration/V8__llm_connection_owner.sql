-- ADR 0050 step 2b: personal provider connections (the user's own API keys); null = a platform connection.
ALTER TABLE llm_provider_connection ADD COLUMN owner_user_id uuid;
CREATE INDEX llm_provider_connection_owner_idx ON llm_provider_connection (owner_user_id);
