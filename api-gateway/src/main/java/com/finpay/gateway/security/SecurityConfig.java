package com.finpay.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Who may call what.
 *
 * <p>Until now an access token was proof of nothing: nothing validated it, so the whole sign-in
 * mechanism ended at the point of issuing a string. This is the enforcement point that gives it
 * meaning.
 *
 * <p>Validation lives at the gateway because it is the only way in, so a rule applied here cannot
 * be skipped by reaching a service directly - no service port is published. Services will
 * eventually verify tokens themselves as well; that is defence in depth, not a substitute for
 * stopping unauthenticated traffic at the edge.
 *
 * <p>The gateway holds only the <em>public</em> half of the signing key, fetched from auth-service's
 * JWKS endpoint. It can check that a token is genuine and cannot produce one - which is the whole
 * point of RS256 over a shared secret.
 */
@Configuration
public class SecurityConfig {

    /**
     * Paths that must work without a token.
     *
     * <p>Listed exhaustively rather than by prefix. {@code /api/v1/auth/**} would be the obvious
     * pattern and is wrong: it would exempt every endpoint auth-service ever adds, including ones
     * that should require a session, purely because of where they live.
     */
    private static final String[] PUBLIC_POST = {
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        // Refresh carries its own credential and is how a client recovers from an expired access
        // token - requiring a valid one would make it useless exactly when it is needed.
        "/api/v1/auth/refresh",
        // Logout must work with an expired or absent access token. Refusing it would leave a
        // caller unable to end a session they have lost the ability to prove they own.
        "/api/v1/auth/logout"
    };

    private static final String[] PUBLIC_GET = {
        // A public key has to be fetchable by anyone who needs to verify a token, including
        // clients that have no credentials at all.
        "/api/v1/auth/.well-known/jwks.json", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**"
    };

    /**
     * Actuator, on its own unpublished port.
     *
     * <p>Separate and first, because the chain below authenticates everything it does not
     * explicitly exempt - and that includes the management port. Without this, {@code
     * /actuator/health} answers 401, the container healthcheck never passes, the gateway is
     * permanently unhealthy and Prometheus stops scraping. The failure looks like a broken
     * gateway rather than a security rule, which is what makes it worth stating loudly.
     *
     * <p>Anonymous access is the deliberate choice from §9.6: the port is not published, the
     * exposed set is limited to health, info, metrics and prometheus, and healthchecks and the
     * scrape both work without distributing a credential. Port isolation is the control here.
     *
     * <p>Matched by {@link EndpointRequest}, not by a {@code /actuator/**} glob. A blanket path
     * rule is what once made the config server's {@code /{application}/{profile}} lookups
     * anonymous on its public port - the matcher knows what an actuator endpoint actually is.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain actuatorSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder, PlatformAuthenticationEntryPoint entryPoint) {

        return http
                // No browser session, no cookies, no CSRF token to steal: every request carries
                // its own bearer credential. CSRF protection defends cookie-based authentication,
                // which this platform deliberately does not use.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // CORS preflight carries no credentials by definition.
                        .pathMatchers(HttpMethod.OPTIONS)
                        .permitAll()
                        .pathMatchers(HttpMethod.POST, PUBLIC_POST)
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, PUBLIC_GET)
                        .permitAll()
                        // Everything else, including paths no route claims. An unauthenticated
                        // caller learns nothing about which paths exist.
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)).authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(entryPoint).accessDeniedHandler(entryPoint))
                .build();
    }

    /**
     * Verifies signature, expiry, issuer and audience - then revocation.
     *
     * <p>Audience is checked explicitly. A verifier that skips it will happily accept a token
     * minted for a different system that happens to share the signing key, which turns one
     * compromised integration into a platform-wide one.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${finpay.gateway.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${finpay.gateway.jwt.issuer}") String issuer,
            @Value("${finpay.gateway.jwt.audience}") String audience,
            TokenDenylist denylist) {

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                // Pinned: without this, a token signed with any algorithm the JWKS advertises is
                // accepted, and algorithm confusion is a well-worn way in.
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience));
        decoder.setJwtValidator(validator);

        return new RevocationAwareJwtDecoder(decoder, denylist);
    }
}
