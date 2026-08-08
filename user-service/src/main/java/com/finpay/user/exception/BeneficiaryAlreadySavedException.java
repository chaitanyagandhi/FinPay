package com.finpay.user.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/** Raised when an owner saves a payee they have already saved. */
public class BeneficiaryAlreadySavedException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BeneficiaryAlreadySavedException() {
        super(UserErrorCode.BENEFICIARY_ALREADY_SAVED, "That payee is already saved.");
    }
}
