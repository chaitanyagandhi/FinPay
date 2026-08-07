package com.finpay.platform.web.error;

import org.springframework.http.HttpStatus;

/**
 * Protocol-level failures that mean the same thing in every service.
 *
 * <p>Domain failures - insufficient funds, a frozen wallet, a rejected payment - are deliberately
 * absent. Those belong to the service that owns the rule, declared as its own {@link ErrorCode}
 * enum, so this type does not slowly accumulate the whole platform's vocabulary.
 */
public enum PlatformErrorCode implements ErrorCode {

    /** The request body or parameters failed validation. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

    /** The request could not be parsed at all, e.g. malformed JSON. */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),

    /** A required header or parameter was missing. */
    MISSING_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST),

    /** No resource matches the requested path. */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** The path exists but not for this HTTP method. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    /** The request body's content type is not supported. */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    /** Authentication is required and was absent or invalid. */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /** The caller is authenticated but not allowed to perform this operation. */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /**
     * The caller has made too many requests and must slow down.
     *
     * <p>Protocol-level rather than domain-level: "you are going too fast" means the same thing
     * whichever endpoint said it, and a client's reaction - back off and retry later - does not
     * depend on what was being called. Responses carry {@code Retry-After}.
     */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    /** A downstream service could not be reached or had no available instance. */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    /** A downstream service did not answer in time. */
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),

    /** Anything unanticipated. The cause is logged; the caller is told nothing about internals. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    PlatformErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
