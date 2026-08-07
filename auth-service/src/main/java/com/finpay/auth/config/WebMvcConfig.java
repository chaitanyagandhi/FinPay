package com.finpay.auth.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.finpay.auth.web.RateLimitInterceptor;

/**
 * Where the rate limiter applies.
 *
 * <p>Listed explicitly rather than applied to everything under {@code /api/v1/auth}. Throttling is
 * for the endpoints an attacker gains something by hammering: guessing credentials, and grinding
 * through refresh tokens. The JWKS endpoint is deliberately excluded - it serves a public key that
 * every verifier must be able to fetch, and rate limiting it would break token validation across
 * the platform under exactly the load where it matters most.
 *
 * <p>Actuator lives on its own port and never passes through this dispatcher, so container
 * healthchecks cannot be throttled either.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final List<String> RATE_LIMITED_PATHS =
            List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout");

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns(RATE_LIMITED_PATHS);
    }
}
