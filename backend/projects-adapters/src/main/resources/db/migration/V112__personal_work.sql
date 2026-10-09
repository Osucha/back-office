-- ADR 0038: personal work next to the team mode. Private projects, personal Jira intake rules on the owner's
-- personal connection, and the run proposals they create for the owner.

ALTER TABLE project ADD COLUMN personal boolean NOT NULL DEFAULT false;

CREATE TABLE personal_intake_rule (
    id                    uuid PRIMARY KEY,
    owner_user_id         uuid NOT NULL,
    owner_name            text NOT NULL,
    name                  text NOT NULL,
    connection_id         uuid NOT NULL,
    jql                   text NOT NULL,
    poll_interval_minutes integer NOT NULL DEFAULT 5,
    workflow_id           uuid NOT NULL,
    enabled               boolean NOT NULL DEFAULT true,
    include_existing      boolean NOT NULL DEFAULT true,
    initialized           boolean NOT NULL DEFAULT false,
    next_poll_at          timestamptz,
    last_poll_at          timestamptz,
    last_success_at       timestamptz,
    last_error            text,
    consecutive_failures  integer NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);

CREATE INDEX idx_personal_intake_rule_owner ON personal_intake_rule (owner_user_id);
CREATE INDEX idx_personal_intake_rule_due ON personal_intake_rule (next_poll_at) WHERE enabled;

-- One proposal per issue and rule (UNIQUE): an issue that appears in a rule's results again is not proposed twice.
CREATE TABLE intake_proposal (
    id            uuid PRIMARY KEY,
    rule_id       uuid NOT NULL REFERENCES personal_intake_rule (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL,
    issue_key     text NOT NULL,
    summary       text,
    issue_type    text,
    status        text,
    priority      text,
    state         text NOT NULL,
    detected_at   timestamptz NOT NULL,
    decided_at    timestamptz,
    workflow_id   uuid,
    run_id        uuid,
    UNIQUE (rule_id, issue_key)
);

CREATE INDEX idx_intake_proposal_owner_state ON intake_proposal (owner_user_id, state, detected_at DESC);
