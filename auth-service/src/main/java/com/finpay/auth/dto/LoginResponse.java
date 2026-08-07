package com.finpay.auth.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A successful sign-in.
 *
 * <p>Carries the access token and when it stops working, so a client can refresh ahead of expiry
 * rather than discovering it mid-request. The refresh token is added in the next step.
 *
 * @param accessToken a signed JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}
 * @param expiresAt when the token stops being accepted
 * @param expiresInSeconds the same thing as a duration, which is what most clients actually use
 */
public record LoginResponse(
        @Schema(description = "Send as: Authorization: Bearer <token>") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        Instant expiresAt,
        @Schema(example = "900") long expiresInSeconds) {

    public static final String BEARER = "Bearer";

    public static LoginResponse bearer(String accessToken, Instant issuedAt, Instant expiresAt) {
        return new LoginResponse(
                accessToken,
                BEARER,
                expiresAt,
                java.time.Duration.between(issuedAt, expiresAt).toSeconds());
    }
}
