-- ADR 0046: where the coding jobs of a benchmark run, the shared runner fleet or the creator's personal runner.
ALTER TABLE benchmark ADD COLUMN runner_target varchar(16) NOT NULL DEFAULT 'SHARED';
