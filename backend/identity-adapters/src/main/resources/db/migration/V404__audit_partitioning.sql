-- Audit log as declarative monthly range partitions on event_time (ADR 0018, docs/architecture/09-scale-and-operations.md
-- "Storage and data lifecycle": 2 years, then export). The primary key (event_time, id) and the keyset
-- cursor stay the same. Partitions are named audit_log_pYYYY_MM and cover UTC months; this migration creates
-- one per month from the oldest existing row (at least current month - 3) to current month + 3, the identity
-- DataRetentionJobs keep creating future months. audit_log_default catches anything outside the created
-- range so an audit write never fails; the job moves such rows into their month when it creates it.
-- Existing rows are copied in this transaction (fine for the audit volumes of this platform; for very large
-- tables run it in a maintenance window).

ALTER TABLE audit_log RENAME TO audit_log_unpartitioned;
DROP INDEX IF EXISTS audit_log_actor_idx;
DROP INDEX IF EXISTS audit_log_entity_idx;
DROP INDEX IF EXISTS audit_log_project_idx;
DROP INDEX IF EXISTS audit_log_operation_idx;
DROP INDEX IF EXISTS audit_log_correlation_idx;
ALTER TABLE audit_log_unpartitioned RENAME CONSTRAINT audit_log_pkey TO audit_log_unpartitioned_pkey;

CREATE TABLE audit_log (
    event_time        timestamptz NOT NULL,
    id                uuid        NOT NULL,
    actor_kind        text        NOT NULL,
    actor_id          uuid,
    actor_name        text        NOT NULL,
    operation         text        NOT NULL,
    entity_type       text        NOT NULL,
    entity_id         text,
    project_id        uuid,
    result            text        NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE')),
    details           jsonb,
    correlation_id    text,
    source_ip         text,
    user_agent        text,
    PRIMARY KEY (event_time, id)
) PARTITION BY RANGE (event_time);

CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

DO $$
DECLARE
    oldest timestamptz := (SELECT min(event_time) FROM audit_log_unpartitioned);
    first_month date := date_trunc('month', (LEAST(COALESCE(oldest, now()), now() - interval '3 months')) AT TIME ZONE 'UTC')::date;
    last_month date := date_trunc('month', (now() + interval '3 months') AT TIME ZONE 'UTC')::date;
    m date := first_month;
BEGIN
    WHILE m <= last_month LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            'audit_log_p' || to_char(m, 'YYYY_MM'),
            to_char(m, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char((m + interval '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00'
        );
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;

INSERT INTO audit_log SELECT event_time, id, actor_kind, actor_id, actor_name, operation, entity_type, entity_id, project_id,
                             result, details, correlation_id, source_ip, user_agent
                      FROM audit_log_unpartitioned;
DROP TABLE audit_log_unpartitioned;

-- Partitioned indexes (created on every partition, present and future).
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, event_time DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, event_time DESC);
CREATE INDEX audit_log_project_idx ON audit_log (project_id, event_time DESC);
CREATE INDEX audit_log_operation_idx ON audit_log (operation, event_time DESC);
CREATE INDEX audit_log_correlation_idx ON audit_log (correlation_id);
