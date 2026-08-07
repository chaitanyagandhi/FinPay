package com.finpay.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How hard one caller may hit the authentication endpoints.
 *
 * <p>This limit and account lockout defend against different attacks and neither replaces the
 * other. Lockout protects one account from many guesses; this protects the service from one source
 * making many attempts spread thinly across many accounts, which lockout never sees because no
 * single account accumulates enough failures to trigger it.
 */
@ConfigurationProperties(prefix = "finpay.auth.rate-limit")
public class RateLimitProperties {

    /**
     * Turned off in tests that are not about rate limiting, so they do not need Redis.
     *
     * <p>Deliberately not turned off by a missing Redis: an operator disabling a control should
     * have to say so, not achieve it by breaking a dependency.
     */
    private boolean enabled = true;

    /** Requests allowed per window, per caller, per endpoint. */
    private int requests = 10;

    /**
     * The window length.
     *
     * <p>A fixed window, not a sliding one. It is one Redis round trip and needs no per-request
     * state, at the cost of allowing up to twice the limit across a window boundary. For a control
     * whose job is to stop sustained guessing rather than to meter a paid API, that burst is not
     * worth the extra machinery - and lockout still bounds what the burst can achieve against any
     * individual account.
     */
    private Duration window = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequests() {
        return requests;
    }

    public void setRequests(int requests) {
        this.requests = requests;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
