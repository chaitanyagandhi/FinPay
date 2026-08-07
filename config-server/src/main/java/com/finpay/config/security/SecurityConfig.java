package com.finpay.config.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Locks down the configuration endpoints.
 *
 * <p>Configuration describes the shape of the platform: which hosts it talks to, which endpoints
 * exist, and where its secrets come from. That is useful to an attacker even when it contains no
 * secret values, so every configuration endpoint requires authentication.
 *
 * <p>Clients are services, not browsers, so the chain is stateless and uses HTTP Basic. Credentials
 * come from the environment ({@code CONFIG_SERVER_USERNAME} / {@code CONFIG_SERVER_PASSWORD}); the
 * committed defaults apply to local development only.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain configServerFilterChain(HttpSecurity http) throws Exception {
        return http
                // No browser client and no session, so there is no CSRF vector to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Actuator binds its own port (management.server.port), which compose
                        // never publishes. Health and info are permitted so container healthchecks
                        // and Kubernetes probes work without credentials; metrics and everything
                        // else still require them, and Prometheus scrapes with credentials.
                        .requestMatchers(EndpointRequest.to("health", "info"))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(withDefaults())
                .build();
    }
}
