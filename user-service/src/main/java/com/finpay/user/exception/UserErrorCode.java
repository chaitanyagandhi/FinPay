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
    UNKNOWN_TIMEZONE(HttpStatus.BAD_REQUEST),

    /**
     * The payee is already saved.
     *
     * <p>409 rather than quietly succeeding: the caller asked to create something that exists, and
     * saying so lets a client tell a double-tap from a change it did not expect.
     */
    BENEFICIARY_ALREADY_SAVED(HttpStatus.CONFLICT),

    /**
     * No such payee, or no such saved entry.
     *
     * <p>One code for both, and 404 for a payee belonging to somebody else as well. A 403 there
     * would confirm the entry exists and is simply not the caller's, which is a slower way of
     * enumerating other people's payee lists.
     */
    BENEFICIARY_NOT_FOUND(HttpStatus.NOT_FOUND),

    /**
     * An owner cannot be their own payee.
     *
     * <p>400 rather than 409: nothing conflicts, the request is simply one the platform will never
     * accept, and the caller fixes it by sending a different id.
     */
    CANNOT_ADD_SELF(HttpStatus.BAD_REQUEST);

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
