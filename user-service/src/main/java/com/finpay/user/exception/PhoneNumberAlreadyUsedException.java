package com.finpay.user.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised when a phone number is already on another profile.
 *
 * <p>The number is deliberately not repeated back. Echoing it would confirm to whoever submitted it
 * that the number belongs to a FinPay account, which is exactly the question someone probing a list
 * of numbers wants answered.
 */
public class PhoneNumberAlreadyUsedException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PhoneNumberAlreadyUsedException() {
        super(UserErrorCode.PHONE_NUMBER_ALREADY_USED, "That phone number is already in use.");
    }
}
