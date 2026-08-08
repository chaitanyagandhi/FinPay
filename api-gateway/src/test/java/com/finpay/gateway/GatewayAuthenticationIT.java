package com.finpay.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * What the gateway accepts, what it refuses, and what it tells downstream.
 *
 * <p>This is the step that gives an access token meaning. Until now nothing verified one, so a
 * forged token and a real one were equally good; every case below would have passed with the
 * verification deleted, which is precisely why they are asserted against real RS256 signatures and
 * a real key set fetched over HTTP rather than a stubbed decoder.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.discovery.client.simple.instances.auth-service[0].uri=http://localhost:${wiremock.port}",
            "finpay.gateway.jwt.jwk-set-uri=http://localhost:${wiremock.port}" + TestTokens.JWKS_PATH,
            "management.server.port=" + GatewayAuthenticationIT.MANAGEMENT_PORT,
        })
@Testcontainers
class GatewayAuthenticationIT {

    static final String MANAGEMENT_PORT = "19200";

    private static WireMockServer authService;
    private static final TestTokens TOKENS = new TestTokens();

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.0-alpine").withExposedPorts(6379);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void startDownstreamService() {
        authService = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        authService.start();
        System.setProperty("wiremock.port", String.valueOf(authService.port()));
    }

    @AfterAll
    static void stopDownstreamService() {
        if (authService != null) {
            authService.stop();
        }
        System.clearProperty("wiremock.port");
    }

    @DynamicPropertySource
    static void downstreamAddress(DynamicPropertyRegistry registry) {
        registry.add("wiremock.port", () -> authService.port());
    }

    @BeforeEach
    void resetStubs() {
        authService.resetAll();
        authService.stubFor(get(urlEqualTo(TestTokens.JWKS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOKENS.jwkSetJson())));
        stubDownstream();
    }

    // --- what is refused ----------------------------------------------------------------------

    @Test
    @DisplayName("refuses a protected request with no token at all")
    void refusesAnonymousAccess() {
        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .exchange()
                .expectStatus()
                .isUnauthorized();

        // Nothing may reach the service: an unauthenticated request must cost the platform nothing.
        authService.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions")));
    }

    @Test
    @DisplayName("refuses a token signed by a key the platform does not publish")
    void refusesAForgedSignature() {
        // The forgery case. Before this step it would have been indistinguishable from a real one.
        expectRefused(TOKENS.signedByAnotherKey());
    }

    @Test
    @DisplayName("refuses an expired token")
    void refusesAnExpiredToken() {
        expectRefused(TOKENS.expired());
    }

    @Test
    @DisplayName("refuses a token minted for a different audience")
    void refusesAForeignAudience() {
        // Spring's defaults do not check this. Without AudienceValidator, a token issued for
        // another system sharing the signing key would be accepted here.
        expectRefused(TOKENS.withAudience("some-other-system"));
    }

    @Test
    @DisplayName("refuses a token from an unexpected issuer")
    void refusesAForeignIssuer() {
        expectRefused(TOKENS.withIssuer("https://not-finpay.example/auth"));
    }

    @Test
    @DisplayName("refuses gibberish in the Authorization header")
    void refusesAMalformedToken() {
        expectRefused("not-even-a-jwt");
    }

    @Test
    @DisplayName("refuses a revoked token, which is what makes logout mean anything")
    void refusesARevokedToken() {
        String jti = UUID.randomUUID().toString();
        String token = TOKENS.withTokenId(jti);

        // Accepted before revocation: this is the control, and without it the assertion below
        // would pass even if the token were being rejected for some unrelated reason.
        expectAccepted(token);

        revoke(jti);

        expectRefused(token);
    }

    @Test
    @DisplayName("every rejection looks the same, whatever was wrong with the token")
    void rejectionsAreIndistinguishable() {
        String forged = bodyOf(TOKENS.signedByAnotherKey());
        String expired = bodyOf(TOKENS.expired());
        String wrongAudience = bodyOf(TOKENS.withAudience("elsewhere"));

        // Telling a caller which part of their token was wrong tells whoever is holding a stolen
        // or crafted one exactly what to fix.
        org.assertj.core.api.Assertions.assertThat(withoutVaryingFields(expired))
                .isEqualTo(withoutVaryingFields(forged));
        org.assertj.core.api.Assertions.assertThat(withoutVaryingFields(wrongAudience))
                .isEqualTo(withoutVaryingFields(forged));
    }

    // --- what is allowed ----------------------------------------------------------------------

    @Test
    @DisplayName("accepts a genuine token and forwards the request")
    void acceptsAGenuineToken() {
        expectAccepted(TOKENS.valid());
        authService.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions")));
    }

    @Test
    @DisplayName("lets sign-in, registration, refresh and logout through without a token")
    void allowsTheEndpointsThatCannotRequireOne() {
        // Requiring a token here would make it impossible to obtain one, and would leave a caller
        // whose access token has expired unable to refresh or to end their session.
        for (String path :
                List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")) {
            authService.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)));

            webTestClient.post().uri(path).exchange().expectStatus().isOk();
        }
    }

    @Test
    @DisplayName("serves the public key without a token, since a verifier has no credentials yet")
    void allowsTheJwksEndpoint() {
        authService.stubFor(get(urlEqualTo("/api/v1/auth/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200).withBody("{\"keys\":[]}")));

        webTestClient
                .get()
                .uri("/api/v1/auth/.well-known/jwks.json")
                .exchange()
                .expectStatus()
                .isOk();
    }

    // --- what downstream is told --------------------------------------------------------------

    @Test
    @DisplayName("tells the downstream service who the caller is")
    void propagatesIdentity() {
        String subject = UUID.randomUUID().toString();

        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.forUser(subject, List.of("USER", "ADMIN")))
                .exchange()
                .expectStatus()
                .isOk();

        authService.verify(getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions"))
                .withHeader("X-User-Id", equalTo(subject))
                .withHeader("X-User-Roles", equalTo("USER,ADMIN")));
    }

    @Test
    @DisplayName("discards an identity a caller claims for themselves")
    void refusesToBelieveClaimedIdentity() {
        String realSubject = UUID.randomUUID().toString();
        String victim = UUID.randomUUID().toString();

        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.forUser(realSubject, List.of("USER")))
                // Forged alongside a genuine token: if the header survived, a valid user could act
                // as any other simply by naming them, and every authorization check downstream
                // would be reading the attacker's own input.
                .header("X-User-Id", victim)
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus()
                .isOk();

        authService.verify(getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions"))
                .withHeader("X-User-Id", equalTo(realSubject))
                .withHeader("X-User-Roles", equalTo("USER")));
    }

    @Test
    @DisplayName("strips claimed identity headers even from an unauthenticated public request")
    void stripsIdentityOnPublicPaths() {
        authService.stubFor(
                post(urlEqualTo("/api/v1/auth/login")).willReturn(aResponse().withStatus(200)));

        webTestClient
                .post()
                .uri("/api/v1/auth/login")
                .header("X-User-Id", UUID.randomUUID().toString())
                .exchange()
                .expectStatus()
                .isOk();

        authService.verify(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/api/v1/auth/login"))
                        .withoutHeader("X-User-Id"));
    }

    @Test
    @DisplayName("still forwards the Authorization header, so services can verify independently")
    void forwardsTheTokenDownstream() {
        String token = TOKENS.valid();

        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();

        // Identity headers are a convenience, not a replacement for a service checking the token.
        authService.verify(getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + token)));
    }

    // --- helpers ------------------------------------------------------------------------------

    private void stubDownstream() {
        authService.stubFor(get(urlPathEqualTo("/api/v1/auth/sessions"))
                .willReturn(aResponse().withStatus(200)));
    }

    private void expectAccepted(String token) {
        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void expectRefused(String token) {
        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private String bodyOf(String token) {
        return new String(webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());
    }

    /** Drops the per-request fields, leaving what actually describes the outcome. */
    private String withoutVaryingFields(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "").replaceAll("\"requestId\":\"[^\"]*\"", "");
    }

    /** Writes the revocation exactly as auth-service's TokenDenylist does. */
    private void revoke(String jti) {
        redis.opsForValue()
                .set("finpay:auth:revoked-token:" + jti, "revoked", Duration.ofMinutes(15))
                .block();
    }
}
