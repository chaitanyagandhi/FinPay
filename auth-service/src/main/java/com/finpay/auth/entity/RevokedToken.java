package com.finpay.auth.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An access token withdrawn before its own expiry.
 *
 * <p>An access token is self-contained: a verifier checks the signature and the expiry and needs
 * nothing else, which is exactly what makes it fast and exactly what makes it impossible to take
 * back. Recording the {@code jti} here is the only way a signed-out token stops being accepted
 * before it would have lapsed anyway.
 *
 * <p>The row carries the original expiry so it can be deleted once the token would have died on
 * its own. The denylist therefore stays small in proportion to the access token TTL, not to the
 * number of logouts ever performed.
 *
 * <p>Append-only, like {@code LoginAttempt}: no {@code updated_at}, no version.
 */
@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The {@code jti} claim of the withdrawn token. Unique: revoking twice is not an error. */
    @Column(name = "jti", nullable = false, updatable = false)
    private UUID jti;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 64)
    private TokenRevocationReason reason;

    @Column(name = "revoked_at", insertable = false, updatable = false)
    private Instant revokedAt;

    /** When the token would have expired on its own; after this the row is purgeable. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected RevokedToken() {
        // for JPA
    }

    public RevokedToken(UUID jti, UUID userId, TokenRevocationReason reason, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.jti = jti;
        this.userId = userId;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJti() {
        return jti;
    }

    public UUID getUserId() {
        return userId;
    }

    public TokenRevocationReason getReason() {
        return reason;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "RevokedToken[jti=%s, reason=%s]".formatted(jti, reason);
    }
}
