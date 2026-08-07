package com.finpay.auth.dto;

import java.util.UUID;

import com.finpay.auth.entity.User;
import com.finpay.auth.entity.UserStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a caller receives after registering.
 *
 * <p>Carries no password, no hash and no token. Registration creates an account; it does not sign
 * anyone in. A client that wants a session calls the login endpoint next.
 *
 * @param userId the account's public identifier
 * @param email the normalised address the account was created with
 * @param status always {@code PENDING_VERIFICATION} at this point
 */
public record RegistrationResponse(
        @Schema(example = "0d5a1f6c-1c2b-4b1e-9f6a-1a2b3c4d5e6f") UUID userId,
        @Schema(example = "ada@finpay.test") String email,
        UserStatus status) {

    public static RegistrationResponse from(User user) {
        return new RegistrationResponse(user.getId(), user.getEmail(), user.getStatus());
    }
}
