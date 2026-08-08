package com.finpay.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Asks whether an access token has been withdrawn since it was issued.
 *
 * <p>An access token is self-contained: its signature and expiry are checkable without asking
 * anyone, which is what makes it fast and what makes logout impossible to honour on its own.
 * auth-service publishes the ids of tokens it has revoked, and this reads them.
 *
 * <p>The key format is shared with auth-service. It is stated in both places rather than in a
 * common module because the shared module holds no business contracts - but changing it on one
 * side and not the other silently stops revocation working, which is the kind of failure nothing
 * complains about.
 */
@Component
public class TokenDenylist {

    private static final Logger log = LoggerFactory.getLogger(TokenDenylist.class);

    /** Must match {@code com.finpay.auth.service.TokenDenylist.KEY_PREFIX}. */
    static final String KEY_PREFIX = "finpay:auth:revoked-token:";

    private final ReactiveStringRedisTemplate redis;

    public TokenDenylist(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Whether this token id has been revoked.
     *
     * <p><strong>Fails closed.</strong> If Redis cannot be reached the token is treated as
     * revoked and the request is refused. This is the opposite choice from auth rate limiting,
     * and deliberately so: a throttle that stops working costs a layer of protection, while a
     * revocation check that stops working keeps honouring tokens their owners have already
     * signed out - including the ones revoked because they were stolen. Refusing is visible and
     * recoverable; silently accepting withdrawn credentials is neither.
     *
     * <p>The blast radius is bounded: refresh and sign-in stay public, so callers can obtain a
     * session, and the outage announces itself immediately rather than being discovered later in
     * an incident review.
     */
    public Mono<Boolean> isRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            // A token with no jti cannot be revoked individually, so it must not be issued.
            // Refusing here rather than waving it through keeps that guarantee enforceable.
            log.warn("Access token carried no token id; treating it as unusable");
            return Mono.just(true);
        }

        return redis.hasKey(KEY_PREFIX + tokenId).onErrorResume(error -> {
            log.error(
                    "Revocation denylist unreachable; refusing the request rather than honouring a "
                            + "token that may have been withdrawn: {}",
                    error.getMessage());
            return Mono.just(true);
        });
    }
}
