package com.finpay.auth.entity;

/**
 * Authorization grant held by a user.
 *
 * <p>A person can hold more than one: an administrator still has their own wallet, and therefore
 * {@link #USER} as well.
 */
public enum Role {

    /** Owns a wallet and can transact with it. The role every registration receives. */
    USER,

    /** Can view limited customer data for support purposes. */
    SUPPORT,

    /** Can freeze wallets and review flagged transactions. */
    ADMIN,

    /** Can read audit records but cannot modify any wallet. */
    AUDITOR
}
