package com.finpay.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Exercises the gateway against a real downstream HTTP server.
 *
 * <p>The production route table is used unchanged. Rather than pointing a route at a fixed test
 * address, the {@code auth-service} instance is published through Spring Cloud's simple discovery
 * client, so the {@code lb://auth-service} target resolves exactly as it would against the registry.
 * That keeps load-balanced resolution, path forwarding and the internal-path block all under test
 * instead of only the parts a hard-coded URI would reach.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Eureka is replaced by the static discovery client below; the gateway must not try
            // to reach a registry that is not running.
            "eureka.client.enabled=false",
            "spring.cloud.discovery.client.simple.instances.auth-service[0].uri=http://localhost:${wiremock.port}"
        })
class ApiGatewayRoutingIT {

    private static WireMockServer authService;

    @Autowired
    private WebTestClient webTestClient;

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
    }

    @Test
    @DisplayName("forwards a public request to the service that owns the path")
    void forwardsToOwningService() {
        authService.stubFor(post(urlEqualTo("/api/v1/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"stub\"}")));

        webTestClient
                .post()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accessToken")
                .isEqualTo("stub");

        // The downstream must receive the original path: the gateway routes, it does not rewrite.
        authService.verify(
                1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/api/v1/auth/login")));
    }

    @Test
    @DisplayName("preserves the path, query string and request headers")
    void preservesRequestDetails() {
        authService.stubFor(get(urlPathEqualTo("/api/v1/auth/sessions"))
                .willReturn(aResponse().withStatus(200)));

        webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/auth/sessions")
                        .queryParam("page", "2")
                        .build())
                .header("X-Client-Version", "1.4.0")
                .exchange()
                .expectStatus()
                .isOk();

        authService.verify(getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions"))
                .withQueryParam("page", equalTo("2"))
                .withHeader("X-Client-Version", equalTo("1.4.0")));
    }

    @Test
    @DisplayName("propagates the downstream status code rather than masking it")
    void propagatesDownstreamStatus() {
        authService.stubFor(
                post(urlEqualTo("/api/v1/auth/login")).willReturn(aResponse().withStatus(401)));

        webTestClient.post().uri("/api/v1/auth/login").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("refuses service-internal paths without contacting any service")
    void refusesInternalPaths() {
        webTestClient
                .post()
                .uri("/internal/v1/wallets/11111111-1111-1111-1111-111111111111/reserve")
                .exchange()
                .expectStatus()
                .isNotFound();

        // Nothing may reach a downstream service: the request is stopped at the edge.
        authService.verify(
                0,
                com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                        com.github.tomakehurst.wiremock.matching.UrlPattern.ANY));
    }

    @Test
    @DisplayName("refuses a traversal that spells an internal path indirectly")
    void refusesTraversalToInternalPaths() {
        webTestClient
                .post()
                .uri("/api/v1/auth/../../../internal/v1/wallets/1/credit")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("returns 404 for a path no route claims")
    void rejectsUnmappedPaths() {
        webTestClient
                .get()
                .uri("/api/v1/does-not-exist")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("returns 503 when a routed service has no running instance")
    void reportsUnavailableServiceWhenNoInstanceIsRegistered() {
        // wallet-service is routed but nothing is registered for it in this test.
        webTestClient.get().uri("/api/v1/wallets/me").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    @DisplayName("serves its own health check without routing it")
    void servesHealthLocally() {
        webTestClient
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }
}
