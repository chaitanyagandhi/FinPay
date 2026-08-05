package com.finpay.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Verifies that the registry actually performs its job - accepting a registration and serving it
 * back - rather than only that the application starts.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.user.name=" + ServiceRegistryIT.USERNAME,
            "spring.security.user.password=" + ServiceRegistryIT.PASSWORD,
            // Reads are served from a read-only cache that refreshes on a timer. Bypassing it
            // makes registration visible immediately, so the test asserts registry behaviour
            // rather than cache expiry.
            "eureka.server.use-read-only-response-cache=false"
        })
class ServiceRegistryIT {

    static final String USERNAME = "test-client";
    static final String PASSWORD = "test-secret";

    private static final String APP_NAME = "PROBE-SERVICE";
    private static final String INSTANCE_ID = "probe-service-1";

    private static final String REGISTRATION_BODY =
            """
            {
              "instance": {
                "instanceId": "%s",
                "hostName": "127.0.0.1",
                "app": "%s",
                "ipAddr": "127.0.0.1",
                "status": "UP",
                "port": { "$": 8080, "@enabled": "true" },
                "dataCenterInfo": {
                  "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                  "name": "MyOwn"
                }
              }
            }
            """
                    .formatted(INSTANCE_ID, APP_NAME);

    @Autowired
    private TestRestTemplate restTemplate;

    @AfterEach
    void deregisterProbeInstance() {
        authenticated()
                .exchange(
                        "/eureka/apps/{app}/{instanceId}",
                        HttpMethod.DELETE,
                        null,
                        String.class,
                        APP_NAME,
                        INSTANCE_ID);
    }

    @Test
    @DisplayName("rejects unauthenticated access to the registry API")
    void rejectsUnauthenticatedRegistryReads() {
        ResponseEntity<String> response = restTemplate.getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("rejects unauthenticated access to the dashboard")
    void rejectsUnauthenticatedDashboard() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("rejects wrong credentials")
    void rejectsWrongCredentials() {
        ResponseEntity<String> response =
                restTemplate.withBasicAuth(USERNAME, "not-the-password").getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("serves an empty registry to an authenticated client before anything registers")
    void servesRegistryToAuthenticatedClient() {
        ResponseEntity<String> response = getApplications();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("accepts a registration and serves the instance back")
    void registersAndServesInstance() {
        ResponseEntity<Void> registration = register();

        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<String> applications = getApplications();

            assertThat(applications.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(applications.getBody())
                    .as("the registered instance should appear in the registry")
                    .contains(APP_NAME)
                    .contains(INSTANCE_ID)
                    .contains("\"status\":\"UP\"");
        });
    }

    @Test
    @DisplayName("removes an instance once it deregisters")
    void deregistersInstance() {
        register();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(getApplications().getBody()).contains(INSTANCE_ID));

        ResponseEntity<String> deregistration = authenticated()
                .exchange(
                        "/eureka/apps/{app}/{instanceId}",
                        HttpMethod.DELETE,
                        null,
                        String.class,
                        APP_NAME,
                        INSTANCE_ID);

        assertThat(deregistration.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(getApplications().getBody()).doesNotContain(INSTANCE_ID));
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

    private ResponseEntity<Void> register() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return authenticated()
                .exchange(
                        "/eureka/apps/{app}",
                        HttpMethod.POST,
                        new HttpEntity<>(REGISTRATION_BODY, headers),
                        Void.class,
                        APP_NAME);
    }

    private ResponseEntity<String> getApplications() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        return authenticated().exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private TestRestTemplate authenticated() {
        return restTemplate.withBasicAuth(USERNAME, PASSWORD);
    }
}
