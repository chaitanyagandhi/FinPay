package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.platform.web.RequestCorrelation;
import com.finpay.platform.web.error.ApiError;

/**
 * Registration end to end, against a real PostgreSQL.
 *
 * <p>Asserts what a caller receives, what actually lands in the database, and - as much as
 * anything - what never appears in either.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // BCrypt's production cost would add roughly a quarter of a second to every case.
            "finpay.auth.password.bcrypt-strength=4",
            "eureka.client.enabled=false",
            "management.server.port=" + RegistrationIT.MANAGEMENT_PORT
        })
@Testcontainers
class RegistrationIT {

    static final String MANAGEMENT_PORT = "19194";

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // --- the happy path ---------------------------------------------------------------------

    @Test
    @DisplayName("creates an account and returns its identifier")
    void registersAnAccount() {
        String email = uniqueEmail();

        ResponseEntity<Map> response = post(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("email")).isEqualTo(email);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(UUID.fromString((String) response.getBody().get("userId"))).isNotNull();
        assertThat(response.getHeaders().getLocation()).isNotNull();
    }

    @Test
    @DisplayName("writes the user, its role and its credential")
    void writesAllThreeRows() {
        String email = uniqueEmail();

        post(email, PASSWORD);

        UUID userId = userIdFor(email);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForList("SELECT role FROM user_roles WHERE user_id = ?", String.class, userId))
                .containsExactly("USER");
        assertThat(jdbc.queryForObject("SELECT algorithm FROM credentials WHERE user_id = ?", String.class, userId))
                .isEqualTo("BCRYPT");
    }

    @Test
    @DisplayName("stores a verifiable hash, and nothing resembling the password")
    void storesOnlyAHash() {
        String email = uniqueEmail();

        post(email, PASSWORD);

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM credentials WHERE user_id = ?", String.class, userIdFor(email));

        assertThat(hash)
                .isNotNull()
                .isNotEqualTo(PASSWORD)
                .doesNotContain(PASSWORD)
                .startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    @DisplayName("returns neither the password nor its hash")
    void responseCarriesNoSecret() {
        String email = uniqueEmail();

        ResponseEntity<String> raw =
                restTemplate.postForEntity("/api/v1/auth/register", body(email, PASSWORD), String.class);

        assertThat(raw.getBody())
                .doesNotContain(PASSWORD)
                .doesNotContain("$2")
                .doesNotContain("password")
                .doesNotContain("hash");
    }

    @Test
    @DisplayName("normalises the address before storing it")
    void normalisesEmail() {
        String email = uniqueEmail();

        post("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ", PASSWORD);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    // --- conflicts --------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a second account for the same address")
    void refusesDuplicate() {
        String email = uniqueEmail();
        post(email, PASSWORD);

        ResponseEntity<ApiError> conflict =
                restTemplate.postForEntity("/api/v1/auth/register", body(email, PASSWORD), ApiError.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).isNotNull();
        assertThat(conflict.getBody().code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(conflict.getBody().requestId()).isNotBlank();
        // The endpoint must not confirm which addresses exist.
        assertThat(conflict.getBody().message()).doesNotContain(email);
    }

    @Test
    @DisplayName("treats a differently-cased address as the same account")
    void refusesDuplicateIgnoringCase() {
        String email = uniqueEmail();
        post(email, PASSWORD);

        ResponseEntity<ApiError> conflict = restTemplate.postForEntity(
                "/api/v1/auth/register", body(email.toUpperCase(java.util.Locale.ROOT), PASSWORD), ApiError.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    // --- validation -------------------------------------------------------------------------

    @ParameterizedTest(name = "{2}")
    @DisplayName("rejects invalid input with the platform error envelope")
    @CsvSource({
        "not-an-email,        correct-horse-battery-staple, malformed address",
        "'',                  correct-horse-battery-staple, blank address",
        "valid@finpay.test,   short,                        password below the minimum",
        "valid@finpay.test,   '',                           blank password"
    })
    void rejectsInvalidInput(String email, String password, String description) {
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/v1/auth/register", body(email, password), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("rejects a password longer than BCrypt actually hashes")
    void rejectsPasswordBeyondBcryptLimit() {
        // BCrypt ignores everything past 72 bytes. Accepting a longer password would mean two
        // different passwords sharing a 72-byte prefix both open the same account.
        String tooLong = "a".repeat(RegistrationRequest.MAX_PASSWORD_LENGTH + 1);

        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/v1/auth/register", body(uniqueEmail(), tooLong), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("writes nothing when the request is rejected")
    void rejectedRequestWritesNothing() {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        restTemplate.postForEntity("/api/v1/auth/register", body("not-an-email", "short"), ApiError.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class))
                .isEqualTo(before);
    }

    // --- platform wiring ---------------------------------------------------------------------

    @Test
    @DisplayName("carries a request id on every response, inherited from the shared module")
    void carriesRequestId() {
        ResponseEntity<Map> response = post(uniqueEmail(), PASSWORD);

        assertThat(response.getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER))
                .isNotBlank();
    }

    @Test
    @DisplayName("publishes an OpenAPI document describing the endpoint")
    void publishesApiDocumentation() {
        String docs = restTemplate.getForObject("/v3/api-docs", String.class);

        assertThat(docs).contains("/api/v1/auth/register").contains("ApiError").contains("bearerAuth");
    }

    // --- helpers ------------------------------------------------------------------------------

    private ResponseEntity<Map> post(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/register", body(email, password), Map.class);
    }

    private RegistrationRequest body(String email, String password) {
        return new RegistrationRequest(email, password);
    }

    private UUID userIdFor(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /** Each test gets its own address: the container and its schema are shared across the class. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@finpay.test";
    }
}
