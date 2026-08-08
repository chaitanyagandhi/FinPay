package com.finpay.user.exception;

import java.io.Serial;

import com.finpay.platform.web.error.FinPayException;

/**
 * Raised when a timezone is not one the JVM recognises.
 *
 * <p>Checked rather than trusted, because the value decides when a statement period ends and when a
 * notification is sent. An unrecognised zone stored now becomes an exception in a scheduled job
 * later, a long way from the request that caused it.
 */
public class UnknownTimezoneException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnknownTimezoneException(String timezone) {
        super(UserErrorCode.UNKNOWN_TIMEZONE, "'%s' is not a recognised time zone.".formatted(timezone));
    }
}
