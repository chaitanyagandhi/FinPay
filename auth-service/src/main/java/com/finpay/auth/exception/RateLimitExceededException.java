package com.finpay.auth.exception;

import java.io.Serial;
import java.time.Duration;

import com.finpay.platform.web.error.FinPayException;
import com.finpay.platform.web.error.PlatformErrorCode;

/**
 * Raised when a caller has used up its allowance for an endpoint.
 *
 * <p>Carries the platform's {@code RATE_LIMITED} code rather than one of this service's own: being
 * throttled is a protocol-level fact that means the same thing everywhere, and a client's response
 * to it - wait and retry - does not depend on which endpoint refused.
 *
 * <p>The message is deliberately free of specifics. Telling a caller their exact remaining budget
 * or how many attempts they have made would help someone tune an attack to stay just underneath.
 */
public class RateLimitExceededException extends FinPayException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(PlatformErrorCode.RATE_LIMITED, "Too many requests. Please retry later.");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
