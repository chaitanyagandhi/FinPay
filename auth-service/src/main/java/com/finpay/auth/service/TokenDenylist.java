package com.finpay.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes revoked access token ids where every service can see them.
 *
 * <p>{@code revoked_tokens} in this service's database is the durable record, but nothing else can
 * read it: each service owns its own database and reaching across that boundary is exactly the
 * coupling the platform is built to avoid. The alternative - an introspection call to this service
 * on every single request - would put a network hop and this service's availability in front of
 * every authenticated operation on the platform.
 *
 * <p>So the id is also written to Redis, which the gateway already has, and read from there on the
 * hot path at the cost of one key lookup.
 *
 * <p>Each key carries a TTL equal to what was left of the token's own life. The denylist therefore
 * empties itself, and its size is bounded by the access token TTL rather than by the number of
 * logouts ever performed - the same reasoning as the {@code expires_at} column, enforced by Redis
 * instead of by a sweep.
 */
@Component
public class TokenDenylist {

    private static final Logger log = LoggerFactory.getLogger(TokenDenylist.class);

    /** Shared with the gateway. Changing it on one side silently stops revocation working. */
    public static final String KEY_PREFIX = "finpay:auth:revoked-token:";

    private final StringRedisTemplate redis;

    public TokenDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records that an access token must stop being accepted.
     *
     * <p>Best effort. If Redis is unreachable the token keeps working until it expires on its own,
     * which is at most the access token TTL - and the refresh family is revoked in PostgreSQL
     * regardless, so the session still cannot be extended. Losing the durable record instead would
     * be worse, so a Redis failure never fails the logout.
     *
     * @param expiresAt the token's own expiry; nothing is written if it has already passed
     */
    public void revoke(UUID jti, Instant expiresAt) {
        Duration remaining = Duration.between(Instant.now(), expiresAt);

        if (remaining.isNegative() || remaining.isZero()) {
            // Already expired: every verifier rejects it on the expiry claim alone.
            return;
        }

        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "revoked", remaining);
            log.info("Published revocation of access token {} for the next {}s", jti, remaining.toSeconds());
        } catch (DataAccessException e) {
            log.warn(
                    "Could not publish revocation of access token {}; it stays valid for up to {}s. "
                            + "The refresh family is revoked regardless, so the session cannot be extended: {}",
                    jti,
                    remaining.toSeconds(),
                    e.getMessage());
        }
    }
}
