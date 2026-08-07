package com.finpay.auth.entity;

/**
 * Lifecycle state of an authenticatable identity.
 *
 * <p>Persisted as its name, never its ordinal: an ordinal silently changes meaning the moment a
 * constant is inserted, and this column is checked by a database constraint that names the values.
 */
public enum UserStatus {

    /** Registered but the email address has not been confirmed. */
    PENDING_VERIFICATION,

    /** Able to sign in and transact. */
    ACTIVE,

    /** Temporarily barred after repeated failed sign-in attempts. */
    LOCKED,

    /** Permanently disabled by an administrator or by the account holder. */
    DISABLED
}
