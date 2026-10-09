-- Actor stamps for the Model Evaluation context (ADR 0017, docs/architecture/08-identity-and-access.md).
-- Denormalised (kind, user id, display name at the time of the change) so history stays readable after a
-- user is renamed or deactivated. NULL for rows written before this migration.

ALTER TABLE model_profile
    ADD COLUMN updated_by_kind varchar(16),
    ADD COLUMN updated_by_id   uuid,
    ADD COLUMN updated_by_name varchar(200);

ALTER TABLE task_suite
    ADD COLUMN updated_by_kind varchar(16),
    ADD COLUMN updated_by_id   uuid,
    ADD COLUMN updated_by_name varchar(200);

ALTER TABLE benchmark
    ADD COLUMN created_by_kind varchar(16),
    ADD COLUMN created_by_id   uuid,
    ADD COLUMN created_by_name varchar(200),
    ADD COLUMN updated_by_kind varchar(16),
    ADD COLUMN updated_by_id   uuid,
    ADD COLUMN updated_by_name varchar(200);

CREATE INDEX benchmark_created_by_idx ON benchmark (created_by_id);
