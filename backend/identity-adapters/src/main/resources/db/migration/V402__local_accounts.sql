-- Local accounts (ADR 0021): per-user sign-in method, password credentials with lockout state, TOTP
-- authenticators, single-use recovery codes and one-time set-password links. Existing users are OIDC users.

ALTER TABLE identity_user
    ADD COLUMN auth_mode         text NOT NULL DEFAULT 'OIDC' CHECK (auth_mode IN ('LOCAL', 'OIDC', 'BOTH')),
    ADD COLUMN last_login_method text CHECK (last_login_method IN ('LOCAL', 'OIDC'));

-- password_hash NULL = no usable password (invited local user, or reset issued: the old password stopped working).
CREATE TABLE identity_local_credential (
    user_id              uuid PRIMARY KEY REFERENCES identity_user (id) ON DELETE CASCADE,
    password_hash        text,
    password_changed_at  timestamptz,
    must_change_password boolean     NOT NULL DEFAULT false,
    failed_attempts      integer     NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    lockout_count        integer     NOT NULL DEFAULT 0
);

-- secret_encrypted: AES-GCM with backoffice.auth.local.secret-key. confirmed_at NULL = enrolment pending.
CREATE TABLE identity_mfa_totp (
    user_id          uuid PRIMARY KEY REFERENCES identity_user (id) ON DELETE CASCADE,
    secret_encrypted text        NOT NULL,
    created_at       timestamptz NOT NULL,
    confirmed_at     timestamptz,
    last_used_step   bigint
);

-- code_hash: HMAC-SHA256 keyed from the secret key (recovery codes have ~50 bits).
CREATE TABLE identity_mfa_recovery_code (
    user_id    uuid        NOT NULL REFERENCES identity_user (id) ON DELETE CASCADE,
    code_hash  text        NOT NULL,
    created_at timestamptz NOT NULL,
    used_at    timestamptz,
    PRIMARY KEY (user_id, code_hash)
);

-- token_hash: SHA-256 hex of the 256-bit link token; the plaintext is only in the administrator's response.
CREATE TABLE identity_password_reset_token (
    token_hash      text PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES identity_user (id) ON DELETE CASCADE,
    purpose         text        NOT NULL CHECK (purpose IN ('SET_INITIAL', 'RESET')),
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    created_by_kind text        NOT NULL,
    created_by_id   uuid,
    created_by_name text        NOT NULL,
    used_at         timestamptz
);
CREATE INDEX identity_password_reset_token_user_idx ON identity_password_reset_token (user_id);

ALTER TABLE identity_platform_settings
    ADD COLUMN local_login_enabled  boolean NOT NULL DEFAULT true,
    ADD COLUMN mfa_required_local   boolean NOT NULL DEFAULT false,
    ADD COLUMN password_policy      jsonb   NOT NULL DEFAULT '{"minLength":12,"maxLength":128,"rejectCommon":true,"rejectContainingEmail":true}'::jsonb,
    ADD COLUMN lockout_max_failures integer NOT NULL DEFAULT 5,
    ADD COLUMN lockout_minutes      integer NOT NULL DEFAULT 15;
