-- Auth service baseline schema.
--
-- This service owns identity and authentication only. Names, addresses and anything else
-- describing a person belong to the user service; duplicating them here would create two
-- sources of truth for the same fact.
--
-- Conventions used throughout FinPay:
--   * UUID primary keys, because identifiers appear in URLs and must not leak row counts
--     or let a caller enumerate other users by guessing the next integer.
--   * timestamptz everywhere. Every instant is stored in UTC; a timestamp without a zone
--     is ambiguous the moment two deployments disagree about local time.
--   * created_at / updated_at on every table, updated_at maintained by trigger rather than
--     by application code, so a row written by a migration or by psql is still correct.
--   * version columns only where optimistic locking is actually needed.

-- ---------------------------------------------------------------------------------------
-- Shared trigger: keeps updated_at honest regardless of who writes the row.
-- ---------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------------------
-- users: the authenticatable identity.
-- ---------------------------------------------------------------------------------------
CREATE TABLE users (
    id                    UUID         PRIMARY KEY,

    -- Stored lower-cased and uniquely constrained: two accounts differing only in case
    -- would let one person impersonate another's login identifier.
    email                 VARCHAR(320) NOT NULL,

    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified        BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Lockout state. Kept here rather than derived from login_attempts on every login:
    -- the check runs on the hot path and must not depend on scanning an append-only table.
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_email_shape CHECK (position('@' IN email) > 1),
    CONSTRAINT ck_users_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'DISABLED')
    ),
    CONSTRAINT ck_users_failed_attempts_not_negative CHECK (failed_login_attempts >= 0)
);

COMMENT ON TABLE users IS 'Authenticatable identities. Profile data lives in the user service.';
COMMENT ON COLUMN users.locked_until IS 'Set when repeated failures lock the account; NULL when not locked.';

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Administrative screens list by status; a partial index keeps the common "locked" query
-- cheap without carrying every row.
CREATE INDEX idx_users_status ON users (status);
CREATE INDEX idx_users_locked_until ON users (locked_until) WHERE locked_until IS NOT NULL;

-- ---------------------------------------------------------------------------------------
-- user_roles: authorization grants.
-- ---------------------------------------------------------------------------------------
-- Separate from users because a person can hold more than one: an administrator still has
-- their own wallet and therefore the USER role as well.
CREATE TABLE user_roles (
    user_id    UUID        NOT NULL,
    role       VARCHAR(32) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('USER', 'SUPPORT', 'ADMIN', 'AUDITOR'))
);

COMMENT ON TABLE user_roles IS 'Roles granted to a user. Composite primary key prevents duplicate grants.';

-- ---------------------------------------------------------------------------------------
-- credentials: the password, separated from the identity.
-- ---------------------------------------------------------------------------------------
-- A separate table so that reading a user never implicitly loads a password hash, and so
-- the algorithm can be recorded per credential and rehashed on next login when it changes.
CREATE TABLE credentials (
    id                  UUID         PRIMARY KEY,
    user_id             UUID         NOT NULL,

    password_hash       VARCHAR(255) NOT NULL,
    algorithm           VARCHAR(32)  NOT NULL DEFAULT 'BCRYPT',
    password_updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    must_change         BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    -- One credential per user: a second row would make "the password" ambiguous.
    CONSTRAINT uq_credentials_user UNIQUE (user_id),
    CONSTRAINT fk_credentials_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_credentials_algorithm CHECK (algorithm IN ('BCRYPT', 'ARGON2')),
    -- A plausible hash is long. This will not catch every mistake, but it does catch the
    -- worst one: a plaintext password written into this column.
    CONSTRAINT ck_credentials_hash_length CHECK (length(password_hash) >= 40)
);

COMMENT ON COLUMN credentials.algorithm IS 'Recorded so hashes can be upgraded in place when the algorithm changes.';

CREATE TRIGGER trg_credentials_updated_at
    BEFORE UPDATE ON credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------------------
-- refresh_tokens: rotating tokens with reuse detection.
-- ---------------------------------------------------------------------------------------
-- Only a hash of the token is stored. A leaked database must not hand an attacker working
-- refresh tokens, exactly as it must not hand them passwords.
--
-- family_id ties a chain of rotations together. If a token that has already been used is
-- presented again, the token was stolen, and the whole family is revoked rather than just
-- that one token.
CREATE TABLE refresh_tokens (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL,

    token_hash        VARCHAR(128) NOT NULL,
    family_id         UUID         NOT NULL,
    previous_token_id UUID,

    issued_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    used_at           TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revoked_reason    VARCHAR(64),

    -- Captured for the security audit trail, not for authorization decisions.
    ip_address        INET,
    user_agent        VARCHAR(255),

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_previous FOREIGN KEY (previous_token_id) REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT ck_refresh_tokens_expiry_after_issue CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_tokens_revoked_reason CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL) OR (revoked_at IS NOT NULL)
    )
);

COMMENT ON COLUMN refresh_tokens.token_hash IS 'Hash of the token. The token itself is never stored.';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Rotation chain; reuse of a spent token revokes the whole family.';

CREATE TRIGGER trg_refresh_tokens_updated_at
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- "All my active sessions" and "revoke this family" are the two queries that matter.
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL AND used_at IS NULL;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
-- Expired rows are deleted by a scheduled job; that job scans by expiry.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- ---------------------------------------------------------------------------------------
-- login_attempts: append-only record of every authentication attempt.
-- ---------------------------------------------------------------------------------------
-- Written for successes as well as failures, because "when did this account last sign in,
-- and from where" is the first question asked after a report of account takeover.
--
-- email is recorded as supplied rather than only as a user reference: attempts against an
-- address that does not exist are exactly the ones worth noticing, and they have no user.
CREATE TABLE login_attempts (
    id             UUID         PRIMARY KEY,
    user_id        UUID,
    email          VARCHAR(320) NOT NULL,

    successful     BOOLEAN      NOT NULL,
    failure_reason VARCHAR(64),

    ip_address     INET,
    user_agent     VARCHAR(255),

    attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The user may be deleted while the attempt history is retained.
    CONSTRAINT fk_login_attempts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_login_attempts_failure_reason CHECK (
        (successful = TRUE AND failure_reason IS NULL) OR (successful = FALSE)
    )
);

COMMENT ON TABLE login_attempts IS 'Append-only. Never updated, so it carries no updated_at or version.';

-- Lockout counts recent failures for one address; both queries read the newest rows first.
CREATE INDEX idx_login_attempts_email_time ON login_attempts (email, attempted_at DESC);
CREATE INDEX idx_login_attempts_user_time ON login_attempts (user_id, attempted_at DESC);
CREATE INDEX idx_login_attempts_failures ON login_attempts (email, attempted_at DESC)
    WHERE successful = FALSE;

-- ---------------------------------------------------------------------------------------
-- revoked_tokens: denylist for access tokens that have not yet expired.
-- ---------------------------------------------------------------------------------------
-- An access token is self-contained and valid until it expires, so logging out or
-- disabling an account cannot take one back. Its jti is recorded here until the moment it
-- would have expired anyway, after which the row is purged: the list stays small because
-- access tokens are short-lived.
CREATE TABLE revoked_tokens (
    id         UUID         PRIMARY KEY,
    jti        UUID         NOT NULL,
    user_id    UUID,

    reason     VARCHAR(64),
    revoked_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The original token expiry: after this, the row can be deleted.
    expires_at TIMESTAMPTZ  NOT NULL,

    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_revoked_tokens_jti UNIQUE (jti),
    CONSTRAINT fk_revoked_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

COMMENT ON TABLE revoked_tokens IS 'Short-lived denylist of access token ids, purged once each token would have expired.';

CREATE INDEX idx_revoked_tokens_expires_at ON revoked_tokens (expires_at);
CREATE INDEX idx_revoked_tokens_user ON revoked_tokens (user_id);
