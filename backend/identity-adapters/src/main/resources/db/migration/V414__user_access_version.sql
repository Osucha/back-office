-- ADR 0068: access versions per user. A user's version is the global identity_access_version.version (changes
-- that may affect many users: roles, groups, the SYSTEM grant, settings) plus the user's row here (changes of that
-- user only), both bumped in the transaction of the change. changed_at lets every instance poll recent changes.
-- No foreign key: a row of a deleted user is harmless and a bump never fails a change.

CREATE TABLE identity_user_access_version (
    user_id    uuid        PRIMARY KEY,
    version    bigint      NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX identity_user_access_version_changed_at_idx ON identity_user_access_version (changed_at);
