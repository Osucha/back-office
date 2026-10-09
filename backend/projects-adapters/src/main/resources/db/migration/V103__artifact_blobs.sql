-- Artefact content above the inline limit (64 kB by default) moves to the BlobStore under
-- runs/<runId>/artifacts/<artifactId>; the row keeps metadata (ADR 0023, docs/architecture/09-scale-and-operations.md).
-- content stays NOT NULL and is '' for blob-backed rows. Existing large rows are moved by the projects
-- retention job in batches (docs/architecture/performance.md "Artefact storage").

ALTER TABLE artifact ADD COLUMN blob_key text;
ALTER TABLE artifact ADD COLUMN sha256 text;
ALTER TABLE artifact ADD COLUMN content_expired_at timestamptz;

-- retention: blob-backed artefacts per run
CREATE INDEX idx_artifact_blob_run ON artifact (run_id) WHERE blob_key IS NOT NULL;
-- one-off migration of legacy large inline rows (empty once migrated)
CREATE INDEX idx_artifact_inline_large ON artifact (created_at) WHERE blob_key IS NULL AND content_expired_at IS NULL AND size_bytes > 65536;

-- Blob keys / prefixes to delete once the owning rows are gone (written in the same transaction as the
-- delete, processed by the retention job, so a rolled-back delete never loses content).
CREATE TABLE run_blob_gc (
    key_prefix  text PRIMARY KEY,
    enqueued_at timestamptz NOT NULL DEFAULT now()
);
