package com.finpay.platform.web.servlet;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.finpay.platform.web.RequestCorrelation;
import com.finpay.platform.web.error.ApiError;
import com.finpay.platform.web.error.ErrorCode;
import com.finpay.platform.web.error.FinPayException;
import com.finpay.platform.web.error.PlatformErrorCode;

/**
 * Turns every exception that escapes a controller into the platform's error envelope.
 *
 * <p>Two things it deliberately does not do. It never puts framework or exception text into the
 * response: a message like "could not execute statement ... constraint wallet_pkey" tells a caller
 * about the schema. And it never swallows anything - an unanticipated failure is logged in full,
 * with its stack trace, before the caller is told only that something went wrong.
 *
 * <p>Anticipated failures ({@link FinPayException}) are logged at WARN without a stack trace,
 * because a rejected payment is an expected outcome, not an incident.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A failure the service anticipated and described. */
    @ExceptionHandler(FinPayException.class)
    public ResponseEntity<ApiError> handleFinPayException(FinPayException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();

        log.warn(
                "Request failed: code={} status={} path={}",
                errorCode.code(),
                errorCode.httpStatus().value(),
                request.getRequestURI());

        return respond(errorCode.httpStatus(), errorCode.code(), exception.getMessage(), request);
    }

    /** Bean Validation rejected the request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationFailure(
            MethodArgumentNotValidException exception, HttpServletRequest request) {

        // Field names and constraint messages come from our own API contract, so they are safe to
        // return and are what makes a 400 actionable.
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> "%s %s".formatted(error.getField(), error.getDefaultMessage()))
                .reduce((first, second) -> first + "; " + second)
                .orElse("The request contains invalid values.");

        return respond(PlatformErrorCode.VALIDATION_FAILED, message, request);
    }

    /** The body could not be parsed. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        // The parser's own message can quote the payload, so it is logged rather than returned.
        log.warn("Unreadable request body on {}: {}", request.getRequestURI(), exception.getMessage());

        return respond(PlatformErrorCode.MALFORMED_REQUEST, "The request body could not be parsed.", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {

        return respond(
                PlatformErrorCode.MISSING_REQUEST_PARAMETER,
                "Required parameter '%s' is missing.".formatted(exception.getParameterName()),
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(HttpServletRequest request) {
        return respond(PlatformErrorCode.RESOURCE_NOT_FOUND, "No resource matches this path.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {

        return respond(
                PlatformErrorCode.METHOD_NOT_ALLOWED,
                "Method %s is not supported for this path.".formatted(exception.getMethod()),
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpServletRequest request) {
        return respond(PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported.", request);
    }

    /**
     * Anything not anticipated.
     *
     * <p>Logged at ERROR with the full stack trace so it can be diagnosed, and reported to the
     * caller as a bare internal error. The request id in both is what connects them.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);

        return respond(PlatformErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request);
    }

    private ResponseEntity<ApiError> respond(ErrorCode errorCode, String message, HttpServletRequest request) {
        return respond(errorCode.httpStatus(), errorCode.code(), message, request);
    }

    private ResponseEntity<ApiError> respond(
            HttpStatus status, String code, String message, HttpServletRequest request) {

        return ResponseEntity.status(status)
                .body(ApiError.of(status, code, message, request.getRequestURI(), currentRequestId(request)));
    }

    /**
     * Prefers the id the filter recorded; falls back to the logging context, then to a fresh id so a
     * response is never left without one to quote.
     */
    private String currentRequestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String requestId) {
            return requestId;
        }
        String fromMdc = MDC.get(RequestCorrelation.REQUEST_ID_MDC_KEY);
        return fromMdc != null ? fromMdc : RequestCorrelation.generate();
    }
}
