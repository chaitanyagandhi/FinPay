package com.finpay.platform.web.error;

import java.io.Serial;

/**
 * Base type for failures a FinPay service reports deliberately.
 *
 * <p>Carrying an {@link ErrorCode} is what separates these from an incidental
 * {@code RuntimeException}: the service has decided what went wrong, what the caller should be told,
 * and which status it maps to. Anything that reaches the error handler without one is treated as a
 * bug - logged with its stack trace and reported as an internal error, revealing nothing.
 *
 * <p>Services subclass this per domain failure rather than throwing it directly, so the type itself
 * says what happened at the throw site and in a stack trace.
 */
public class FinPayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    /**
     * @param errorCode what went wrong and the status it maps to
     * @param message explanation safe to return to the caller; it is sent verbatim, so it must
     *     never contain internal identifiers, SQL, or exception text
     */
    public FinPayException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @param cause the underlying failure; it is logged but never returned to the caller
     */
    public FinPayException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
