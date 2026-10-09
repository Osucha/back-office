-- Shared across replicas (ADR 0023): login throttle, MFA step-token single use, throttle audit dedupe.

-- Sliding-window log of throttled requests per key (source IP); rows older than the window are pruned.
CREATE TABLE identity_login_attempt (
    id          bigserial PRIMARY KEY,
    throttle_key varchar(128) NOT NULL,
    at          timestamptz  NOT NULL
);
CREATE INDEX identity_login_attempt_key_at ON identity_login_attempt (throttle_key, at);

-- Consumed MFA step tokens (nonce) until they would have expired anyway.
CREATE TABLE mfa_token_use (
    jti         varchar(64) PRIMARY KEY,
    expires_at  timestamptz NOT NULL
);
CREATE INDEX mfa_token_use_expires ON mfa_token_use (expires_at);

-- "Already audited this burst" marker of the login throttle, per key.
CREATE TABLE identity_throttle_audit (
    throttle_key varchar(128) PRIMARY KEY,
    until        timestamptz  NOT NULL
);
