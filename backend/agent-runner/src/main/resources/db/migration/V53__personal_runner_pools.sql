-- ADR 0040: personal runner pools. A pool with an owner runs only jobs routed to that user's personal runner
-- (steps of their private projects); at most one personal pool per user. llm_credentials says how Claude Code in
-- the pool's sandboxes reaches the model: GATEWAY (Back Office LLM gateway, metered) or LOCAL_LOGIN (the owner's own
-- Claude login on their runner-controller, not metered by Back Office).

ALTER TABLE runner_pool
    ADD COLUMN owner_user_id uuid,
    ADD COLUMN llm_credentials text NOT NULL DEFAULT 'GATEWAY';

ALTER TABLE runner_pool
    ADD CONSTRAINT runner_pool_llm_credentials_check CHECK (llm_credentials IN ('GATEWAY', 'LOCAL_LOGIN')),
    ADD CONSTRAINT runner_pool_local_login_personal_check CHECK (llm_credentials = 'GATEWAY' OR owner_user_id IS NOT NULL);

CREATE UNIQUE INDEX runner_pool_owner_user_id_key ON runner_pool (owner_user_id) WHERE owner_user_id IS NOT NULL;
