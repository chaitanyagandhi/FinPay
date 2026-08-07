package com.finpay.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A request to create an account.
 *
 * <p>A record, so it is immutable and carries exactly the fields the API accepts. Anything else in
 * the body is ignored rather than bound, which is what stops a caller setting a field the endpoint
 * never meant to expose.
 *
 * @param email the sign-in address; normalised to lower case before it is stored
 * @param password the plaintext password, hashed immediately and never persisted or logged
 */
public record RegistrationRequest(
        @NotBlank(message = "must not be blank")
                @Email(message = "must be a valid email address")
                @Size(max = 320, message = "must be at most 320 characters")
                @Schema(example = "ada@finpay.test")
                String email,
        @NotBlank(message = "must not be blank")
                @Size(
                        min = MIN_PASSWORD_LENGTH,
                        max = MAX_PASSWORD_LENGTH,
                        message = "must be between 12 and 72 characters")
                @Schema(
                        description = "Between 12 and 72 characters. The upper bound is BCrypt's, not a policy choice.",
                        example = "correct-horse-battery-staple")
                String password) {

    /**
     * Trims the address before anything else sees it.
     *
     * <p>This runs during deserialisation, so validation and the service both receive the trimmed
     * value. Without it, {@code @Email} rejects an address with a stray leading or trailing space —
     * which is a copy-paste artefact, not a malformed address, and a 400 for it would be a poor
     * answer to a correct email.
     *
     * <p>Only whitespace is removed. Lower-casing stays in the service, where it belongs with the
     * uniqueness rule it exists to support.
     */
    public RegistrationRequest {
        email = email == null ? null : email.trim();
    }

    /** Long enough that an offline attack on a stolen hash is not trivial. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * BCrypt hashes only the first 72 bytes and silently ignores the rest.
     *
     * <p>Accepting longer passwords would mean two different passwords authenticating the same
     * account whenever they share a 72-byte prefix — a real vulnerability that looks like generous
     * input handling. Rejecting them is the honest behaviour.
     */
    public static final int MAX_PASSWORD_LENGTH = 72;
}
