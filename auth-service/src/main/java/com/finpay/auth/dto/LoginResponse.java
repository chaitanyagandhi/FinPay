package com.finpay.auth.dto;

import java.time.Duration;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A token pair, returned by both sign-in and refresh.
 *
 * <p>Carries when the access token stops working, so a client can refresh ahead of expiry rather
 * than discovering it mid-request.
 *
 * <p>The refresh token is returned on every exchange because it changes on every exchange: a
 * client that keeps using the value it was given at sign-in will be treated as having replayed a
 * spent token, and will lose the session. Rotation is the mechanism that makes theft detectable,
 * so there is no mode in which the same refresh token comes back twice.
 *
 * @param accessToken a signed JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}
 * @param expiresAt when the access token stops being accepted
 * @param expiresInSeconds the same thing as a duration, which is what most clients actually use
 * @param refreshToken an opaque value, valid once, to exchange for the next pair
 * @param refreshExpiresAt when the refresh token can no longer be exchanged
 */
public record LoginResponse(
        @Schema(description = "Send as: Authorization: Bearer <token>") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        Instant expiresAt,
        @Schema(example = "900") long expiresInSeconds,
        @Schema(description = "Valid once. Replaced on every refresh.") String refreshToken,
        Instant refreshExpiresAt) {

    public static final String BEARER = "Bearer";

    public static LoginResponse bearer(
            String accessToken, Instant issuedAt, Instant expiresAt, String refreshToken, Instant refreshExpiresAt) {
        return new LoginResponse(
                accessToken,
                BEARER,
                expiresAt,
                Duration.between(issuedAt, expiresAt).toSeconds(),
                refreshToken,
                refreshExpiresAt);
    }
}
