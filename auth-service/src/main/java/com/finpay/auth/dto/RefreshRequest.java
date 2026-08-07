package com.finpay.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A refresh token offered in exchange for a new pair.
 *
 * <p>Carried in the body rather than in a header or a query parameter: query strings end up in
 * access logs, browser history and referrer headers, and this value is a credential.
 *
 * @param refreshToken the token issued by the most recent sign-in or refresh
 */
public record RefreshRequest(
        @NotBlank(message = "must not be blank")
                @Size(max = 512, message = "must be at most 512 characters")
                @Schema(description = "The refresh token returned by the previous sign-in or refresh")
                String refreshToken) {

    public RefreshRequest {
        refreshToken = refreshToken == null ? null : refreshToken.trim();
    }
}
