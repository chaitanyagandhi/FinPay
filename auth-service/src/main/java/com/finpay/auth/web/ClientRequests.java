package com.finpay.auth.web;

import jakarta.servlet.http.HttpServletRequest;

import com.finpay.auth.dto.ClientContext;

/**
 * Where a request appeared to come from.
 *
 * <p>Shared by the controller, which records the address for the audit trail, and the rate limiter,
 * which counts against it. They must agree: if the two disagreed about who a caller is, the
 * throttle would be counting a different party from the one the attempt history blames.
 */
public final class ClientRequests {

    /**
     * Set by the gateway, which is the only thing that should be reaching this service. The value
     * originates with the caller and is trustworthy only because everything in front of it is ours.
     */
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientRequests() {}

    /**
     * The caller's address.
     *
     * <p>Only the first entry of the forwarded header is taken; the rest are proxies. Behind the
     * gateway the socket address is always the gateway's, so the header is the only thing carrying
     * the caller's address - but it is client-settable on a direct connection, so a value that is
     * not a literal IP is discarded and the socket address used instead.
     */
    public static String ipAddressOf(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);

        if (forwarded != null && !forwarded.isBlank()) {
            // ClientContext validates and normalises; it yields null for anything that is not a
            // literal address, which is exactly the case where the socket is the better answer.
            String candidate = new ClientContext(forwarded.split(",")[0], null).ipAddress();
            if (candidate != null) {
                return candidate;
            }
        }

        return request.getRemoteAddr();
    }

    /** Everything recorded about where a request came from. */
    public static ClientContext contextOf(HttpServletRequest request) {
        return new ClientContext(ipAddressOf(request), request.getHeader("User-Agent"));
    }
}
