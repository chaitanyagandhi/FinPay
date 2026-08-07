package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.nimbusds.jwt.SignedJWT;

import com.finpay.auth.dto.LoginRequest;
import com.finpay.auth.dto.LogoutRequest;
import com.finpay.auth.dto.RefreshRequest;
import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.platform.web.error.ApiError;

/**
 * Ending a session.
 *
 * <p>Logout has to do two different things to two different kinds of token, and the tests are
 * split along that line: the refresh token's family is revoked in the database, and the access
 * token - which cannot be recalled - has its id written to the denylist.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finpay.auth.password.bcrypt-strength=4",
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "management.server.port=" + LogoutIT.MANAGEMENT_PORT
        })
@Testcontainers
class LogoutIT {

    static final String MANAGEMENT_PORT = "19197";

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the refresh token --------------------------------------------------------------------

    @Test
    @DisplayName("revokes the session's family and answers 204")
    void revokesTheFamily() {
        ResponseEntity<Map> login = login(register());
        String refreshToken = refreshToken(login);
        UUID family = familyOf(refreshToken);

        ResponseEntity<Void> response = logout(refreshToken, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Long revoked = jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_reason = 'LOGOUT'",
                Long.class,
                family);
        assertThat(revoked).isEqualTo(1L);
    }

    @Test
    @DisplayName("the session cannot be refreshed after logging out")
    void endsTheSession() {
        String refreshToken = refreshToken(login(register()));

        logout(refreshToken, null);

        ResponseEntity<ApiError> refreshed =
                restTemplate.postForEntity("/api/v1/auth/refresh", new RefreshRequest(refreshToken), ApiError.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("revokes the successor too, so a rotated session cannot outlive the logout")
    void revokesTheSuccessor() {
        String original = refreshToken(login(register()));
        String successor = refreshToken(
                restTemplate.postForEntity("/api/v1/auth/refresh", new RefreshRequest(original), Map.class));

        logout(successor, null);

        Long live = jdbc.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Long.class,
                familyOf(successor));
        assertThat(live).isZero();
    }

    @Test
    @DisplayName("answers 204 for a refresh token that never existed, revealing nothing")
    void unknownTokenLooksIdentical() {
        // Reporting "no such token" would turn logout into a way of asking whether a token is
        // genuine - useful to someone holding a value they are not sure is real.
        ResponseEntity<Void> response = logout("not-a-token-that-was-ever-issued", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("logging out twice is not an error")
    void isIdempotent() {
        ResponseEntity<Map> login = login(register());
        String refreshToken = refreshToken(login);
        String accessToken = accessToken(login);

        assertThat(logout(refreshToken, accessToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // The second call re-presents an access token whose jti is already denylisted. Without the
        // existence check that would violate the unique constraint on jti and surface as a 500.
        assertThat(logout(refreshToken, accessToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- the access token ---------------------------------------------------------------------

    @Test
    @DisplayName("denylists the presented access token by its jti")
    void denylistsTheAccessToken() throws Exception {
        ResponseEntity<Map> login = login(register());
        String accessToken = accessToken(login);
        String jti = SignedJWT.parse(accessToken).getJWTClaimsSet().getJWTID();

        logout(refreshToken(login), accessToken);

        Long denylisted = jdbc.queryForObject(
                "select count(*) from revoked_tokens where jti = ? and reason = 'LOGOUT'",
                Long.class,
                UUID.fromString(jti));
        assertThat(denylisted).isEqualTo(1L);
    }

    @Test
    @DisplayName("records the original expiry, so the denylist can be purged rather than growing forever")
    void recordsTheExpiryForPurging() throws Exception {
        ResponseEntity<Map> login = login(register());
        String accessToken = accessToken(login);
        UUID jti =
                UUID.fromString(SignedJWT.parse(accessToken).getJWTClaimsSet().getJWTID());

        logout(refreshToken(login), accessToken);

        java.sql.Timestamp expiresAt = jdbc.queryForObject(
                "select expires_at from revoked_tokens where jti = ?", java.sql.Timestamp.class, jti);
        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt.toInstant())
                .isEqualTo(SignedJWT.parse(accessToken)
                        .getJWTClaimsSet()
                        .getExpirationTime()
                        .toInstant());
    }

    @Test
    @DisplayName("denylists nothing when no access token is presented")
    void denylistsNothingWithoutABearerToken() {
        long before = denylistSize();

        logout(refreshToken(login(register())), null);

        assertThat(denylistSize()).isEqualTo(before);
    }

    @Test
    @DisplayName("refuses to denylist a token that does not verify")
    void ignoresAForgedAccessToken() {
        long before = denylistSize();

        // Signed by nobody. Trusting the jti out of an unverified token would let anyone revoke
        // anyone else's access token by handing over a forgery.
        String forged = "eyJhbGciOiJub25lIn0"
                + ".eyJqdGkiOiI3ZjhlOGY0Ni0xMTFhLTRkMWQtOGY2YS0wMDAwMDAwMDAwMDAiLCJzdWIiOiJhIn0"
                + ".";

        assertThat(logout(refreshToken(login(register())), forged).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(denylistSize()).isEqualTo(before);
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

    private ResponseEntity<Void> logout(String refreshToken, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }

        return restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(new LogoutRequest(refreshToken), headers),
                Void.class);
    }

    private String refreshToken(ResponseEntity<Map> response) {
        return (String) response.getBody().get("refreshToken");
    }

    private String accessToken(ResponseEntity<Map> response) {
        return (String) response.getBody().get("accessToken");
    }

    private UUID familyOf(String refreshToken) {
        return jdbc.queryForObject(
                "select family_id from refresh_tokens where token_hash = ?", UUID.class, sha256Hex(refreshToken));
    }

    private long denylistSize() {
        return jdbc.queryForObject("select count(*) from revoked_tokens", Long.class);
    }

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
