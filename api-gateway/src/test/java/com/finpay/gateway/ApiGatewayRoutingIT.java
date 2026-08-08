package com.finpay.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import com.finpay.platform.web.RequestCorrelation;

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
            "spring.cloud.discovery.client.simple.instances.auth-service[0].uri=http://localhost:${wiremock.port}",
            // Keys are fetched from the stubbed downstream, exactly as they are fetched from
            // auth-service in production.
            "finpay.gateway.jwt.jwk-set-uri=http://localhost:${wiremock.port}" + TestTokens.JWKS_PATH,
            "management.server.port=" + ApiGatewayRoutingIT.MANAGEMENT_PORT,
        })
// Spring Boot switches metrics export off inside @SpringBootTest; without this the
// Prometheus endpoint is simply not registered. Tracing stays off: no collector runs here.
@AutoConfigureObservability(tracing = false)
@Testcontainers
class ApiGatewayRoutingIT {

    /** Fixed so the test knows where actuator is; modules build sequentially. */
    static final String MANAGEMENT_PORT = "19193";

    private static WireMockServer authService;

    /** Real RS256 tokens: mocking the decoder would remove the behaviour under test. */
    private static final TestTokens TOKENS = new TestTokens();

    /**
     * The revocation denylist is consulted on every authenticated request and fails closed, so
     * the gateway genuinely cannot serve authenticated traffic without it.
     */
    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.0-alpine").withExposedPorts(6379);

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

        // Re-stubbed after every reset: the gateway fetches this the first time it has a token
        // to verify, and a missing key set would fail every request for a reason unrelated to
        // whatever the test is actually about.
        authService.stubFor(get(urlEqualTo(TestTokens.JWKS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOKENS.jwkSetJson())));

        // This suite is about routing, so every request carries a valid token by default.
        // GatewayAuthenticationIT covers which requests are allowed to go without one.
        webTestClient = webTestClient
                .mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.valid())
                .build();
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

    // --- request id ------------------------------------------------------------------------

    @Test
    @DisplayName("passes the request id to the downstream service so one id spans the whole call")
    void forwardsRequestIdDownstream() {
        authService.stubFor(get(urlPathEqualTo("/api/v1/auth/sessions"))
                .willReturn(aResponse().withStatus(200)));

        String returnedId = webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(RequestCorrelation.REQUEST_ID_HEADER)
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(RequestCorrelation.REQUEST_ID_HEADER);

        // The id the caller is given must be the same one the service was told about, otherwise
        // the two sets of log lines cannot be joined.
        authService.verify(getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions"))
                .withHeader(RequestCorrelation.REQUEST_ID_HEADER, equalTo(returnedId)));
    }

    @Test
    @DisplayName("returns the request id exactly once, not once per hop")
    void returnsRequestIdOnlyOnce() {
        authService.stubFor(get(urlPathEqualTo("/api/v1/auth/sessions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        // A downstream service stamps its own response, exactly as the shared
                        // servlet filter does in a real service.
                        .withHeader(RequestCorrelation.REQUEST_ID_HEADER, "from-downstream")));

        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(RequestCorrelation.REQUEST_ID_HEADER, "req-once")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(RequestCorrelation.REQUEST_ID_HEADER, "req-once");
    }

    @Test
    @DisplayName("adopts a caller-supplied request id and forwards that one")
    void adoptsAndForwardsInboundRequestId() {
        authService.stubFor(get(urlPathEqualTo("/api/v1/auth/sessions"))
                .willReturn(aResponse().withStatus(200)));

        webTestClient
                .get()
                .uri("/api/v1/auth/sessions")
                .header(RequestCorrelation.REQUEST_ID_HEADER, "req-client-42")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(RequestCorrelation.REQUEST_ID_HEADER, "req-client-42");

        authService.verify(getRequestedFor(urlPathEqualTo("/api/v1/auth/sessions"))
                .withHeader(RequestCorrelation.REQUEST_ID_HEADER, equalTo("req-client-42")));
    }

    // --- error envelope --------------------------------------------------------------------

    @Test
    @DisplayName("renders an unreachable service in the platform error envelope")
    void rendersUnavailableServiceInPlatformEnvelope() {
        webTestClient
                .get()
                .uri("/api/v1/wallets/me")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(503)
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.path")
                .isEqualTo("/api/v1/wallets/me")
                .jsonPath("$.requestId")
                .isNotEmpty()
                .jsonPath("$.timestamp")
                .isNotEmpty();
    }

    @Test
    @DisplayName("renders an unrouted path in the platform error envelope")
    void rendersNotFoundInPlatformEnvelope() {
        webTestClient
                .get()
                .uri("/api/v1/does-not-exist")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("RESOURCE_NOT_FOUND")
                .jsonPath("$.requestId")
                .isNotEmpty();
    }

    @Test
    @DisplayName("never names an internal host, class or exception in an error body")
    void errorBodyRevealsNoInternals() {
        String body = new String(webTestClient
                .get()
                .uri("/api/v1/wallets/me")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .returnResult()
                .getResponseBodyContent());

        assertThat(body)
                .doesNotContain("wallet-service")
                .doesNotContain("lb://")
                .doesNotContain("Exception")
                .doesNotContain("org.springframework")
                .doesNotContain("trace");
    }

    // --- API documentation -----------------------------------------------------------------

    @Test
    @DisplayName("proxies a service's OpenAPI document and rewrites to the service's own path")
    void proxiesServiceApiDocs() {
        authService.stubFor(get(urlEqualTo("/v3/api-docs"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"auth-service\"}}")));

        webTestClient
                .get()
                .uri("/v3/api-docs/auth-service")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.info.title")
                .isEqualTo("auth-service");

        // The service is asked for its own document path, not the aggregated one.
        authService.verify(getRequestedFor(urlEqualTo("/v3/api-docs")));
    }

    @Test
    @DisplayName("serves the aggregated Swagger UI at the edge")
    void servesAggregatedSwaggerUi() {
        webTestClient.get().uri("/swagger-ui.html").exchange().expectStatus().value(status -> assertThat(status)
                .isBetween(200, 399));

        // The page's configuration lists every service, so a reader never needs an internal
        // address to find an API.
        webTestClient
                .get()
                .uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.urls[?(@.name == 'auth-service')].url")
                .isEqualTo("/v3/api-docs/auth-service")
                .jsonPath("$.urls[?(@.name == 'wallet-service')].url")
                .isEqualTo("/v3/api-docs/wallet-service");
    }

    @Test
    @DisplayName("does not serve actuator on the public gateway port")
    void hidesActuatorFromThePublicPort() {
        // The gateway is the one port the outside world can reach. Actuator lists routes,
        // targets and metrics, so it lives on the unpublished management port instead.
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isNotFound();
        webTestClient
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("tells the downstream service where the caller actually came from")
    void stampsTheClientAddress() {
        authService.stubFor(
                post(urlEqualTo("/api/v1/auth/login")).willReturn(aResponse().withStatus(200)));

        webTestClient.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();

        // Downstream cannot work this out for itself: the connection always comes from the
        // gateway, so without this header every caller looks like the same client.
        authService.verify(postRequestedFor(urlEqualTo("/api/v1/auth/login"))
                .withHeader("X-Forwarded-For", matching("\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F:]+")));
    }

    @Test
    @DisplayName("discards a client-supplied X-Forwarded-For rather than trusting or appending to it")
    void doesNotTrustAForgedClientAddress() {
        authService.stubFor(
                post(urlEqualTo("/api/v1/auth/login")).willReturn(aResponse().withStatus(200)));

        webTestClient
                .post()
                .uri("/api/v1/auth/login")
                .header("X-Forwarded-For", "203.0.113.99")
                .exchange()
                .expectStatus()
                .isOk();

        // If the forged value survived - first in the list, which is the entry downstream reads -
        // a caller could mint a new address per request and take a fresh rate-limit allowance
        // every time, which would defeat auth throttling entirely.
        authService.verify(postRequestedFor(urlEqualTo("/api/v1/auth/login"))
                .withHeader("X-Forwarded-For", notContaining("203.0.113.99")));
    }

    @Test
    @DisplayName("serves health and metrics on the management port")
    void servesActuatorOnManagementPort() {
        assertThat(management("/actuator/health")).contains("\"status\":\"UP\"");
        assertThat(management("/actuator/health/liveness")).contains("UP");
        assertThat(management("/actuator/health/readiness")).contains("UP");
        assertThat(management("/actuator/prometheus"))
                .contains("jvm_memory_used_bytes")
                .contains("application=\"api-gateway\"");
    }

    private String management(String path) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        // The scrape endpoint produces text/plain; actuator answers an unmatched "produces"
        // with 404 rather than 406, so the accepted types are stated explicitly.
        headers.setAccept(java.util.List.of(
                org.springframework.http.MediaType.TEXT_PLAIN,
                org.springframework.http.MediaType.APPLICATION_JSON,
                org.springframework.http.MediaType.ALL));

        return new org.springframework.boot.test.web.client.TestRestTemplate()
                .exchange(
                        "http://localhost:" + MANAGEMENT_PORT + path,
                        org.springframework.http.HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(headers),
                        String.class)
                .getBody();
    }
}
