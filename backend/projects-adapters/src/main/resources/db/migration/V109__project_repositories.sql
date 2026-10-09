-- ADR 0034: optional, multiple repositories per project.
-- The single repository (repo_url / repo_default_branch) becomes the list entry `main` (SOURCE, primary).
-- The legacy columns stay, nullable, as a mirror of the primary repository for one release (rolling upgrades:
-- an older api replica still reads them); a later migration drops them.

ALTER TABLE project ADD COLUMN IF NOT EXISTS repositories jsonb NOT NULL DEFAULT '[]'::jsonb;

UPDATE project
SET repositories = jsonb_build_array(
        jsonb_build_object(
            'key', 'main',
            'url', repo_url,
            'defaultBranch', COALESCE(NULLIF(btrim(repo_default_branch), ''), 'main'),
            'purpose', 'SOURCE',
            'description', NULL,
            'primary', true
        )
    )
WHERE repositories = '[]'::jsonb
  AND repo_url IS NOT NULL
  AND btrim(repo_url) <> '';

ALTER TABLE project ALTER COLUMN repo_url DROP NOT NULL;
ALTER TABLE project ALTER COLUMN repo_default_branch DROP NOT NULL;
ALTER TABLE project ALTER COLUMN repo_default_branch DROP DEFAULT;

-- the project repository a CI execution ran in (matched by URL); null for CI clients older than backoffice-ci.sh 2.1
ALTER TABLE ci_execution ADD COLUMN IF NOT EXISTS repository_key text;
