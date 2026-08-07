package com.finpay.auth.dto;

/**
 * Where a request appeared to come from.
 *
 * <p>Recorded on every sign-in attempt for the audit trail. Both values originate with the caller
 * or with a proxy, so neither is trustworthy enough to make an authorization decision - they
 * answer "what happened" after the fact, not "should this be allowed" beforehand.
 *
 * @param ipAddress may be null when it cannot be determined
 * @param userAgent may be null; truncated to fit the column
 */
public record ClientContext(String ipAddress, String userAgent) {

    private static final int MAX_USER_AGENT_LENGTH = 255;

    /**
     * Literal IPv4 or IPv6, nothing else.
     *
     * <p>Deliberately not a hostname: the address arrives in a client-settable header, and
     * resolving whatever it contains would let a caller make this service perform DNS lookups of
     * their choosing. Anything that is not already an address is discarded.
     */
    private static final java.util.regex.Pattern LITERAL_IP =
            java.util.regex.Pattern.compile("^[0-9.]+$|^[0-9a-fA-F:]+$");

    public ClientContext {
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        // A malformed address is dropped rather than rejected: it is an audit detail, and
        // refusing the sign-in over it would turn a cosmetic problem into an outage.
        if (ipAddress != null && !LITERAL_IP.matcher(ipAddress.trim()).matches()) {
            ipAddress = null;
        } else if (ipAddress != null) {
            ipAddress = ipAddress.trim();
        }
    }

    public static ClientContext unknown() {
        return new ClientContext(null, null);
    }
}
