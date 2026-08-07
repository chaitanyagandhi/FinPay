package com.finpay.platform.web;

import java.util.UUID;

import org.springframework.util.StringUtils;

/**
 * The identifier that ties together everything one client request causes.
 *
 * <p>A single transfer touches the gateway, the payment service, the fraud service, the wallet
 * service and the transaction service. Without a shared identifier, reconstructing what happened
 * means correlating timestamps across five log streams and guessing. With one, every line belonging
 * to that request can be selected exactly.
 *
 * <p>The gateway mints the id when a request arrives without one and passes it to every service it
 * calls. Services echo it back on the response so a client, a support engineer or an auditor can
 * quote it when reporting a problem.
 */
public final class RequestCorrelation {

    /** Inbound and outbound HTTP header carrying the request id. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Logging context key. Structured log output emits this as a field on every line. */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    /** Exchange or request attribute under which the resolved id is stored. */
    public static final String REQUEST_ID_ATTRIBUTE = RequestCorrelation.class.getName() + ".requestId";

    /** Longest accepted inbound id; anything longer is treated as untrusted and replaced. */
    public static final int MAX_LENGTH = 64;

    private RequestCorrelation() {}

    /**
     * Returns a usable request id for an inbound value, generating one when necessary.
     *
     * <p>The inbound header is client-controlled, so it is accepted only when it is short and made
     * only of characters that are safe to place into a log field. A rejected value is replaced
     * rather than causing the request to fail: a malformed id is a tracing problem, not a reason to
     * refuse someone's payment.
     */
    public static String resolve(String inboundValue) {
        return isAcceptable(inboundValue) ? inboundValue : generate();
    }

    /** Returns a new random request id. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns whether a client-supplied id may be reused as-is.
     *
     * <p>Restricting the character set keeps a caller from injecting newlines or control characters
     * that would forge extra entries in the log stream.
     */
    static boolean isAcceptable(String value) {
        if (!StringUtils.hasText(value) || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isAllowed(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowed(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_';
    }
}
