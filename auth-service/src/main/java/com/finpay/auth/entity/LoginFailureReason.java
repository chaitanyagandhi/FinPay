package com.finpay.auth.entity;

/**
 * Why a sign-in attempt failed.
 *
 * <p>Recorded in the attempt history, never returned to the caller. Telling someone that an
 * address exists but the password was wrong - as opposed to the address not existing at all -
 * hands them half the credential for free.
 */
public enum LoginFailureReason {

    /** No account for the address supplied. */
    USER_NOT_FOUND,

    /** The account exists and the password did not match. */
    BAD_PASSWORD,

    /** The account is temporarily barred after repeated failures. */
    ACCOUNT_LOCKED,

    /** The account has been disabled. */
    ACCOUNT_DISABLED
}
