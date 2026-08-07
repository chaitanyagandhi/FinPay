package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.finpay.auth.dto.LoginRequest;
import com.finpay.auth.dto.RefreshRequest;
import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.platform.web.error.ApiError;

/**
 * Throttling the authentication endpoints.
 *
 * <p>Runs against the same Redis image compose runs, because the limiter's correctness is entirely
 * a matter of what Redis does with {@code INCR} and {@code EXPIRE}; a fake would only confirm that
 * the test's idea of Redis matches the code's.
 *
 * <p>Callers are distinguished by {@code X-Forwarded-For}, which is what the gateway sets, so each
 * test can act as a different client without needing different sockets.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finpay.auth.password.bcrypt-strength=4",
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "finpay.auth.rate-limit.enabled=true",
            "finpay.auth.rate-limit.requests=3",
            "finpay.auth.rate-limit.window=30s",
            "management.server.port=" + AuthRateLimitIT.MANAGEMENT_PORT
        })
@Testcontainers
class AuthRateLimitIT {

    static final String MANAGEMENT_PORT = "19199";

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.0-alpine").withExposedPorts(6379);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Stops the test client from retrying the very responses this suite is asserting on.
     *
     * <p>Apache HttpClient 5 is on the classpath transitively, so {@code TestRestTemplate} uses it,
     * and its default retry strategy treats <strong>429 as retryable</strong> and honours
     * {@code Retry-After}. Every assertion here therefore saw the response to a silent second
     * request made a full window later - by which time the window had reset and the call
     * succeeded. The symptom was a test that failed with "expected 429 but was 200" while the
     * service log showed the refusal happening exactly as intended.
     *
     * <p>The production behaviour is right and worth keeping: a well-behaved client backing off on
     * {@code Retry-After} is precisely what the header is for.
     */
    @BeforeEach
    void doNotRetryThrottledResponses() {
        restTemplate
                .getRestTemplate()
                .setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                        HttpClients.custom().disableAutomaticRetries().build()));
    }

    // --- refusing -----------------------------------------------------------------------------

    @Test
    @DisplayName("allows the configured number of requests and refuses the next one")
    void refusesOverTheLimit() {
        String caller = uniqueCaller();
        String email = register();

        for (int i = 0; i < 3; i++) {
            assertThat(loginExpectingFailure(email, "wrong-password", caller).getStatusCode())
                    .as("request %s should be within the allowance", i + 1)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<ApiError> refused = loginExpectingFailure(email, "wrong-password", caller);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getBody().code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("refuses a correct password too: the throttle is about the caller, not the credentials")
    void refusesEvenValidCredentials() {
        String caller = uniqueCaller();
        String email = register();

        exhaust(email, caller);

        assertThat(login(email, PASSWORD, caller).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("sends Retry-After so a client knows when to come back")
    void sendsRetryAfter() {
        String caller = uniqueCaller();
        String email = register();
        exhaust(email, caller);

        ResponseEntity<ApiError> refused = loginExpectingFailure(email, PASSWORD, caller);

        // Set on the response before the exception is thrown; this asserts it survives the
        // exception resolver rendering the body, which is not obvious and would silently regress.
        String retryAfter = refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive().isLessThanOrEqualTo(30L);
    }

    @Test
    @DisplayName("a refused request never reaches the handler, so it costs no password comparison")
    void refusedRequestDoesNoWork() {
        String caller = uniqueCaller();
        String email = register();
        exhaust(email, caller);
        long attemptsBefore = attemptsFor(email);

        login(email, PASSWORD, caller);

        // No attempt row means the request was turned away before the service ran. That is most of
        // the point: the expensive work is exactly what an attacker is trying to provoke.
        assertThat(attemptsFor(email)).isEqualTo(attemptsBefore);
    }

    // --- scoping ------------------------------------------------------------------------------

    @Test
    @DisplayName("counts each caller separately, so one attacker cannot lock everyone out")
    void countsPerCaller() {
        String attacker = uniqueCaller();
        String bystander = uniqueCaller();
        String email = register();

        exhaust(email, attacker);

        assertThat(login(email, PASSWORD, attacker).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login(email, PASSWORD, bystander).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("counts each endpoint separately, so exhausting sign-in leaves refresh usable")
    void countsPerEndpoint() {
        String caller = uniqueCaller();
        String email = register();

        // Spend the sign-in allowance, keeping the refresh token from the one call that worked.
        String refreshToken = (String) login(email, PASSWORD, caller).getBody().get("refreshToken");
        exhaust(email, caller);
        assertThat(login(email, PASSWORD, caller).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A session the caller legitimately holds must still be extendable.
        assertThat(refresh(refreshToken, caller).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("does not throttle the JWKS endpoint, which every verifier must be able to reach")
    void doesNotThrottleJwks() {
        String caller = uniqueCaller();

        // Well past the allowance. Throttling this would break token validation platform-wide
        // under exactly the load where it matters most.
        for (int i = 0; i < 8; i++) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/auth/.well-known/jwks.json",
                    HttpMethod.GET,
                    new HttpEntity<>(headersFor(caller)),
                    String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private String register() {
        String email = "user-" + UUID.randomUUID() + "@finpay.test";
        // Registration is itself rate limited, so each account is created as its own caller to
        // keep setup from consuming the allowance the test is about to measure.
        restTemplate.exchange(
                "/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(new RegistrationRequest(email, PASSWORD), headersFor(uniqueCaller())),
                Map.class);
        return email;
    }

    /** Uses up the whole allowance for one caller on the sign-in endpoint. */
    private void exhaust(String email, String caller) {
        for (int i = 0; i < 3; i++) {
            login(email, "wrong-password", caller);
        }
    }

    private ResponseEntity<Map> login(String email, String password, String caller) {
        return restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(new LoginRequest(email, password), headersFor(caller)),
                Map.class);
    }

    private ResponseEntity<ApiError> loginExpectingFailure(String email, String password, String caller) {
        return restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(new LoginRequest(email, password), headersFor(caller)),
                ApiError.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken, String caller) {
        return restTemplate.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(new RefreshRequest(refreshToken), headersFor(caller)),
                Map.class);
    }

    private HttpHeaders headersFor(String caller) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", caller);
        return headers;
    }

    /**
     * A distinct literal IP per caller.
     *
     * <p>Drawn from 10/8 so the space is large enough that two tests can never be handed the same
     * address and quietly share an allowance.
     */
    private static String uniqueCaller() {
        int n = COUNTER.incrementAndGet();
        return "10.%d.%d.%d".formatted(n >> 16 & 0xFF, n >> 8 & 0xFF, n & 0xFF);
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private long attemptsFor(String email) {
        return jdbc.queryForObject("select count(*) from login_attempts where email = ?", Long.class, email);
    }
}
