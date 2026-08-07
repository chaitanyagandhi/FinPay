package com.finpay.auth.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;

/**
 * One link in a rotation chain.
 *
 * <p>Only a hash of the token is stored, for the same reason passwords are hashed: a leaked
 * database must not hand an attacker working credentials. Unlike a password the value here is
 * generated rather than chosen, which changes what hashing it should cost - see {@code
 * RefreshTokenService}.
 *
 * <p>A row is spent by setting {@code usedAt}, not by deleting it. The spent row is what makes
 * reuse detectable: a token presented twice is either a client bug or a stolen token, and the
 * second presentation is the only chance to notice.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** SHA-256 of the token, hex encoded. The token itself is never stored anywhere. */
    @Column(name = "token_hash", nullable = false, length = 128, updatable = false)
    private String tokenHash;

    /**
     * Ties every rotation of one session together.
     *
     * <p>Constant for the life of the chain, so revoking a session means revoking a family rather
     * than walking {@code previousTokenId} backwards and hoping not to miss a link.
     */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** The token this one replaced; null for the first token in a family. */
    @Column(name = "previous_token_id", updatable = false)
    private UUID previousTokenId;

    @Column(name = "issued_at", insertable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** When this token was exchanged for its successor. Null while it is still spendable. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 64)
    private TokenRevocationReason revokedReason;

    /**
     * Captured for the audit trail, never used to authorise anything.
     *
     * <p>The column is {@code inet}; Hibernate binds a String as varchar, which PostgreSQL refuses
     * without an explicit cast.
     */
    @Column(name = "ip_address", columnDefinition = "inet")
    @ColumnTransformer(write = "cast(? as inet)")
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected RefreshToken() {
        // for JPA
    }

    private RefreshToken(
            UUID userId,
            String tokenHash,
            UUID familyId,
            UUID previousTokenId,
            Instant expiresAt,
            String ipAddress,
            String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.previousTokenId = previousTokenId;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    /**
     * The first token of a new family, created when a user signs in.
     *
     * <p>A new family per sign-in is what keeps sessions independent: revoking the session started
     * on a lost phone must not sign the same person out of their laptop.
     */
    public static RefreshToken startFamily(
            UUID userId, String tokenHash, Instant expiresAt, String ipAddress, String userAgent) {
        return new RefreshToken(userId, tokenHash, UUID.randomUUID(), null, expiresAt, ipAddress, userAgent);
    }

    /** The successor of a token that has just been spent, in the same family. */
    public RefreshToken successor(String tokenHash, Instant expiresAt, String ipAddress, String userAgent) {
        return new RefreshToken(userId, tokenHash, familyId, id, expiresAt, ipAddress, userAgent);
    }

    /** Whether this token can still be exchanged at the given instant. */
    public boolean isSpendableAt(Instant at) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(at);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public UUID getPreviousTokenId() {
        return previousTokenId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public TokenRevocationReason getRevokedReason() {
        return revokedReason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RefreshToken token && id != null && id.equals(token.id);
    }

    @Override
    public int hashCode() {
        return RefreshToken.class.hashCode();
    }

    /** Never includes the hash: this string reaches logs, and the hash is the lookup key. */
    @Override
    public String toString() {
        return "RefreshToken[id=%s, family=%s, used=%s, revoked=%s]".formatted(id, familyId, usedAt != null, revokedAt);
    }
}
