package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.ParseException;
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

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

import com.finpay.auth.dto.LoginRequest;
import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.platform.web.error.ApiError;

/**
 * Sign-in end to end.
 *
 * <p>The token is not merely inspected as a string: it is parsed and its signature verified
 * against the published JWKS, which is exactly what every other service will do with it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finpay.auth.password.bcrypt-strength=4",
            "eureka.client.enabled=false",
            // The test owns its configuration; reaching for a config server that is not
            // running would make the suite depend on the compose stack being up.
            "spring.cloud.config.enabled=false",
            "management.server.port=" + LoginIT.MANAGEMENT_PORT
        })
@Testcontainers
class LoginIT {

    static final String MANAGEMENT_PORT = "19195";

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the token --------------------------------------------------------------------------

    @Test
    @DisplayName("returns a bearer token that verifies against the published public key")
    void issuesAVerifiableToken() throws Exception {
        String email = register();

        ResponseEntity<Map> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("tokenType")).isEqualTo("Bearer");
        assertThat((Integer) response.getBody().get("expiresInSeconds")).isPositive();

        SignedJWT jwt = SignedJWT.parse((String) response.getBody().get("accessToken"));

        // Anyone holding only the public key must be able to verify it: that is what makes the
        // key pair worth the trouble over a shared secret.
        RSAKey publicKey = publishedKey(jwt.getHeader().getKeyID());
        assertThat(jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(publicKey)))
                .as("the token must verify against the key published at the JWKS endpoint")
                .isTrue();
    }

    @Test
    @DisplayName("carries the subject, roles, expiry and a unique token id")
    void tokenCarriesTheExpectedClaims() throws Exception {
        String email = register();

        SignedJWT jwt = SignedJWT.parse(accessToken(login(email, PASSWORD)));
        var claims = jwt.getJWTClaimsSet();

        assertThat(UUID.fromString(claims.getSubject())).isEqualTo(userIdFor(email));
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER");
        assertThat(claims.getIssuer()).isNotBlank();
        assertThat(claims.getAudience()).contains("finpay");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    }

    @Test
    @DisplayName("carries no personal data")
    void tokenCarriesNoPersonalData() throws Exception {
        String email = register();

        String token = accessToken(login(email, PASSWORD));

        // A JWT is only base64. Anything in it is readable by whoever intercepts it and by every
        // log that records an Authorization header.
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().toString()).doesNotContain(email);
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1])))
                .doesNotContain(email)
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("issues a distinct token id each time")
    void issuesDistinctTokenIds() throws Exception {
        String email = register();

        String first = SignedJWT.parse(accessToken(login(email, PASSWORD)))
                .getJWTClaimsSet()
                .getJWTID();
        String second = SignedJWT.parse(accessToken(login(email, PASSWORD)))
                .getJWTClaimsSet()
                .getJWTID();

        // Revoking one session must not revoke the others.
        assertThat(first).isNotEqualTo(second);
    }

    // --- failures ---------------------------------------------------------------------------

    @Test
    @DisplayName("rejects a wrong password without saying why")
    void rejectsWrongPassword() {
        String email = register();

        ResponseEntity<ApiError> response = loginExpectingFailure(email, "not-the-right-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("answers an unknown address exactly as it answers a wrong password")
    void unknownAddressIsIndistinguishable() {
        String registered = register();

        ResponseEntity<ApiError> wrongPassword = loginExpectingFailure(registered, "not-the-right-password");
        ResponseEntity<ApiError> unknownUser =
                loginExpectingFailure("nobody-" + UUID.randomUUID() + "@finpay.test", PASSWORD);

        // Identical status, code and message: this endpoint must not reveal which addresses have
        // accounts.
        assertThat(unknownUser.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(unknownUser.getBody().code())
                .isEqualTo(wrongPassword.getBody().code());
        assertThat(unknownUser.getBody().message())
                .isEqualTo(wrongPassword.getBody().message());
    }

    @Test
    @DisplayName("refuses a disabled account, still without saying why")
    void refusesDisabledAccount() {
        String email = register();
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

        ResponseEntity<ApiError> response = loginExpectingFailure(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
        // The real reason belongs in the audit trail, not the response.
        assertThat(latestAttemptReason(email)).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    @DisplayName("lets an unverified account sign in")
    void allowsPendingVerification() {
        String email = register();

        // Registration leaves the account PENDING_VERIFICATION. Verification gates what can be
        // done with money, not whether the owner can see the account at all.
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- the audit trail --------------------------------------------------------------------

    @Test
    @DisplayName("records a successful attempt with the caller's address")
    void recordsSuccessfulAttempt() {
        String email = register();

        restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD), Map.class);

        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT successful, failure_reason, user_id, ip_address FROM login_attempts WHERE email = ? ORDER BY attempted_at DESC LIMIT 1",
                email);
        assertThat(attempt.get("successful")).isEqualTo(true);
        assertThat(attempt.get("failure_reason")).isNull();
        assertThat(attempt.get("user_id")).isNotNull();
        assertThat(attempt.get("ip_address")).isNotNull();
    }

    @Test
    @DisplayName("records a failed attempt with the real reason")
    void recordsFailedAttempt() {
        String email = register();

        loginExpectingFailure(email, "not-the-right-password");

        assertThat(latestAttemptReason(email)).isEqualTo("BAD_PASSWORD");
    }

    @Test
    @DisplayName("records an attempt against an address that has no account")
    void recordsAttemptForUnknownAddress() {
        String unknown = "nobody-" + UUID.randomUUID() + "@finpay.test";

        loginExpectingFailure(unknown, PASSWORD);

        // No user to reference, but the attempt is exactly the kind worth noticing.
        Map<String, Object> attempt =
                jdbc.queryForMap("SELECT user_id, failure_reason FROM login_attempts WHERE email = ?", unknown);
        assertThat(attempt.get("user_id")).isNull();
        assertThat(attempt.get("failure_reason")).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    @DisplayName("counts consecutive failures and clears them on success")
    void tracksConsecutiveFailures() {
        String email = register();

        loginExpectingFailure(email, "wrong-one");
        loginExpectingFailure(email, "wrong-two");
        assertThat(failedAttemptCount(email)).isEqualTo(2);

        login(email, PASSWORD);

        // The counter means "consecutive failures", so ordinary typos spread over months never
        // accumulate into a lockout.
        assertThat(failedAttemptCount(email)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT last_login_at FROM users WHERE email = ?", java.sql.Timestamp.class, email))
                .isNotNull();
    }

    // --- the published key -------------------------------------------------------------------

    @Test
    @DisplayName("publishes the public key and never the private one")
    void publishesOnlyPublicKeyMaterial() {
        String jwks = restTemplate.getForObject("/api/v1/auth/.well-known/jwks.json", String.class);

        assertThat(jwks).contains("\"kty\":\"RSA\"").contains("\"n\":").contains("\"e\":");
        // "d" is the private exponent; "p", "q", "dp", "dq", "qi" are its factors. None may leak.
        assertThat(jwks)
                .doesNotContain("\"d\":")
                .doesNotContain("\"p\":")
                .doesNotContain("\"q\":")
                .doesNotContain("\"dp\":")
                .doesNotContain("\"qi\":");
    }

    // --- helpers ------------------------------------------------------------------------------

    private String register() {
        String email = "user-" + UUID.randomUUID() + "@finpay.test";
        restTemplate.postForEntity("/api/v1/auth/register", new RegistrationRequest(email, PASSWORD), Map.class);
        return email;
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), Map.class);
    }

    private ResponseEntity<ApiError> loginExpectingFailure(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), ApiError.class);
    }

    private String accessToken(ResponseEntity<Map> response) {
        return (String) response.getBody().get("accessToken");
    }

    private RSAKey publishedKey(String keyId) throws ParseException {
        String jwks = restTemplate.getForObject("/api/v1/auth/.well-known/jwks.json", String.class);
        List<com.nimbusds.jose.jwk.JWK> keys = JWKSet.parse(jwks).getKeys();

        return keys.stream()
                .filter(k -> keyId == null || keyId.equals(k.getKeyID()))
                .findFirst()
                .orElseThrow()
                .toRSAKey();
    }

    private UUID userIdFor(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String latestAttemptReason(String email) {
        return jdbc.queryForObject(
                "SELECT failure_reason FROM login_attempts WHERE email = ? ORDER BY attempted_at DESC LIMIT 1",
                String.class,
                email);
    }

    private int failedAttemptCount(String email) {
        return jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email = ?", Integer.class, email);
    }
}
