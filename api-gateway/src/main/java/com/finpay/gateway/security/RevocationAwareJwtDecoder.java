package com.finpay.gateway.security;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import reactor.core.publisher.Mono;

/**
 * A decoder that also refuses tokens which have been withdrawn.
 *
 * <p>Revocation is checked here rather than as an {@code OAuth2TokenValidator} because validators
 * are synchronous, and the only way to consult Redis from one would be to block - on a WebFlux
 * gateway that means occupying an event-loop thread for every authenticated request on the
 * platform. Wrapping the decoder keeps the lookup reactive.
 *
 * <p>The signature is verified <em>before</em> the denylist is consulted. Doing it the other way
 * round would let an unauthenticated caller probe which token ids are revoked, and would spend a
 * Redis round trip on garbage.
 */
public class RevocationAwareJwtDecoder implements ReactiveJwtDecoder {

    private final ReactiveJwtDecoder delegate;
    private final TokenDenylist denylist;

    public RevocationAwareJwtDecoder(ReactiveJwtDecoder delegate, TokenDenylist denylist) {
        this.delegate = delegate;
        this.denylist = denylist;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return delegate.decode(token).flatMap(jwt -> denylist.isRevoked(jwt.getId())
                .flatMap(revoked -> revoked
                        // The same exception type an invalid signature produces, so a revoked
                        // token is indistinguishable from any other rejected one. Telling a caller
                        // "this token was revoked" confirms it was genuine.
                        ? Mono.error(new BadJwtException("The token is not valid."))
                        : Mono.just(jwt)));
    }
}
