-- LangGraph4j checkpoints are ordered by a monotonic sequence instead of the wall clock.
--
-- langgraph4j-postgres-saver 1.9.1 takes the newest checkpoint of a thread as the first row of
-- ORDER BY saved_at DESC, where saved_at defaults to CURRENT_TIMESTAMP (the database server's transaction
-- start time). When the database clock steps backwards (NTP/VM time sync, failover to a server with a skewed
-- clock) a later checkpoint sorts below an earlier one and a run resumes from, or is judged by, a stale
-- checkpoint ("graph stopped before node ..."). SequencedPostgresSaver orders by seq DESC NULLS LAST, saved_at DESC.
--
-- The tables are created here (same DDL as the library's v2.0 init script, IF NOT EXISTS) so this migration
-- does not depend on the application having started once. Existing rows keep seq NULL (no table rewrite)
-- and keep their saved_at order; every checkpoint written from now on gets a sequence number and sorts first.

CREATE TABLE IF NOT EXISTS lg4jthread (
    thread_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    thread_name      VARCHAR(255) UNIQUE NOT NULL,
    parent_thread_id BIGINT,
    is_interrupted   BOOLEAN DEFAULT FALSE NOT NULL,
    message          TEXT,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS lg4jthreadtag (
    thread_id        BIGINT PRIMARY KEY,
    thread_name      VARCHAR(255),
    released_version INTEGER,
    parent_thread_id BIGINT,
    is_released      BOOLEAN DEFAULT FALSE NOT NULL,
    is_error         BOOLEAN DEFAULT FALSE NOT NULL,
    message          TEXT,
    created_at       TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS lg4jcheckpoint (
    checkpoint_id        UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id            BIGINT NOT NULL,
    node_id              VARCHAR(255),
    next_node_id         VARCHAR(255),
    state_data           JSONB NOT NULL,
    state_content_type   VARCHAR(100) NOT NULL,
    saved_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id ON lg4jcheckpoint (thread_id);
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc ON lg4jcheckpoint (thread_id, saved_at DESC);
CREATE INDEX IF NOT EXISTS idx_lg4jthreadtag_thread_name_released_version ON lg4jthreadtag (thread_name, released_version DESC);

CREATE SEQUENCE IF NOT EXISTS lg4jcheckpoint_seq;
ALTER TABLE lg4jcheckpoint ADD COLUMN IF NOT EXISTS seq BIGINT;
ALTER TABLE lg4jcheckpoint ALTER COLUMN seq SET DEFAULT nextval('lg4jcheckpoint_seq');
ALTER SEQUENCE lg4jcheckpoint_seq OWNED BY lg4jcheckpoint.seq;
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_seq_desc ON lg4jcheckpoint (thread_id, seq DESC NULLS LAST);
