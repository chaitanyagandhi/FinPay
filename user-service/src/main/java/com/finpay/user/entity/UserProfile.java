package com.finpay.user.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The person behind an account.
 *
 * <p>Holds no email and no password. Those identify and authenticate, which is auth-service's job;
 * a second copy here would be a second answer to "who is this", and the two diverge the first time
 * either is updated alone.
 *
 * <p>The primary key <em>is</em> the auth-service user id. A separate profile id would introduce a
 * second identifier for one person and a translation step between them, answering no question the
 * first one cannot.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** What other users see. Null until the person chooses one. */
    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    /** E.164, so one number has exactly one spelling and a lookup is an equality test. */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    /**
     * The user's own day boundaries, for statements and for when a notification is sent. The
     * ledger stays in UTC regardless.
     */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

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

    /**
     * Optimistic locking. Null on a new instance, which is also how Hibernate recognises that an
     * entity with an application-assigned id needs an insert rather than a select.
     */
    @Version
    @Column(name = "version")
    private Long version;

    protected UserProfile() {
        // for JPA
    }

    private UserProfile(UUID userId) {
        this.userId = userId;
    }

    /** An empty profile for an account that has one but has not filled anything in. */
    public static UserProfile forUser(UUID userId) {
        return new UserProfile(userId);
    }

    /**
     * Applies a partial update.
     *
     * <p>Null means "leave this alone", which is what makes the API a PATCH rather than a PUT.
     * Clearing a field is a separate intent and would need its own representation; silently
     * treating an omitted field as a deletion is how a client that sends one field wipes the rest.
     */
    public void apply(
            String displayName,
            String firstName,
            String lastName,
            String phoneNumber,
            String countryCode,
            String timezone,
            String avatarUrl) {

        if (displayName != null) {
            this.displayName = displayName;
        }
        if (firstName != null) {
            this.firstName = firstName;
        }
        if (lastName != null) {
            this.lastName = lastName;
        }
        if (phoneNumber != null) {
            this.phoneNumber = phoneNumber;
        }
        if (countryCode != null) {
            this.countryCode = countryCode;
        }
        if (timezone != null) {
            this.timezone = timezone;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getAvatarUrl() {
        return avatarUrl;
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
        return other instanceof UserProfile profile && userId != null && userId.equals(profile.userId);
    }

    @Override
    public int hashCode() {
        return UserProfile.class.hashCode();
    }

    /** Never includes name or phone number: this string reaches logs, and both are personal data. */
    @Override
    public String toString() {
        return "UserProfile[userId=%s]".formatted(userId);
    }
}
