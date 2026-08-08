package com.finpay.user.exception;

import org.springframework.http.HttpStatus;

import com.finpay.platform.web.error.ErrorCode;

/**
 * Failures specific to user profiles.
 *
 * <p>Declared here rather than in the shared module because what can go wrong with a profile is
 * domain knowledge this service owns. Clients branch on these strings, so a code may be added but
 * never renamed or repurposed.
 */
public enum UserErrorCode implements ErrorCode {

    /**
     * The phone number already belongs to another account.
     *
     * <p>409 rather than 400: the request was well formed, it conflicts with existing state. The
     * message never says whose account it is - that would turn profile editing into a way of
     * testing which numbers are registered.
     */
    PHONE_NUMBER_ALREADY_USED(HttpStatus.CONFLICT),

    /** The timezone is not one the platform recognises. */
    UNKNOWN_TIMEZONE(HttpStatus.BAD_REQUEST);

    private final HttpStatus httpStatus;

    UserErrorCode(HttpStatus httpStatus) {
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
