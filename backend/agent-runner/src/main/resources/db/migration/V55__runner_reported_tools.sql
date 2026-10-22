-- ADR 0050 step 3: what a runner reported with its last lease: its coding agents and whether a Claude login is set.
-- null = not reported (controllers before this change).
ALTER TABLE runner ADD COLUMN harnesses jsonb;
ALTER TABLE runner ADD COLUMN claude_login boolean;
