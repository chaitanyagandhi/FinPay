package com.finpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the contract the config server exposes to the rest of the platform: what it serves, to
 * whom, and what it refuses.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.user.name=" + ConfigServerIT.USERNAME,
            "spring.security.user.password=" + ConfigServerIT.PASSWORD
        })
class ConfigServerIT {

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
    @DisplayName("exposes health without authentication for container and probe checks")
    void exposesHealthAnonymously() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("does not expose other actuator endpoints anonymously")
    void hidesOtherActuatorEndpoints() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/env", String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
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
