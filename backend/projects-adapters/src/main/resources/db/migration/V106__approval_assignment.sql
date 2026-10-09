-- ADR 0028: a project owner or maintainer (run.assign) may assign an OPEN approval to named users at run time.
-- `assignment` = {"userIds": [uuid...], "assignedBy": actor, "assignedAt": timestamp, "comment": text|null};
-- NULL = the gate's own approver rule (approvers.users) applies. The roles and four-eyes rules always apply.

ALTER TABLE approval_request ADD COLUMN assignment jsonb;
