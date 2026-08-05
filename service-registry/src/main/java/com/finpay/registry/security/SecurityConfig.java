package com.finpay.registry.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Locks down the registry.
 *
 * <p>The registry holds the address of every running instance in the platform, including internal
 * services that are never exposed through the gateway. Left open it is a map of the whole system, so
 * both the replication API and the dashboard require authentication.
 *
 * <p>CSRF protection is disabled because the clients are services, not browsers: Eureka clients
 * register, heartbeat, and deregister with plain HTTP calls carrying no cookie and no session, and
 * the dashboard is read-only. Leaving CSRF enabled would reject every registration.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain registryFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Called by the container healthcheck and Kubernetes probes before any
                        // credential is available.
                        .requestMatchers(EndpointRequest.to("health", "info"))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .httpBasic(withDefaults())
                .build();
    }
}
