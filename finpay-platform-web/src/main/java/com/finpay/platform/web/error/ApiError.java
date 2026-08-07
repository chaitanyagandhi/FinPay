package com.finpay.platform.web.error;

import java.time.Instant;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error shape every FinPay service returns.
 *
 * <p>One envelope across the platform means a client writes error handling once, and a failure that
 * crosses three services still reads the same at the edge as it did at its origin.
 *
 * @param timestamp when the failure was rendered, in UTC
 * @param status HTTP status code
 * @param error HTTP reason phrase, for readability
 * @param code stable machine-readable code clients may branch on, e.g. {@code INSUFFICIENT_FUNDS}
 * @param message human-readable explanation that is safe to show a caller
 * @param path request path that failed
 * @param requestId identifier tying this response to its log entries
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp, int status, String error, String code, String message, String path, String requestId) {

    /**
     * Builds an envelope for the given status.
     *
     * <p>{@code message} must already be safe to expose. Exception text, SQL fragments and stack
     * traces describe internals to a caller and are filtered out before reaching here.
     */
    public static ApiError of(HttpStatus status, String code, String message, String path, String requestId) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code, message, path, requestId);
    }
}
