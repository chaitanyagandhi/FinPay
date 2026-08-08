package com.finpay.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.finpay.user.entity.UserProfile;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A user's own profile, as returned to them.
 *
 * <p>Contains no email address: that belongs to auth-service, and a client that wants it should
 * ask the service that owns it rather than receive a copy that can go stale.
 *
 * @param userId the account this profile describes
 * @param updatedAt when the profile last changed; null until it has been filled in
 */
public record UserProfileResponse(
        UUID userId,
        @Schema(example = "ada") String displayName,
        String firstName,
        String lastName,
        @Schema(example = "+441632960961") String phoneNumber,
        @Schema(example = "GB") String countryCode,
        @Schema(example = "Europe/London") String timezone,
        String avatarUrl,
        Instant updatedAt) {

    public static UserProfileResponse of(UserProfile profile) {
        return new UserProfileResponse(
                profile.getUserId(),
                profile.getDisplayName(),
                profile.getFirstName(),
                profile.getLastName(),
                profile.getPhoneNumber(),
                profile.getCountryCode(),
                profile.getTimezone(),
                profile.getAvatarUrl(),
                profile.getUpdatedAt());
    }

    /**
     * What a signed-in user sees before they have filled anything in.
     *
     * <p>Returned rather than a 404: the account exists and the caller is authenticated, so
     * "not found" would be answering a different question from the one asked. Nothing is written
     * to the database by a request that only reads.
     */
    public static UserProfileResponse empty(UUID userId) {
        return new UserProfileResponse(userId, null, null, null, null, null, "UTC", null, null);
    }
}
