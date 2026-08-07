package com.finpay.gateway.filter;

import java.net.InetSocketAddress;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Stamps every request with the address it actually came from.
 *
 * <p>Downstream services cannot work this out for themselves: to them the connection always comes
 * from the gateway, so without this header every caller in the world looks like one client. Two
 * things depend on telling them apart - the sign-in attempt history, which answers "where was this
 * account accessed from", and auth rate limiting, which would otherwise count everyone into a
 * single bucket that one attacker could exhaust for everybody.
 *
 * <p><strong>The header is replaced, never appended to.</strong> This gateway is the edge: nothing
 * trustworthy sits in front of it, so an {@code X-Forwarded-For} that arrives from outside is a
 * claim a caller has made about themselves. Believing it would let anyone forge a different
 * address per request and get a fresh rate-limit allowance each time, which defeats the control
 * completely. Whatever the client sent is discarded and the socket address is written in its place.
 *
 * <p>Spring Cloud Gateway's own {@code XForwardedHeadersFilter} is deliberately not used. It is
 * registered only when {@code trusted-proxies} is configured, and it exists for the opposite
 * topology - a gateway sitting <em>behind</em> proxies whose forwarded headers it should believe.
 * Enabling it here would append rather than replace, leaving the client's forged entry first,
 * which is exactly the entry a downstream service reads.
 */
@Component
public class ClientAddressFilter implements WebFilter, Ordered {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String clientAddress = addressOf(exchange.getRequest().getRemoteAddress());

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(X_FORWARDED_FOR);
                    if (clientAddress != null) {
                        headers.set(X_FORWARDED_FOR, clientAddress);
                    }
                })
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    /**
     * The peer's literal address, or null when the container reports none.
     *
     * <p>{@code getHostAddress} rather than {@code toString}: the latter can include a hostname
     * and a leading slash, neither of which is an address.
     */
    private String addressOf(InetSocketAddress remote) {
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : null;
    }

    @Override
    public int getOrder() {
        // Immediately after InternalPathGuard, which rejects forbidden paths before anything else
        // spends work on them, and before routing so the header is in place for every downstream.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
