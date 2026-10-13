-- Workflow drafts: a workflow saved with validation errors is kept as a draft. No run starts from a draft and it
-- cannot be published to the library until it is saved without errors. Existing workflows passed validation on save.

ALTER TABLE workflow_definition
    ADD COLUMN draft boolean NOT NULL DEFAULT false;
