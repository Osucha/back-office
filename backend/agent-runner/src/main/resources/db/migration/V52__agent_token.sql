-- Prompt-injection defences (ADR 0029): the runner entrypoint exchanges its job token for a gateway-only
-- agent token that the coding agent uses for the LLM proxy. The agent token cannot post events, results or
-- uploads. One per lease; a new lease (setTokenHash) clears it. SHA-256 hex of the token, never the token.
ALTER TABLE agent_job
    ADD COLUMN agent_token_hash text UNIQUE;
