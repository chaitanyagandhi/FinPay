package com.finpay.auth.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.finpay.auth.config.RateLimitProperties;

/**
 * Counts requests per caller per endpoint, in Redis.
 *
 * <p>Redis rather than memory because the counter has to be shared. A per-instance counter means
 * the effective limit multiplies by the number of replicas, and an attacker who reconnects lands
 * on a different instance with a fresh allowance - a limit that looks enforced and is not.
 *
 * <p>A fixed window implemented as {@code INCR} plus an {@code EXPIRE} on first use. The expiry is
 * set only when the counter is created, so the window starts at the first request and is never
 * extended by later ones; setting it on every request would turn a busy caller's window into a
 * rolling ban that never ends.
 */
@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    private static final String KEY_PREFIX = "finpay:auth:rate-limit:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public AuthRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Counts one request and reports whether it is over the limit.
     *
     * <p><strong>Fails open.</strong> If Redis cannot be reached the request is allowed. Failing
     * closed would turn a Redis outage into a total authentication outage - nobody can sign in,
     * refresh, or log out - which is a far larger incident than temporarily losing a throttle. The
     * control that protects an individual account, lockout, lives in PostgreSQL and keeps working
     * regardless, so this failure mode degrades defence in depth rather than removing it.
     *
     * @param bucket what is being limited, e.g. the endpoint
     * @param caller who is being limited, normally a client address
     * @return the decision, including how long to wait when refused
     */
    public Decision check(String bucket, String caller) {
        if (!properties.isEnabled()) {
            return Decision.allowed(properties.getRequests());
        }

        String key = KEY_PREFIX + bucket + ":" + caller;
        Duration window = properties.getWindow();

        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return Decision.allowed(properties.getRequests());
            }

            if (count == 1L) {
                // First request of a window: start the clock. Only here, so the window expires a
                // fixed time after it opened rather than a fixed time after the last attempt.
                redis.expire(key, window);
            }

            if (count > properties.getRequests()) {
                Duration retryAfter = redis.getExpire(key) > 0 ? Duration.ofSeconds(redis.getExpire(key)) : window;
                log.info("Rate limit exceeded: bucket={} count={} limit={}", bucket, count, properties.getRequests());
                return Decision.refused(retryAfter);
            }

            return Decision.allowed(properties.getRequests() - count.intValue());
        } catch (DataAccessException e) {
            // Logged at warn, not error: the service is still doing its job, with one layer of
            // protection missing. An alert on this belongs in the monitoring rules, not here.
            log.warn("Rate limiter could not reach Redis; allowing the request: {}", e.getMessage());
            return Decision.allowed(properties.getRequests());
        }
    }

    /**
     * The outcome of one check.
     *
     * @param allowed whether the request may proceed
     * @param remaining requests left in the window; meaningless when refused
     * @param retryAfter how long until the window resets; null when allowed
     */
    public record Decision(boolean allowed, int remaining, Duration retryAfter) {

        static Decision allowed(int remaining) {
            return new Decision(true, Math.max(remaining, 0), null);
        }

        static Decision refused(Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }
    }
}
