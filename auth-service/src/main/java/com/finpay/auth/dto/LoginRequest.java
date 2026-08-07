package com.finpay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Credentials presented at sign-in.
 *
 * <p>Validation here is deliberately loose compared with registration: only presence and a sane
 * upper bound. Applying the registration password rules would tell an attacker which stored
 * passwords could not possibly be valid, and would lock out anyone whose password predates a
 * policy change.
 *
 * @param email the sign-in address, matched case-insensitively
 * @param password the plaintext password, compared against the stored hash and never logged
 */
public record LoginRequest(
        @NotBlank(message = "must not be blank")
                @Size(max = 320, message = "must be at most 320 characters")
                @Schema(example = "ada@finpay.test")
                String email,
        @NotBlank(message = "must not be blank")
                @Size(max = 72, message = "must be at most 72 characters")
                @Schema(example = "correct-horse-battery-staple")
                String password) {

    public LoginRequest {
        email = email == null ? null : email.trim();
    }
}
