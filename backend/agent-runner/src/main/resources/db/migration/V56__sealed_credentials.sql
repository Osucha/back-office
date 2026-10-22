-- ADR 0051: sealed delivery of project repository tokens. A runner-controller registers an X25519 public key
-- (base64 of the 32-byte raw key); Back Office seals each job's tokens to it (HPKE, RFC 9180). A job's tokens are
-- released once per lease: credentials_released_at is compared with leased_at, so a re-queued job can fetch again.

ALTER TABLE runner ADD COLUMN public_key text;
ALTER TABLE runner ADD COLUMN public_key_set_at timestamptz;

ALTER TABLE agent_job ADD COLUMN credentials_released_at timestamptz;
