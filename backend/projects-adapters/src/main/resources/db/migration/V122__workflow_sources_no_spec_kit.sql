-- ADR 0063: sources belong to the workflow; no Spec Kit.
--   * workflow_definition / library_workflow_version get `sources` (JSON list of {key, library}): the repositories
--     cloned into the run directory; empty = the project's primary repository. V309 (tasks range, runs after this one)
--     fills them from the former step `repositories`.
--   * project.spec_kit_enabled (V110, ADR 0036) is dropped: Back Office no longer runs Spec Kit.
ALTER TABLE workflow_definition ADD COLUMN sources jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE library_workflow_version ADD COLUMN sources jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE project DROP COLUMN IF EXISTS spec_kit_enabled;
