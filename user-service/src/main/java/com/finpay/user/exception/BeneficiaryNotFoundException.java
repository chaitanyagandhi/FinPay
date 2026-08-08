package com.finpay.user.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised when a payee cannot be saved or a saved entry cannot be found.
 *
 * <p>Also raised when the entry belongs to another owner. The caller cannot tell the difference,
 * which is deliberate: distinguishing "does not exist" from "is not yours" would let anyone probe
 * for other people's saved payees one id at a time.
 */
public class BeneficiaryNotFoundException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BeneficiaryNotFoundException() {
        super(UserErrorCode.BENEFICIARY_NOT_FOUND, "No such payee.");
    }
}
