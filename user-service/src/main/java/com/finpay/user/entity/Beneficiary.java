package com.finpay.user.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Somebody a user has saved in order to pay them.
 *
 * <p>Both ids are auth-service user ids. The owner is whoever saved the entry; the beneficiary must
 * have a profile in this service, which the schema enforces - a payment confirmation has to be able
 * to show who is about to be paid.
 */
@Entity
@Table(name = "beneficiaries")
public class Beneficiary {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "beneficiary_user_id", nullable = false, updatable = false)
    private UUID beneficiaryUserId;

    /**
     * The payee's profile, mapped read-only over the same column.
     *
     * <p>Listing payees needs each one's name, and fetching them one query at a time would be an
     * N+1 on the screen a user opens most often. The association exists so the list query can join
     * and fetch in one statement; {@code beneficiaryUserId} above stays the writable side, so
     * there is exactly one place the column is set.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_user_id", insertable = false, updatable = false)
    private UserProfile beneficiaryProfile;

    /** What the owner calls them, which need not be the name anyone else sees. */
    @Column(name = "nickname", length = 80)
    private String nickname;

    /**
     * Written by the database default, so Hibernate has to read it back: without this the value
     * returned to a caller is null for a column that is populated.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by trigger on every write, so it is re-read after both insert and update. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    protected Beneficiary() {
        // for JPA
    }

    private Beneficiary(UUID ownerUserId, UUID beneficiaryUserId, String nickname) {
        this.id = UUID.randomUUID();
        this.ownerUserId = ownerUserId;
        this.beneficiaryUserId = beneficiaryUserId;
        this.nickname = nickname;
    }

    /**
     * Saves a payee for an owner.
     *
     * <p>Does not check that the two differ or that the payee exists. Both are refused by the
     * database, and the service turns those refusals into answers a caller can act on - the point
     * being that the rule holds even if a future caller forgets to ask.
     */
    public static Beneficiary save(UUID ownerUserId, UUID beneficiaryUserId, String nickname) {
        return new Beneficiary(ownerUserId, beneficiaryUserId, nickname);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getBeneficiaryUserId() {
        return beneficiaryUserId;
    }

    public UserProfile getBeneficiaryProfile() {
        return beneficiaryProfile;
    }

    public String getNickname() {
        return nickname;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Beneficiary beneficiary && id != null && id.equals(beneficiary.id);
    }

    @Override
    public int hashCode() {
        return Beneficiary.class.hashCode();
    }

    /** Never includes the nickname: this string reaches logs, and a nickname names a person. */
    @Override
    public String toString() {
        return "Beneficiary[id=%s, owner=%s]".formatted(id, ownerUserId);
    }
}
