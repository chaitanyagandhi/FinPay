-- User service baseline schema.
--
-- This service owns what describes a person: their name, contact details, preferences, who they
-- pay, and the state of their identity checks. It deliberately does NOT own the email address or
-- the password - those are authentication concerns and belong to auth-service. Two tables holding
-- the same email is two answers to the question "who is this", and they diverge the first time one
-- of them is updated alone.
--
-- user_id is the same UUID auth-service assigned. There is no foreign key to it, and there cannot
-- be: it lives in another database, owned by another service, which is the point of
-- database-per-service. The gateway guarantees the id belongs to an authenticated account before
-- any request reaches here.
--
-- Conventions, as established in the auth schema:
--   * UUID primary keys; identifiers appear in URLs and must not leak row counts.
--   * timestamptz everywhere, created_at/updated_at on every table, updated_at by trigger.
--   * version only where optimistic locking is genuinely needed.
--   * Named constraints and indexes; partial indexes where the hot query has a predicate.

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------------------
-- user_profiles: the person behind the account.
-- ---------------------------------------------------------------------------------------
CREATE TABLE user_profiles (
    -- The auth-service user id, used directly as the primary key. A separate profile id would
    -- add a second identifier for the same person and a join to translate between them, with
    -- no question that only the second one can answer.
    user_id       UUID         PRIMARY KEY,

    display_name  VARCHAR(80),
    first_name    VARCHAR(80),
    last_name     VARCHAR(80),

    -- Stored in E.164 so that two spellings of one number cannot become two accounts, and so a
    -- lookup by number is an equality test rather than a normalisation exercise.
    phone_number  VARCHAR(20),

    -- varchar, not char(2): PostgreSQL's char is bpchar, which blank-pads and which
    -- Hibernate maps as varchar, so ddl-auto=validate refuses to start against it. The CHECK
    -- below is what actually enforces the format, so the fixed width bought nothing.
    country_code  VARCHAR(2),
    -- IANA zone name, e.g. Europe/London. Statements and notification timing need the user's
    -- own day boundaries; the ledger itself stays in UTC.
    timezone      VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    avatar_url    VARCHAR(512),

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,

    -- One profile per phone number: a payee search that matched two people would let one be paid
    -- in mistake for the other.
    CONSTRAINT uq_user_profiles_phone UNIQUE (phone_number),
    CONSTRAINT ck_user_profiles_phone_e164 CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_user_profiles_country_code CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_user_profiles_display_name_not_blank CHECK (display_name IS NULL OR length(btrim(display_name)) > 0)
);

COMMENT ON TABLE user_profiles IS 'Personal details. The email address and password belong to auth-service.';
COMMENT ON COLUMN user_profiles.user_id IS 'The auth-service user id. No FK: that table is in another database.';
COMMENT ON COLUMN user_profiles.phone_number IS 'E.164, so one number has exactly one spelling.';

CREATE TRIGGER trg_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Finding someone to pay is the hot query. Names are matched case-insensitively, so the index
-- is on the lower-cased value or it will not be used.
CREATE INDEX idx_user_profiles_display_name ON user_profiles (lower(display_name))
    WHERE display_name IS NOT NULL;

-- ---------------------------------------------------------------------------------------
-- user_preferences: how the person wants to be treated.
-- ---------------------------------------------------------------------------------------
-- Separate from the profile because these are written by different screens at different times,
-- and because a notification service reading preferences should not also load somebody's name.
CREATE TABLE user_preferences (
    user_id                UUID         PRIMARY KEY,

    -- Display only. Every amount is stored and moved in its own currency; this decides what the
    -- client shows by default and never what the ledger records.
    -- varchar for the same reason as user_profiles.country_code; the CHECK enforces shape.
    preferred_currency     VARCHAR(3)   NOT NULL DEFAULT 'USD',
    locale                 VARCHAR(16)  NOT NULL DEFAULT 'en-US',

    email_notifications    BOOLEAN      NOT NULL DEFAULT TRUE,
    push_notifications     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Off by default: sending someone messages they did not ask for is a decision they should
    -- make, not one they should have to undo.
    marketing_emails       BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_user_preferences_currency CHECK (preferred_currency ~ '^[A-Z]{3}$')
);

COMMENT ON COLUMN user_preferences.preferred_currency IS 'Display preference only; never what an amount is stored in.';

CREATE TRIGGER trg_user_preferences_updated_at
    BEFORE UPDATE ON user_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------------------
-- beneficiaries: the people a user can pay.
-- ---------------------------------------------------------------------------------------
-- A saved payee, always another FinPay account. Stored per owner, and the pair is unique: the
-- same person saved twice would appear twice in a payee list and make "which one did I pay"
-- ambiguous in exactly the place it must not be.
CREATE TABLE beneficiaries (
    id                  UUID         PRIMARY KEY,
    owner_user_id       UUID         NOT NULL,
    beneficiary_user_id UUID         NOT NULL,

    -- What the owner calls them, which need not be their display name.
    nickname            VARCHAR(80),

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_beneficiaries_owner_beneficiary UNIQUE (owner_user_id, beneficiary_user_id),
    -- Paying yourself is not a transfer; it is a no-op that still moves money through the
    -- ledger and can only ever be a mistake or a probe.
    CONSTRAINT ck_beneficiaries_not_self CHECK (owner_user_id <> beneficiary_user_id),
    CONSTRAINT ck_beneficiaries_nickname_not_blank CHECK (nickname IS NULL OR length(btrim(nickname)) > 0)
);

COMMENT ON TABLE beneficiaries IS 'Saved payees. Both ids are auth-service user ids.';

CREATE TRIGGER trg_beneficiaries_updated_at
    BEFORE UPDATE ON beneficiaries
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- "List my payees, newest first" is the only query this table serves.
CREATE INDEX idx_beneficiaries_owner ON beneficiaries (owner_user_id, created_at DESC);

-- ---------------------------------------------------------------------------------------
-- kyc_records: the state of a user's identity checks.
-- ---------------------------------------------------------------------------------------
-- Append-only history rather than a status column on the profile: "when did this account become
-- verified, and on what basis" is a question a regulator asks, and a mutable column cannot
-- answer it. The current state is the newest row.
--
-- Simulated throughout - no real identity documents are collected or stored, and the reference
-- is to an imaginary provider's case.
CREATE TABLE kyc_records (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,

    status          VARCHAR(32)  NOT NULL,
    level           VARCHAR(32)  NOT NULL DEFAULT 'BASIC',
    provider_ref    VARCHAR(128),
    -- Why a check failed, for support to read. Never returned to the user unedited.
    rejection_reason VARCHAR(255),

    submitted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_kyc_records_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_kyc_records_level CHECK (level IN ('BASIC', 'FULL')),
    -- A decision has a date; an undecided check does not. And a rejection has to say why.
    CONSTRAINT ck_kyc_records_decided CHECK (
        (status = 'PENDING' AND decided_at IS NULL) OR (status <> 'PENDING' AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_kyc_records_rejection_reason CHECK (
        (status = 'REJECTED' AND rejection_reason IS NOT NULL) OR (status <> 'REJECTED')
    )
);

COMMENT ON TABLE kyc_records IS 'Append-only history of identity checks. Simulated; no real documents are held.';

-- The current state is the newest row for a user, which is the only way this is ever read.
CREATE INDEX idx_kyc_records_user_time ON kyc_records (user_id, submitted_at DESC);
