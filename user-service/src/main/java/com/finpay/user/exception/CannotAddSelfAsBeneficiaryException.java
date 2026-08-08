package com.finpay.user.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised when an owner tries to save themselves.
 *
 * <p>Paying yourself is not a transfer. Allowing it would put a movement through the ledger that
 * nets to nothing and still has to be reconciled, so it is refused at the point where somebody
 * could first ask for it.
 */
public class CannotAddSelfAsBeneficiaryException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CannotAddSelfAsBeneficiaryException() {
        super(UserErrorCode.CANNOT_ADD_SELF, "You cannot save yourself as a payee.");
    }
}
