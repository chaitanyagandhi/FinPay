package com.finpay.auth.exception;

import org.springframework.http.HttpStatus;

import com.finpay.platform.web.error.ErrorCode;

/**
 * Failures specific to authentication.
 *
 * <p>Declared here rather than in the shared module because what can go wrong signing in is domain
 * knowledge owned by this service. The shared {@code PlatformErrorCode} carries only the
 * protocol-level failures that mean the same thing everywhere.
 *
 * <p>Clients branch on these strings, so a code may be added but never renamed or repurposed.
 */
public enum AuthErrorCode implements ErrorCode {

    /**
     * The email address is already registered.
     *
     * <p>409 rather than 400: the request was well formed, it conflicts with existing state.
     */
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),

    /**
     * Sign-in failed.
     *
     * <p>One code covers a missing account, a wrong password, a locked account and a disabled one.
     * A client cannot tell which, and that is the point: separate codes would turn this endpoint
     * into a way of discovering which addresses have accounts.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED);

    private final HttpStatus httpStatus;

    AuthErrorCode(HttpStatus httpStatus) {
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
