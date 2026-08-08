package com.finpay.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A partial update to the caller's own profile.
 *
 * <p>Every field is optional and null means "leave this alone" - the difference between a PATCH and
 * a PUT. A client that sends only a display name must not thereby erase the phone number it did not
 * mention.
 *
 * <p>There is no {@code userId}: the profile updated is always the caller's, taken from the
 * authenticated identity. Accepting one from the body would let anyone edit anybody.
 *
 * <p>Values are trimmed in the compact constructor, which runs during deserialisation, so
 * validation and the service both see the trimmed value. A copy-pasted phone number with a
 * trailing space would otherwise fail the pattern before any service code could normalise it.
 */
public record UpdateProfileRequest(
        @Size(max = 80, message = "must be at most 80 characters")
                @Pattern(regexp = "^\\S.*$|^$", message = "must not start with whitespace")
                @Schema(example = "ada")
                String displayName,
        @Size(max = 80, message = "must be at most 80 characters") String firstName,
        @Size(max = 80, message = "must be at most 80 characters") String lastName,
        // E.164. The database enforces the same shape, so a value that slipped past here would be
        // refused there rather than stored in a second spelling.
        @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "must be in E.164 format, e.g. +441632960961")
                @Schema(example = "+441632960961")
                String phoneNumber,
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be a two-letter ISO 3166-1 country code")
                @Schema(example = "GB")
                String countryCode,
        @Size(max = 64, message = "must be at most 64 characters") @Schema(example = "Europe/London") String timezone,
        @Size(max = 512, message = "must be at most 512 characters") String avatarUrl) {

    public UpdateProfileRequest {
        displayName = trim(displayName);
        firstName = trim(firstName);
        lastName = trim(lastName);
        phoneNumber = trim(phoneNumber);
        countryCode = countryCode == null ? null : countryCode.trim().toUpperCase();
        timezone = trim(timezone);
        avatarUrl = trim(avatarUrl);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
