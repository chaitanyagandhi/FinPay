package com.finpay.platform.web.identity;

/**
 * The header names the gateway writes and every service reads.
 *
 * <p>Stated once, here, because both halves of the contract must agree exactly. A mismatch does not
 * produce an error anywhere: the gateway keeps writing a header nobody reads, the service keeps
 * seeing an unauthenticated caller, and the failure only shows up as an authorization decision made
 * on missing information.
 */
public final class IdentityHeaders {

    /** The authenticated subject, as a UUID string. */
    public static final String USER_ID = "X-User-Id";

    /** Comma-separated role names. */
    public static final String USER_ROLES = "X-User-Roles";

    /** The access token's {@code jti}, so one session can be followed across services. */
    public static final String TOKEN_ID = "X-Token-Id";

    private IdentityHeaders() {}
}
