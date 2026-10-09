-- Prompt-injection defences (ADR 0029): a personal access token may decide human gates through the MCP
-- server only when it was created with this option. Existing tokens keep working for everything else but
-- lose MCP gate decisions until the user creates a token with the option.
ALTER TABLE identity_personal_token
    ADD COLUMN mcp_gate_decisions boolean NOT NULL DEFAULT false;
