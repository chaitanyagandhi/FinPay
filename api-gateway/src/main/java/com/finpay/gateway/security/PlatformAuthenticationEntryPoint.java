package com.finpay.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Turns a security rejection into the platform's error envelope.
 *
 * <p>Spring Security's defaults answer with an empty body and a {@code WWW-Authenticate} header
 * describing the failure - a different shape from every other error this platform returns, and one
 * that narrates why a token was rejected. A client parsing errors would need a special case for
 * exactly the responses it is least able to anticipate.
 *
 * <p>Rather than writing JSON here, which would be a second copy of the envelope destined to drift
 * from {@code GatewayErrorAttributes}, the rejection is re-raised as a {@link ResponseStatusException}.
 * That propagates out of the security filter chain to the error handler that already renders every
 * other gateway failure, so there is exactly one place the envelope is built.
 *
 * <p>The detail is deliberately dropped. Expired, malformed, wrong audience, wrong issuer and
 * revoked all become the same "authentication is required": distinguishing them tells whoever is
 * holding a token which part of it to fix.
 */
@Component
public class PlatformAuthenticationEntryPoint implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /**
     * The caller is authenticated but not allowed to do this.
     *
     * <p>403 rather than 401: repeating the sign-in will not help, and saying so saves a client
     * from an endless refresh loop.
     */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }
}
