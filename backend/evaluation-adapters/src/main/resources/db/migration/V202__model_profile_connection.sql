-- ADR 0045: a model profile names the LLM provider connection that serves it (llm_provider_connection.key); null =
-- the provider's first connection, else its environment configuration.

ALTER TABLE model_profile ADD COLUMN connection_key varchar(40);
