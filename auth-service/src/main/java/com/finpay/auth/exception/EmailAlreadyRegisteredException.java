package com.finpay.auth.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised when a registration names an address that already has an account.
 *
 * <p>The message is deliberately identical whatever the cause, and never echoes the address back.
 * Confirming which addresses are registered turns this endpoint into an account-enumeration
 * oracle, which is worth more to an attacker than it is to a legitimate caller who already knows
 * what they typed.
 */
public class EmailAlreadyRegisteredException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EmailAlreadyRegisteredException() {
        super(AuthErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email address already exists.");
    }

    public EmailAlreadyRegisteredException(Throwable cause) {
        super(AuthErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email address already exists.", cause);
    }
}
