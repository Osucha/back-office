-- ADR 0069: whether a runner reported a GitHub Copilot login (the github-copilot entry of an OpenCode auth file mounted
-- into its runner-controller) with its last lease. null = not reported (controllers before this change).
ALTER TABLE runner ADD COLUMN copilot_login boolean;
