package com.finpay.auth.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised for every failed sign-in, whatever the actual cause.
 *
 * <p>One exception and one message for a missing account, a wrong password, a locked account and a
 * disabled one. Distinguishing them would let anyone with a list of addresses discover which have
 * accounts here, and which of those are merely locked rather than non-existent.
 *
 * <p>The real reason is recorded against the attempt, where an operator can see it and a caller
 * cannot.
 */
public class InvalidCredentialsException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidCredentialsException() {
        super(AuthErrorCode.INVALID_CREDENTIALS, "The email address or password is incorrect.");
    }
}
