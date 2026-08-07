package com.finpay.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How access tokens are issued.
 *
 * <p>The signing key is intentionally absent from this type. It is read from the environment by
 * {@link JwtKeyConfig}, so a private key can never end up in a configuration file that gets
 * committed, printed by an actuator endpoint, or served by the config server.
 */
@ConfigurationProperties(prefix = "finpay.auth.jwt")
public class JwtProperties {

    /** Identifies this platform as the token's issuer; verifiers reject anything else. */
    private String issuer = "https://finpay.local/auth";

    /**
     * Who the token is for. A verifier that does not check the audience will happily accept a
     * token minted for a different system that shares the signing key.
     */
    private String audience = "finpay";

    /**
     * Deliberately short. An access token cannot be withdrawn once issued - it is self-contained,
     * and the denylist only covers the window before it expires anyway - so the window itself is
     * the primary control. Refresh tokens, added next, are what keep this from being annoying.
     */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }
}
