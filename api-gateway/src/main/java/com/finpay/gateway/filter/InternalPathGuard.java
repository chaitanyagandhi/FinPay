package com.finpay.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Refuses any request for a service-internal path.
 *
 * <p>Services expose internal operations under {@code /internal} - reserving and releasing wallet
 * funds, finalising debits - which move money without the checks that guard the public API. They are
 * meant to be callable only by other FinPay services on the internal network, never by a client.
 *
 * <p>The route table alone already withholds them, since only {@code /api/v1/**} paths are mapped.
 * This filter is the second layer: it makes the guarantee explicit and independent of the route
 * table, so a future route with a broader predicate cannot quietly expose them. Both layers are
 * cheap; the failure they prevent is not.
 *
 * <p>Requests are rejected with 404 rather than 403. A 403 would confirm that the path exists and is
 * merely forbidden, which tells a prober exactly where to keep looking.
 */
@Component
public class InternalPathGuard implements WebFilter, Ordered {

    /** Path segment that marks a service-internal endpoint. */
    static final String INTERNAL_SEGMENT = "internal";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isInternalPath(exchange.getRequest().getPath().value())) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    /**
     * Returns whether the path addresses a service-internal endpoint.
     *
     * <p>The comparison is per segment, so {@code /api/v1/internal-transfers} is a legitimate public
     * path and is not blocked, while {@code /internal/v1/wallets} is. The path is normalised first
     * so that a traversal such as {@code /api/v1/../internal/v1/wallets} cannot slip past by
     * spelling the segment indirectly.
     */
    static boolean isInternalPath(String rawPath) {
        String normalised = StringUtils.cleanPath(rawPath);
        for (String segment : normalised.split("/")) {
            if (INTERNAL_SEGMENT.equalsIgnoreCase(segment)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        // Ahead of Spring Cloud Gateway's routing filter, so a blocked path is never matched
        // against the route table or forwarded downstream.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
