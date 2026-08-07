package com.finpay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The session to end.
 *
 * <p>Identified by its refresh token rather than by a user id, so that signing out ends one
 * session and not every session that user has. Ending all of them is a different operation with
 * different consequences, and conflating the two would make "sign out on this device" silently log
 * someone out everywhere.
 *
 * @param refreshToken the token whose family ends here
 */
public record LogoutRequest(
        @NotBlank(message = "must not be blank")
                @Size(max = 512, message = "must be at most 512 characters")
                @Schema(description = "The refresh token identifying the session to end")
                String refreshToken) {

    public LogoutRequest {
        refreshToken = refreshToken == null ? null : refreshToken.trim();
    }
}
