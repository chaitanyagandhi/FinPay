package com.finpay.platform.web.error;

import org.springframework.http.HttpStatus;

/**
 * A stable, machine-readable reason for a failure.
 *
 * <p>Clients branch on {@link #code()}, so a code is part of the public API: it may be added, but
 * renaming or repurposing one breaks callers.
 *
 * <p>Each service declares its own codes as an enum implementing this interface, because what can go
 * wrong is domain knowledge that belongs to the service that owns it. {@link PlatformErrorCode}
 * carries only the protocol-level failures that are identical everywhere.
 */
public interface ErrorCode {

    /** Stable identifier, conventionally SCREAMING_SNAKE_CASE, e.g. {@code INSUFFICIENT_FUNDS}. */
    String code();

    /** HTTP status this failure maps to. */
    HttpStatus httpStatus();
}
