-- ADR 0050 step 2: personal model profiles (owner) and default models per kind of step.
ALTER TABLE model_profile ADD COLUMN owner_user_id uuid;
-- Claude Code login profiles are personal: the user who created them owns them
UPDATE model_profile SET owner_user_id = updated_by_id WHERE runtime = 'CLAUDE_CODE_LOGIN';
CREATE INDEX model_profile_owner_idx ON model_profile (owner_user_id);

-- owner_user_id null = the platform default; one default per owner and kind of step
CREATE TABLE model_default (
    id               uuid PRIMARY KEY,
    owner_user_id    uuid,
    step_kind        varchar(32)  NOT NULL,
    model_profile_id uuid         NOT NULL REFERENCES model_profile (id) ON DELETE CASCADE,
    updated_at       timestamptz  NOT NULL,
    updated_by_kind  varchar(16),
    updated_by_id    uuid,
    updated_by_name  varchar(200),
    CONSTRAINT model_default_owner_kind UNIQUE NULLS NOT DISTINCT (owner_user_id, step_kind)
);
