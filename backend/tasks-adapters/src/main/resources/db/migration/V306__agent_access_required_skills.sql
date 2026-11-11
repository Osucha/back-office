-- ADR 0060: the environment is set once, on the agent.
--   * agent.access: what a coding agent may change ({"mode": FULL | READ_ONLY | PATHS, "paths": [globs]}); every
--     existing agent keeps FULL (the built-in analyst and reviewer too: they give model answers, access does not apply);
--   * task_template.requires_skills: skills the work needs; a step whose agent lacks one gets a warning;
--   * steps no longer add or remove skills: addSkills / removeSkills of stored steps are ignored when read (projects).

ALTER TABLE agent ADD COLUMN access jsonb NOT NULL DEFAULT '{"mode": "FULL"}'::jsonb;

ALTER TABLE task_template ADD COLUMN requires_skills jsonb NOT NULL DEFAULT '[]'::jsonb;
