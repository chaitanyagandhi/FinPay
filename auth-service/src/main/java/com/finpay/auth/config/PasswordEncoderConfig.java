package com.finpay.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * How passwords are hashed.
 *
 * <p>BCrypt, with a per-password salt generated automatically, so two people choosing the same
 * password do not share a hash and a stolen table cannot be attacked with one precomputed set.
 *
 * <p>The cost factor is deliberately expensive: it is the only thing standing between a leaked
 * hash and an offline brute-force. It is configurable so tests can lower it - correctness does not
 * depend on the cost, but a suite that pays the production cost on every fixture is a suite people
 * stop running.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Cost factor. Each increment doubles the work; 12 is roughly a quarter of a second per hash
     * on current hardware, which is negligible for a human signing in and painful at scale for an
     * attacker.
     */
    public static final int DEFAULT_STRENGTH = 12;

    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${finpay.auth.password.bcrypt-strength:" + DEFAULT_STRENGTH + "}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
