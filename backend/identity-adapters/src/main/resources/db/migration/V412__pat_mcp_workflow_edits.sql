-- MCP workflow tools (ADR 0049): a personal access token may create and change workflows through the MCP
-- server (create_workflow, update_workflow) only when it was created with this option, because an AI agent
-- holding the token could otherwise change a workflow on its own (for example remove a human gate).
ALTER TABLE identity_personal_token
    ADD COLUMN mcp_workflow_edits boolean NOT NULL DEFAULT false;
