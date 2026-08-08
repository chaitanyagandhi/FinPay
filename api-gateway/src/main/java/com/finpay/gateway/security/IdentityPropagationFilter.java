package com.finpay.gateway.security;

import java.util.stream.Collectors;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Tells downstream services who the caller is.
 *
 * <p>Every service would otherwise have to repeat the JWKS fetch, signature check and denylist
 * lookup the gateway has already done, on every request. The {@code Authorization} header is still
 * forwarded untouched, so a service can verify independently once it has reason to - these headers
 * are a convenience, not a replacement for that.
 *
 * <p><strong>Inbound copies are always removed, authenticated or not.</strong> Without that, a
 * caller could simply send {@code X-User-Id} and be believed by anything that trusts it - the
 * whole authentication chain bypassed with one header. The same reasoning as
 * {@code ClientAddressFilter}: a header a downstream service trusts must be one this gateway wrote.
 *
 * <p>Downstream services are unpublished and reachable only through here, which is what makes
 * trusting these headers defensible at all. If that ever stops being true, this becomes a
 * spoofable identity and the services must verify the token themselves.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    /** The authenticated subject: the user's id, as it appears in the token's {@code sub}. */
    public static final String USER_ID = "X-User-Id";

    /** Comma-separated roles, for services making an authorization decision. */
    public static final String USER_ROLES = "X-User-Roles";

    /** The token's unique id, so a downstream log line can be tied back to one session. */
    public static final String TOKEN_ID = "X-Token-Id";

    private static final String ROLES_CLAIM = "roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(authentication -> withIdentity(exchange, authentication))
                // Public endpoints have no authentication; the headers are still stripped.
                .defaultIfEmpty(withoutIdentity(exchange))
                .flatMap(chain::filter);
    }

    private ServerWebExchange withIdentity(ServerWebExchange exchange, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return withoutIdentity(exchange);
        }

        Jwt jwt = jwtAuthentication.getToken();
        String roles = roleClaimOf(jwt, jwtAuthentication);

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    strip(headers);
                    headers.set(USER_ID, jwt.getSubject());
                    if (jwt.getId() != null) {
                        headers.set(TOKEN_ID, jwt.getId());
                    }
                    if (!roles.isEmpty()) {
                        headers.set(USER_ROLES, roles);
                    }
                })
                .build();

        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange withoutIdentity(ServerWebExchange exchange) {
        ServerHttpRequest request =
                exchange.getRequest().mutate().headers(this::strip).build();

        return exchange.mutate().request(request).build();
    }

    /** Prefers the token's own claim; falls back to the authorities Spring derived from it. */
    private String roleClaimOf(Jwt jwt, JwtAuthenticationToken authentication) {
        java.util.List<String> claimed = jwt.getClaimAsStringList(ROLES_CLAIM);

        if (claimed != null && !claimed.isEmpty()) {
            return String.join(",", claimed);
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
    }

    private void strip(org.springframework.http.HttpHeaders headers) {
        headers.remove(USER_ID);
        headers.remove(USER_ROLES);
        headers.remove(TOKEN_ID);
    }

    @Override
    public int getOrder() {
        // Before the routing filters that actually send the request downstream.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
