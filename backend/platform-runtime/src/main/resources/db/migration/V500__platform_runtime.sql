-- Scale-out backbone (ADR 0023, docs/architecture/09-scale-and-operations.md). Version range V500-V549: platform.

-- Durable work queue. At most one live (READY or LEASED) item per dedupe key; `generation` is bumped when a
-- new request is merged into a live item, so a worker that finishes an item whose generation changed while
-- it held the lease puts it back to READY instead of DONE (the merged signal is never lost).
CREATE TABLE work_item (
    id            uuid PRIMARY KEY,
    kind          varchar(64)  NOT NULL,
    dedupe_key    varchar(255),
    payload       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    priority      smallint     NOT NULL DEFAULT 0,
    run_after     timestamptz  NOT NULL DEFAULT now(),
    status        varchar(16)  NOT NULL DEFAULT 'READY',
    attempts      integer      NOT NULL DEFAULT 0,
    max_attempts  integer      NOT NULL DEFAULT 10,
    generation    integer      NOT NULL DEFAULT 0,
    lease_owner   varchar(255),
    lease_until   timestamptz,
    last_error    text,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    CONSTRAINT work_item_status_chk CHECK (status IN ('READY', 'LEASED', 'DONE', 'DEAD'))
);

CREATE UNIQUE INDEX work_item_live_dedupe ON work_item (dedupe_key) WHERE status IN ('READY', 'LEASED');
CREATE INDEX work_item_ready ON work_item (status, run_after, priority);
CREATE INDEX work_item_lease ON work_item (lease_until) WHERE status = 'LEASED';
CREATE INDEX work_item_kind_status ON work_item (kind, status);
CREATE INDEX work_item_finished ON work_item (finished_at) WHERE status = 'DONE';

-- Outbox of domain events; NOTIFY backoffice_events '<id>' is issued in the same transaction (delivered on
-- commit). Api replicas LISTEN, load the row and fan out to their SSE subscribers. Retention 24 h.
CREATE TABLE event_log (
    id           bigserial PRIMARY KEY,
    type         varchar(128) NOT NULL,
    project_id   uuid,
    payload      jsonb        NOT NULL,
    occurred_at  timestamptz  NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX event_log_created ON event_log (created_at);

-- ShedLock (net.javacrumbs.shedlock, JdbcTemplateLockProvider with database time)
CREATE TABLE shedlock (
    name        varchar(64)  NOT NULL PRIMARY KEY,
    lock_until  timestamp    NOT NULL,
    locked_at   timestamp    NOT NULL,
    locked_by   varchar(255) NOT NULL
);

-- Instance registry: every replica heartbeats its roles and version (operations overview, stale after 2 min).
CREATE TABLE platform_instance (
    id            varchar(255) PRIMARY KEY,
    roles         varchar(255) NOT NULL,
    hostname      varchar(255),
    version       varchar(64),
    started_at    timestamptz  NOT NULL,
    heartbeat_at  timestamptz  NOT NULL,
    stopping      boolean      NOT NULL DEFAULT false
);
