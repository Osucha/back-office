-- Run inputs declared by library tasks (ADR 0052, docs/architecture/07-task-library.md): the fields of the run
-- start form a template asks for, as a JSON list of {name, label, type, required, help}. Additive; existing rows
-- declare none.

ALTER TABLE task_template
    ADD COLUMN run_inputs jsonb NOT NULL DEFAULT '[]';
