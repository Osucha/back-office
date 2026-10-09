-- Actor stamps for the Task Library (ADR 0017, docs/architecture/08-identity-and-access.md): who saved the
-- current version of an interface, template or skill. Denormalised (kind, user id, display name at the time
-- of the change) so history stays readable after a user is renamed or deactivated. NULL for older rows.

ALTER TABLE task_interface
    ADD COLUMN updated_by_kind varchar(16),
    ADD COLUMN updated_by_id   uuid,
    ADD COLUMN updated_by_name varchar(200);

ALTER TABLE task_template
    ADD COLUMN updated_by_kind varchar(16),
    ADD COLUMN updated_by_id   uuid,
    ADD COLUMN updated_by_name varchar(200);

ALTER TABLE skill
    ADD COLUMN updated_by_kind varchar(16),
    ADD COLUMN updated_by_id   uuid,
    ADD COLUMN updated_by_name varchar(200);
