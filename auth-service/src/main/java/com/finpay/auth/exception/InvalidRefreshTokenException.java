package com.finpay.auth.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised whenever a refresh token is not accepted, whatever the actual cause.
 *
 * <p>Unknown, expired, already spent, or revoked because its family was compromised all produce
 * this one exception with this one message. The distinction that matters most to keep hidden is
 * the last: an attacker who learns that presenting a stolen token triggered reuse detection learns
 * both that the token was real and that they have been noticed.
 *
 * <p>The real reason is logged and, where it indicates theft, recorded against the token family.
 */
public class InvalidRefreshTokenException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidRefreshTokenException() {
        super(AuthErrorCode.INVALID_REFRESH_TOKEN, "The refresh token is not valid.");
    }
}
