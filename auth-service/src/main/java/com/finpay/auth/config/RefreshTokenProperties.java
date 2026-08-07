package com.finpay.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How refresh tokens behave.
 *
 * <p>Separate from {@link JwtProperties} because a refresh token is not a JWT and shares none of
 * its mechanics: it is an opaque random value whose authority comes from a database row, not from
 * a signature.
 */
@ConfigurationProperties(prefix = "finpay.auth.refresh-token")
public class RefreshTokenProperties {

    /**
     * How long a refresh token remains spendable.
     *
     * <p>Long where the access token TTL is short. That asymmetry is the point of having two
     * tokens: the access token is the one presented on every request and cannot be withdrawn, so
     * it expires quickly; the refresh token is presented rarely, is checked against a row that can
     * be revoked, and therefore can afford to live long enough that people are not signed out
     * every fifteen minutes.
     */
    private Duration ttl = Duration.ofDays(30);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
