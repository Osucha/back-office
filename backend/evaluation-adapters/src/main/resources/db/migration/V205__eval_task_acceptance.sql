-- ADR 0047: hidden acceptance files, protected paths and the no-op check of a CODING_JOB task.
ALTER TABLE eval_task ADD COLUMN repo_acceptance jsonb;
