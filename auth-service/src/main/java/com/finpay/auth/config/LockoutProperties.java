package com.finpay.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * When repeated failures stop being typos and start being an attack.
 *
 * <p>Both values are a trade between two ways of being wrong. Too strict and an ordinary person
 * who mistypes their password on a phone keyboard locks themselves out of their money; too lax and
 * an attacker gets enough guesses to be worth trying. The defaults sit where a real person almost
 * never lands but a guessing attack immediately does.
 */
@ConfigurationProperties(prefix = "finpay.auth.lockout")
public class LockoutProperties {

    /**
     * Consecutive failures that trigger a lock.
     *
     * <p>Consecutive, not cumulative: {@code User.recordSuccessfulLogin} resets the counter, so an
     * account is never eventually locked by ordinary typos spread over months.
     */
    private int maxFailedAttempts = 5;

    /**
     * How long the lock lasts.
     *
     * <p>Temporary rather than permanent, and deliberately so. A permanent lock would hand any
     * attacker who knows an email address a way to deny that person access to their own money -
     * turning a control that protects the account into one that attacks it. Support can still lock
     * an account indefinitely by setting its status, which is a decision a human makes.
     */
    private Duration duration = Duration.ofMinutes(15);

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public Duration getDuration() {
        return duration;
    }

    public void setDuration(Duration duration) {
        this.duration = duration;
    }
}
