package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.finpay.auth.dto.LoginRequest;
import com.finpay.auth.dto.RefreshRequest;
import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.platform.web.error.ApiError;

/**
 * Refresh token rotation and reuse detection, against a real PostgreSQL.
 *
 * <p>Most of these assertions read the database rather than the response, because the interesting
 * behaviour of this feature is what it writes down. A rotation that returns the right JSON while
 * failing to spend the old token, or a reuse detection whose revocation is rolled back by the
 * rejection that triggered it, both look perfectly correct from the outside.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finpay.auth.password.bcrypt-strength=4",
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "management.server.port=" + RefreshTokenRotationIT.MANAGEMENT_PORT
        })
@Testcontainers
class RefreshTokenRotationIT {

    static final String MANAGEMENT_PORT = "19196";

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // --- issuing ------------------------------------------------------------------------------

    @Test
    @DisplayName("sign-in returns a refresh token alongside the access token")
    void signInIssuesARefreshToken() {
        ResponseEntity<Map> login = login(register());

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshToken(login)).isNotBlank();
        assertThat(login.getBody().get("refreshExpiresAt")).isNotNull();
    }

    @Test
    @DisplayName("stores only a hash of the refresh token, never the token itself")
    void storesOnlyAHash() {
        String token = refreshToken(login(register()));

        // The value handed to the client must not appear anywhere in the column that identifies
        // it. A database leak must not hand over working sessions.
        Long matchingToken =
                jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Long.class, token);
        assertThat(matchingToken).isZero();

        String storedHash = jdbc.queryForObject(
                "select token_hash from refresh_tokens where token_hash = ?", String.class, sha256Hex(token));
        assertThat(storedHash).hasSize(64).isNotEqualTo(token);
    }

    @Test
    @DisplayName("each sign-in starts an independent family, so one session does not end another")
    void eachSignInStartsItsOwnFamily() {
        String email = register();

        String first = refreshToken(login(email));
        String second = refreshToken(login(email));

        assertThat(familyOf(first)).isNotEqualTo(familyOf(second));

        // Ending one session must leave the other usable.
        logout(first);
        assertThat(refresh(second).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- rotation -----------------------------------------------------------------------------

    @Test
    @DisplayName("exchanges a refresh token for a new pair, keeping the family and chaining the rows")
    void rotatesTheToken() {
        String original = refreshToken(login(register()));

        ResponseEntity<Map> rotated = refresh(original);

        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshToken(rotated)).isNotBlank().isNotEqualTo(original);
        assertThat((String) rotated.getBody().get("accessToken")).isNotBlank();

        // The successor belongs to the same session and points back at what it replaced.
        assertThat(familyOf(refreshToken(rotated))).isEqualTo(familyOf(original));
        assertThat(jdbc.queryForObject(
                        "select previous_token_id from refresh_tokens where token_hash = ?",
                        UUID.class,
                        sha256Hex(refreshToken(rotated))))
                .isEqualTo(idOf(original));
    }

    @Test
    @DisplayName("spends the presented token, so it cannot be exchanged a second time")
    void spendsThePresentedToken() {
        String original = refreshToken(login(register()));

        refresh(original);

        assertThat(jdbc.queryForObject(
                        "select used_at from refresh_tokens where token_hash = ?",
                        java.sql.Timestamp.class,
                        sha256Hex(original)))
                .isNotNull();
    }

    // --- reuse detection ----------------------------------------------------------------------

    @Test
    @DisplayName("presenting a spent token is refused")
    void refusesASpentToken() {
        String original = refreshToken(login(register()));
        refresh(original);

        ResponseEntity<ApiError> replay = refreshExpectingFailure(original);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().code()).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    @DisplayName("reuse of a spent token revokes the whole family, and the revocation survives the rejection")
    void reuseRevokesTheFamily() {
        String original = refreshToken(login(register()));
        String successor = refreshToken(refresh(original));
        UUID family = familyOf(original);

        // The thief's copy of the original is presented after the real client already rotated.
        refreshExpectingFailure(original);

        // The whole family is revoked, not just the replayed token. This is the assertion that
        // catches the transaction bug: the rejection above throws, and if the revocation shared
        // that transaction it would have been rolled back and every count here would be zero.
        Long revoked = jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is not null",
                Long.class,
                family);
        assertThat(revoked).isEqualTo(2L);

        List<String> reasons = jdbc.queryForList(
                "select distinct revoked_reason from refresh_tokens where family_id = ?", String.class, family);
        assertThat(reasons).containsExactly("REUSE_DETECTED");

        // And the successor the legitimate client was holding is dead too, which is the point:
        // once two parties hold the chain there is no way to tell which one is the owner.
        assertThat(refreshExpectingFailure(successor).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a reused token and a token that never existed are indistinguishable to the caller")
    void reuseLooksLikeAnyOtherFailure() {
        String original = refreshToken(login(register()));
        refresh(original);

        ApiError afterReuse = refreshExpectingFailure(original).getBody();
        ApiError neverExisted =
                refreshExpectingFailure("not-a-token-that-was-ever-issued").getBody();

        // Everything a caller can see must match. Only the request id and the instant differ, and
        // both are per-request rather than per-outcome.
        assertThat(afterReuse.status()).isEqualTo(neverExisted.status());
        assertThat(afterReuse.code()).isEqualTo(neverExisted.code());
        assertThat(afterReuse.message()).isEqualTo(neverExisted.message());
        assertThat(afterReuse.error()).isEqualTo(neverExisted.error());
    }

    @Test
    @DisplayName("an expired token is refused without revoking the family")
    void refusesAnExpiredTokenWithoutRevokingTheFamily() {
        String token = refreshToken(login(register()));
        UUID family = familyOf(token);

        // Expiry is a clock fact, so the row is aged directly rather than waiting thirty days.
        // Both instants move: the schema requires expires_at > issued_at, so pushing only the
        // expiry into the past is refused - correctly, since such a row could never have existed.
        jdbc.update(
                """
                update refresh_tokens
                   set issued_at = now() - interval '31 days',
                       expires_at = now() - interval '1 minute'
                 where token_hash = ?
                """,
                sha256Hex(token));

        assertThat(refreshExpectingFailure(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Expiry is not evidence of theft: signing in again is the ordinary remedy, and it must
        // not cost the user their other sessions.
        Long revoked = jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is not null",
                Long.class,
                family);
        assertThat(revoked).isZero();
    }

    @Test
    @DisplayName("a revoked token is refused even though it was never spent")
    void refusesARevokedToken() {
        String token = refreshToken(login(register()));
        logout(token);

        assertThat(refreshExpectingFailure(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("rejects a blank refresh token as a bad request rather than an authentication failure")
    void rejectsABlankToken() {
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/v1/auth/refresh", new RefreshRequest("   "), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ------------------------------------------------------------------------------

    private String register() {
        String email = "user-" + UUID.randomUUID() + "@finpay.test";
        restTemplate.postForEntity("/api/v1/auth/register", new RegistrationRequest(email, PASSWORD), Map.class);
        return email;
    }

    private ResponseEntity<Map> login(String email) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD), Map.class);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        return restTemplate.postForEntity("/api/v1/auth/refresh", new RefreshRequest(refreshToken), Map.class);
    }

    private ResponseEntity<ApiError> refreshExpectingFailure(String refreshToken) {
        return restTemplate.postForEntity("/api/v1/auth/refresh", new RefreshRequest(refreshToken), ApiError.class);
    }

    private void logout(String refreshToken) {
        restTemplate.postForEntity(
                "/api/v1/auth/logout", new com.finpay.auth.dto.LogoutRequest(refreshToken), Void.class);
    }

    private String refreshToken(ResponseEntity<Map> response) {
        return (String) response.getBody().get("refreshToken");
    }

    private UUID familyOf(String refreshToken) {
        return jdbc.queryForObject(
                "select family_id from refresh_tokens where token_hash = ?", UUID.class, sha256Hex(refreshToken));
    }

    private UUID idOf(String refreshToken) {
        return jdbc.queryForObject(
                "select id from refresh_tokens where token_hash = ?", UUID.class, sha256Hex(refreshToken));
    }

    /**
     * The hash the service is expected to have stored.
     *
     * <p>Computed here independently rather than by calling the production code, so that a change
     * to how tokens are hashed shows up as a failure instead of being mirrored by the test.
     */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
