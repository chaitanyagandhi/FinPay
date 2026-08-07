package com.finpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the contract the config server exposes to the rest of the platform: what it serves, to
 * whom, and what it refuses.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.user.name=" + ConfigServerIT.USERNAME,
            "spring.security.user.password=" + ConfigServerIT.PASSWORD,
            // Actuator binds its own random port here, mirroring the production split.
            "management.server.port=" + ConfigServerIT.MANAGEMENT_PORT,
            // No collector is running during tests.
        })
// Spring Boot switches metrics export off inside @SpringBootTest; without this the
// Prometheus endpoint is simply not registered. Tracing stays off: no collector runs here.
@AutoConfigureObservability(tracing = false)
class ConfigServerIT {

    /** Fixed so the test knows where actuator is; modules build sequentially. */
    static final String MANAGEMENT_PORT = "19191";

    static final String USERNAME = "test-client";
    static final String PASSWORD = "test-secret";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("rejects unauthenticated configuration requests")
    void rejectsUnauthenticatedRequests() {
        ResponseEntity<String> response = restTemplate.getForEntity("/application/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("rejects wrong credentials")
    void rejectsWrongCredentials() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth(USERNAME, "not-the-password")
                .getForEntity("/application/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("serves shared defaults to an authenticated client")
    void servesSharedDefaults() {
        Environment environment = fetch("/application/default");

        assertThat(environment.getName()).isEqualTo("application");
        assertThat(environment.getPropertySources()).isNotEmpty();

        // The financial-correctness and error-hiding defaults every service inherits.
        assertThat(property(environment, "spring.jackson.serialization.write-bigdecimal-as-plain"))
                .contains("true");
        assertThat(property(environment, "spring.jackson.time-zone")).contains("UTC");
        assertThat(property(environment, "spring.jackson.serialization.write-dates-as-timestamps"))
                .contains("false");
        assertThat(property(environment, "server.error.include-stacktrace")).contains("never");
        assertThat(property(environment, "server.shutdown")).contains("graceful");
    }

    @Test
    @DisplayName("serves the in-network broker address for the docker profile")
    void servesDockerProfileOverrides() {
        Environment environment = fetch("/application/docker");

        assertThat(environment.getProfiles()).containsExactly("docker");
        assertThat(property(environment, "spring.kafka.bootstrap-servers")).contains("kafka:9092");
        assertThat(property(environment, "spring.data.redis.host")).contains("redis");
        // Profile-specific files are layered on top of the shared defaults, not instead of them.
        assertThat(property(environment, "spring.jackson.time-zone")).contains("UTC");
    }

    @Test
    @DisplayName("serves the host-facing broker address for the local profile")
    void servesLocalProfileOverrides() {
        Environment environment = fetch("/application/local");

        assertThat(property(environment, "spring.kafka.bootstrap-servers")).contains("localhost:29092");
        assertThat(property(environment, "spring.data.redis.host")).contains("localhost");
    }

    @Test
    @DisplayName("never serves a resolved secret, only the placeholder")
    void servesPlaceholdersRatherThanSecrets() {
        Environment environment = fetch("/application/docker");

        // The server must hand over the unresolved placeholder so the value is supplied by the
        // consuming service's own environment.
        assertThat(property(environment, "spring.data.redis.password")).contains("${REDIS_PASSWORD:}");
    }

    @Test
    @DisplayName("does not serve actuator on the port clients reach")
    void hidesActuatorFromThePublicPort() {
        // Management endpoints live on their own port, which is never published. Reaching them
        // through the port that serves configuration would defeat that.
        //
        // Note this server maps /{application}/{profile}, so a path like /actuator/health on
        // this port is a configuration lookup for an application named "actuator" rather than
        // the actuator endpoint. Metrics are the unambiguous check.
        assertThat(restTemplate
                        .getForEntity("/actuator/prometheus", String.class)
                        .getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("serves health on the management port for container and probe checks")
    void servesHealthOnManagementPort() {
        ResponseEntity<String> response = management("/actuator/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("serves liveness and readiness probes")
    void servesProbes() {
        assertThat(management("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(management("/actuator/health/readiness").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("serves Prometheus metrics to an authenticated scraper")
    void servesPrometheusMetrics() {
        // The scrape endpoint produces text/plain. RestTemplate would otherwise ask for JSON,
        // and actuator answers an unmatched "produces" with 404 rather than 406.
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.ALL));

        ResponseEntity<String> response = new TestRestTemplate(USERNAME, PASSWORD)
                .exchange(
                        "http://localhost:" + MANAGEMENT_PORT + "/actuator/prometheus",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes").contains("application=\"config-server\"");
    }

    @Test
    @DisplayName("keeps metrics behind credentials while leaving probes open")
    void metricsRequireCredentialsButProbesDoNot() {
        assertThat(management("/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(management("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("exposes no actuator endpoint that was not asked for")
    void exposesOnlyTheRequestedEndpoints() {
        // /actuator/env would dump resolved configuration, including any secret placeholders
        // that had been resolved.
        assertThat(management("/actuator/env").getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(management("/actuator/beans").getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> management(String path) {
        return new TestRestTemplate().getForEntity("http://localhost:" + MANAGEMENT_PORT + path, String.class);
    }

    private Environment fetch(String path) {
        ResponseEntity<Environment> response =
                restTemplate.withBasicAuth(USERNAME, PASSWORD).getForEntity(path, Environment.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** Resolves a property across all served sources, honouring config server precedence order. */
    private Optional<String> property(Environment environment, String key) {
        for (PropertySource source : environment.getPropertySources()) {
            Map<?, ?> values = source.getSource();
            Object value = values.get(key);
            if (value != null) {
                return Optional.of(String.valueOf(value));
            }
        }
        return Optional.empty();
    }
}
