-- ADR 0048: how a model is reached; CLAUDE_CODE_LOGIN = Claude Code with its user's own login on a personal runner.
ALTER TABLE model_profile ADD COLUMN runtime varchar(32) NOT NULL DEFAULT 'API';
