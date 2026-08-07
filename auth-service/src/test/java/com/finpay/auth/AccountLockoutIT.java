package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.platform.web.error.ApiError;

/**
 * Account lockout after repeated failures.
 *
 * <p>The threshold is lowered to three so the suite states its intent in three lines rather than
 * five, and the lock is short so the expiry case does not need a clock abstraction.
 *
 * <p>Most assertions read {@code users} directly. Lockout is a write on a path that ends by
 * throwing, so "did the caller get a 401" proves nothing about whether the lock was actually
 * stored - which is exactly the bug this arrangement exists to prevent.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "finpay.auth.password.bcrypt-strength=4",
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "finpay.auth.rate-limit.enabled=false",
            "finpay.auth.lockout.max-failed-attempts=3",
            "finpay.auth.lockout.duration=2s",
            "management.server.port=" + AccountLockoutIT.MANAGEMENT_PORT
        })
@Testcontainers
class AccountLockoutIT {

    static final String MANAGEMENT_PORT = "19198";

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String WRONG = "not-the-right-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // --- counting -----------------------------------------------------------------------------

    @Test
    @DisplayName("counts each consecutive failure against the account")
    void countsFailures() {
        String email = register();

        loginExpectingFailure(email, WRONG);
        loginExpectingFailure(email, WRONG);

        assertThat(failedAttempts(email)).isEqualTo(2);
        assertThat(lockedUntil(email)).isNull();
    }

    @Test
    @DisplayName("a successful sign-in clears the count, so ordinary typos never accumulate into a lock")
    void successResetsTheCount() {
        String email = register();
        loginExpectingFailure(email, WRONG);
        loginExpectingFailure(email, WRONG);

        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(failedAttempts(email)).isZero();
        assertThat(lockedUntil(email)).isNull();
    }

    // --- locking ------------------------------------------------------------------------------

    @Test
    @DisplayName("locks the account once the threshold is reached, and the lock survives the rejection")
    void locksAtTheThreshold() {
        String email = register();

        loginExpectingFailure(email, WRONG);
        loginExpectingFailure(email, WRONG);
        loginExpectingFailure(email, WRONG);

        // The third failure ends by throwing. If the lock were written in that transaction it
        // would have been rolled back and this would be null - lockout would never engage, no
        // matter how many times an attacker guessed.
        assertThat(lockedUntil(email)).isNotNull();
        assertThat(failedAttempts(email)).isEqualTo(3);
    }

    @Test
    @DisplayName("refuses the correct password while the account is locked")
    void refusesTheCorrectPasswordWhileLocked() {
        String email = register();
        lockOut(email);

        // The password is right. The lock is what refuses it, which is the entire point: an
        // attacker who eventually guesses correctly still gets nothing.
        ResponseEntity<ApiError> response = loginExpectingFailure(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("a locked account is indistinguishable from a wrong password")
    void lockedAccountRevealsNothing() {
        String locked = register();
        lockOut(locked);
        String healthy = register();

        ApiError lockedResponse = loginExpectingFailure(locked, PASSWORD).getBody();
        ApiError wrongPassword = loginExpectingFailure(healthy, WRONG).getBody();

        // If a locked account answered differently, anyone with a list of addresses could discover
        // which of them are under attack - and, by implication, which exist.
        assertThat(lockedResponse.status()).isEqualTo(wrongPassword.status());
        assertThat(lockedResponse.code()).isEqualTo(wrongPassword.code());
        assertThat(lockedResponse.message()).isEqualTo(wrongPassword.message());
        assertThat(lockedResponse.error()).isEqualTo(wrongPassword.error());
    }

    @Test
    @DisplayName("records the reason as ACCOUNT_LOCKED where an operator can see it")
    void recordsTheRealReason() {
        String email = register();
        lockOut(email);

        loginExpectingFailure(email, PASSWORD);

        // The caller is told nothing; the attempt history is told everything.
        String reason = jdbc.queryForObject(
                "select failure_reason from login_attempts where email = ? order by attempted_at desc limit 1",
                String.class,
                email);
        assertThat(reason).isEqualTo("ACCOUNT_LOCKED");
    }

    // --- unlocking ----------------------------------------------------------------------------

    @Test
    @DisplayName("the lock expires on its own, so it cannot be used to deny someone their account")
    void lockExpires() {
        String email = register();
        lockOut(email);

        // Rather than sleeping out the two seconds, the stored expiry is moved into the past -
        // the same fact, without the wall-clock wait.
        jdbc.update("update users set locked_until = now() - interval '1 second' where email = ?", email);

        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lockedUntil(email)).isNull();
        assertThat(failedAttempts(email)).isZero();
    }

    @Test
    @DisplayName("an administrative LOCKED status is not cleared by the temporary lock expiring")
    void administrativeLockIsSeparate() {
        String email = register();
        jdbc.update("update users set status = 'LOCKED' where email = ?", email);

        // locked_until is null, so only the status refuses this. A temporary lockout and a
        // deliberate administrative lock are different decisions and must not overwrite each other.
        assertThat(loginExpectingFailure(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("select status from users where email = ?", String.class, email))
                .isEqualTo("LOCKED");
    }

    // --- helpers ------------------------------------------------------------------------------

    private String register() {
        String email = "user-" + UUID.randomUUID() + "@finpay.test";
        restTemplate.postForEntity("/api/v1/auth/register", new RegistrationRequest(email, PASSWORD), Map.class);
        return email;
    }

    /** Drives the account to the configured threshold of three consecutive failures. */
    private void lockOut(String email) {
        loginExpectingFailure(email, WRONG);
        loginExpectingFailure(email, WRONG);
        loginExpectingFailure(email, WRONG);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), Map.class);
    }

    private ResponseEntity<ApiError> loginExpectingFailure(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), ApiError.class);
    }

    private Integer failedAttempts(String email) {
        return jdbc.queryForObject("select failed_login_attempts from users where email = ?", Integer.class, email);
    }

    private java.sql.Timestamp lockedUntil(String email) {
        return jdbc.queryForObject("select locked_until from users where email = ?", java.sql.Timestamp.class, email);
    }
}
