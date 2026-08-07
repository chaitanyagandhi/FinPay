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
 * A record of one attempt to sign in, successful or not.
 *
 * <p>Written for successes as well as failures, because "when did this account last sign in, and
 * from where" is the first question asked after a report of account takeover.
 *
 * <p>Append-only: never updated, so it carries no {@code updated_at} and no version. The user is
 * referenced by raw id rather than by a JPA association - the row outlives the account it refers
 * to, and loading a User to write a log line would be wasted work.
 */
@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null when the address had no account, or once the account is deleted. */
    @Column(name = "user_id")
    private UUID userId;

    /**
     * The address as supplied, normalised. Recorded even when no account matches: attempts
     * against addresses that do not exist are the ones most worth noticing.
     */
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "successful", nullable = false)
    private boolean successful;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 64)
    private LoginFailureReason failureReason;

    /**
     * Captured for the audit trail, never used to make an authorization decision.
     *
     * <p>The column is {@code inet} rather than text: PostgreSQL validates the value, and the
     * fraud service will later want subnet comparisons that a string cannot answer. Hibernate
     * binds a String as varchar, which PostgreSQL refuses without a cast, so the cast is stated
     * here rather than weakening the column to text.
     */
    @Column(name = "ip_address", columnDefinition = "inet")
    @ColumnTransformer(write = "cast(? as inet)")
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "attempted_at", insertable = false, updatable = false)
    private Instant attemptedAt;

    protected LoginAttempt() {
        // for JPA
    }

    private LoginAttempt(
            UUID userId,
            String email,
            boolean successful,
            LoginFailureReason failureReason,
            String ipAddress,
            String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.email = email;
        this.successful = successful;
        this.failureReason = failureReason;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public static LoginAttempt success(UUID userId, String email, String ipAddress, String userAgent) {
        return new LoginAttempt(userId, email, true, null, ipAddress, userAgent);
    }

    /**
     * @param userId null when no account matched the address
     */
    public static LoginAttempt failure(
            UUID userId, String email, LoginFailureReason reason, String ipAddress, String userAgent) {
        return new LoginAttempt(userId, email, false, reason, ipAddress, userAgent);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public LoginFailureReason getFailureReason() {
        return failureReason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    @Override
    public String toString() {
        return "LoginAttempt[id=%s, successful=%s, reason=%s]".formatted(id, successful, failureReason);
    }
}
