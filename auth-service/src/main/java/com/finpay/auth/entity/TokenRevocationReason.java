package com.finpay.auth.entity;

/**
 * Why a token stopped being accepted before it expired.
 *
 * <p>Stored for the operator, never returned to the caller. A client presenting a revoked token is
 * told only that it was not accepted - telling them <em>that reuse was detected</em> would confirm
 * to whoever stole the token that the theft was noticed, and would tell them the stolen token was
 * genuine.
 */
public enum TokenRevocationReason {

    /** The session was ended deliberately. */
    LOGOUT,

    /**
     * A refresh token was presented after it had already been exchanged.
     *
     * <p>A well-behaved client never does this: it holds exactly one refresh token and replaces it
     * on every rotation. A second presentation means two parties hold the same token, which means
     * one of them should not.
     */
    REUSE_DETECTED
}
