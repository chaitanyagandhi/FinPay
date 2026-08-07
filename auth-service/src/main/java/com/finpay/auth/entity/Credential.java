package com.finpay.auth.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A user's password, kept apart from their identity.
 *
 * <p>Separate from {@link User} for two reasons. Reading a user never implicitly loads a password
 * hash, so it cannot end up somewhere it should not be. And the hashing algorithm is recorded per
 * credential, so when it changes, existing hashes can be upgraded on the owner's next successful
 * sign-in instead of forcing a platform-wide reset.
 */
@Entity
@Table(name = "credentials")
public class Credential {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** One credential per user, enforced by a unique constraint on the column. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * The hash, never the password.
     *
     * <p>The database additionally refuses anything shorter than 40 characters, which will not
     * catch every mistake but does catch the worst one: a plaintext password written here.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "algorithm", nullable = false, length = 32)
    private String algorithm;

    @Column(name = "password_updated_at", insertable = false, updatable = false)
    private Instant passwordUpdatedAt;

    /** Set when an administrator forces a reset; unused until password management lands. */
    @Column(name = "must_change", nullable = false)
    private boolean mustChange;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    protected Credential() {
        // for JPA
    }

    private Credential(UUID id, User user, String passwordHash, String algorithm) {
        this.id = id;
        this.user = user;
        this.passwordHash = passwordHash;
        this.algorithm = algorithm;
    }

    /**
     * Creates a credential for a user.
     *
     * @param passwordHash an already-hashed password; this class never hashes, so there is no path
     *     by which a plaintext value could be stored through it by mistake
     */
    public static Credential forUser(User user, String passwordHash, String algorithm) {
        return new Credential(UUID.randomUUID(), user, passwordHash, algorithm);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public boolean isMustChange() {
        return mustChange;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Credential credential && id != null && id.equals(credential.id);
    }

    @Override
    public int hashCode() {
        return Credential.class.hashCode();
    }

    /** Never includes the hash. */
    @Override
    public String toString() {
        return "Credential[id=%s, algorithm=%s]".formatted(id, algorithm);
    }
}
