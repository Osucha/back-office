-- ADR 0033: Jira intake watcher, approval before external writes; ADR 0030 follow-up: token expiry reminders.

-- The intake watcher of a project: configuration (the JQL filter stays in project.jira_jql) and polling state.
-- cursor = start of the last successful poll; the next poll looks back from there with an overlap.
CREATE TABLE jira_intake (
    project_id            uuid PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    enabled               boolean     NOT NULL DEFAULT false,
    poll_interval_minutes integer     NOT NULL DEFAULT 5 CHECK (poll_interval_minutes BETWEEN 1 AND 1440),
    workflow_id           uuid,
    cursor_at             timestamptz,
    last_poll_at          timestamptz,
    last_success_at       timestamptz,
    last_error            text,
    last_error_at         timestamptz,
    consecutive_failures  integer     NOT NULL DEFAULT 0,
    next_poll_at          timestamptz,
    issues_started        bigint      NOT NULL DEFAULT 0,
    last_connection       text,
    last_warning          text,
    updated_at            timestamptz,
    updated_by            jsonb
);

CREATE INDEX idx_jira_intake_due ON jira_intake (next_poll_at) WHERE enabled;

-- Dedupe keys of the runs the watcher started: T:<issue>:<history id> (or :<status>:<updated>) per transition,
-- N:<issue> per new issue. The primary key makes the claim atomic across replicas.
CREATE TABLE jira_intake_claim (
    project_id  uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    dedupe_key  text        NOT NULL,
    issue_key   text        NOT NULL,
    run_id      uuid,
    claimed_at  timestamptz NOT NULL,
    PRIMARY KEY (project_id, dedupe_key)
);

CREATE INDEX idx_jira_intake_claim_age ON jira_intake_claim (claimed_at);

-- Approval before external writes: a write confirmation is an approval of kind WRITE_CONFIRMATION carrying the
-- exact write ({action, issueKey, comment, toStatus, connectionId, connectionName}) that is performed after approval.
ALTER TABLE approval_request ADD COLUMN kind text NOT NULL DEFAULT 'GATE' CHECK (kind IN ('GATE', 'WRITE_CONFIRMATION'));
ALTER TABLE approval_request ADD COLUMN write_preview jsonb;

-- Token expiry reminders: one reminder per expiry date.
ALTER TABLE connection ADD COLUMN expiry_reminded_for date;
