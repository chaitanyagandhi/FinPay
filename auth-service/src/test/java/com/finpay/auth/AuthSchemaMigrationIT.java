package com.finpay.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the auth schema against a real PostgreSQL of the version compose runs.
 *
 * <p>The point is not that the SQL parses. It is that the constraints actually refuse the writes
 * they exist to refuse: a duplicate email, a plaintext password, a role that does not exist, a
 * refresh token that expires before it was issued. A migration that creates tables but forgets a
 * constraint looks identical to a correct one until the day it matters.
 */
@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            // The test owns its configuration; reaching for a config server that is not
            // running would make the suite depend on the compose stack being up.
            "spring.cloud.config.enabled=false"
        })
@Testcontainers
class AuthSchemaMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    // --- migration state --------------------------------------------------------------------

    @Test
    @DisplayName("applies the baseline migration successfully")
    void appliesBaselineMigration() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank", String.class);

        assertThat(applied).contains("1");
    }

    @ParameterizedTest(name = "creates {0}")
    @DisplayName("creates every table the service owns")
    @ValueSource(strings = {"users", "user_roles", "credentials", "refresh_tokens", "login_attempts", "revoked_tokens"})
    void createsExpectedTables(String table) {
        assertThat(tableExists(table)).isTrue();
    }

    @Test
    @DisplayName("stores every timestamp with a time zone")
    void storesTimestampsWithTimeZone() {
        // A timestamp without a zone is ambiguous the moment two deployments disagree about
        // local time, and this platform reconciles money across services.
        // flyway_schema_history is excluded: Flyway creates installed_on without a zone and we
        // do not control its DDL. Every column this migration creates is in scope.
        List<String> naive = jdbc.queryForList(
                """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name <> 'flyway_schema_history'
                  AND data_type = 'timestamp without time zone'
                """,
                String.class);

        assertThat(naive).isEmpty();
    }

    // --- users ------------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a second account with the same email")
    void refusesDuplicateEmail() {
        insertUser("duplicate@finpay.test");

        assertThatThrownBy(() -> insertUser("duplicate@finpay.test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses an email that is not stored lower-cased")
    void refusesMixedCaseEmail() {
        // Two accounts differing only in case would let one person take the other's identifier.
        assertThatThrownBy(() -> insertUser("Mixed.Case@finpay.test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses an unknown account status")
    void refusesUnknownStatus() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO users (id, email, status) VALUES (?, ?, ?)",
                        UUID.randomUUID(),
                        "status@finpay.test",
                        "NOT_A_STATUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a negative failed-attempt count")
    void refusesNegativeFailedAttempts() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO users (id, email, failed_login_attempts) VALUES (?, ?, ?)",
                        UUID.randomUUID(),
                        "negative@finpay.test",
                        -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("keeps updated_at current without the application setting it")
    void maintainsUpdatedAtByTrigger() {
        UUID userId = insertUser("trigger@finpay.test");
        // Move the row's timestamps into the past so any change is unambiguous.
        jdbc.update(
                "UPDATE users SET created_at = now() - interval '1 hour', updated_at = now() - interval '1 hour' WHERE id = ?",
                userId);

        jdbc.update("UPDATE users SET email_verified = true WHERE id = ?", userId);

        Boolean refreshed =
                jdbc.queryForObject("SELECT updated_at > created_at FROM users WHERE id = ?", Boolean.class, userId);
        assertThat(refreshed).as("the trigger should have advanced updated_at").isTrue();
    }

    // --- roles ------------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a role outside the defined set")
    void refusesUnknownRole() {
        UUID userId = insertUser("role@finpay.test");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, "ROOT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses the same role granted twice")
    void refusesDuplicateRoleGrant() {
        UUID userId = insertUser("duplicate-role@finpay.test");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, "USER");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("allows a user to hold more than one role")
    void allowsMultipleRoles() {
        UUID userId = insertUser("admin@finpay.test");

        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, "USER");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, "ADMIN");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
    }

    // --- credentials ------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a password short enough to be plaintext")
    void refusesPlaintextLookingPassword() {
        UUID userId = insertUser("plaintext@finpay.test");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO credentials (id, user_id, password_hash) VALUES (?, ?, ?)",
                        UUID.randomUUID(),
                        userId,
                        "hunter2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a second credential for the same user")
    void refusesSecondCredential() {
        UUID userId = insertUser("two-passwords@finpay.test");
        insertCredential(userId);

        assertThatThrownBy(() -> insertCredential(userId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("removes the credential when the user is deleted")
    void cascadesCredentialDeletion() {
        UUID userId = insertUser("cascade@finpay.test");
        insertCredential(userId);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM credentials WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    // --- refresh tokens ---------------------------------------------------------------------

    @Test
    @DisplayName("refuses a token that expires before it was issued")
    void refusesExpiryBeforeIssue() {
        UUID userId = insertUser("expiry@finpay.test");

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, issued_at, expires_at)
                        VALUES (?, ?, ?, ?, now(), now() - interval '1 day')
                        """,
                        UUID.randomUUID(),
                        userId,
                        "hash-" + UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses two tokens with the same hash")
    void refusesDuplicateTokenHash() {
        UUID userId = insertUser("token-dup@finpay.test");
        String hash = "hash-" + UUID.randomUUID();
        insertRefreshToken(userId, hash);

        assertThatThrownBy(() -> insertRefreshToken(userId, hash)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("links a rotated token to the one it replaced")
    void linksRotatedTokens() {
        UUID userId = insertUser("rotation@finpay.test");
        UUID first = insertRefreshToken(userId, "hash-" + UUID.randomUUID());

        UUID second = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, previous_token_id, expires_at)
                VALUES (?, ?, ?, (SELECT family_id FROM refresh_tokens WHERE id = ?), ?, now() + interval '30 days')
                """,
                second,
                userId,
                "hash-" + UUID.randomUUID(),
                first,
                first);

        // A rotation chain shares one family, which is what lets reuse revoke all of it at once.
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT family_id) FROM refresh_tokens WHERE user_id = ?",
                        Integer.class,
                        userId))
                .isEqualTo(1);
    }

    // --- login attempts ---------------------------------------------------------------------

    @Test
    @DisplayName("records an attempt against an address that has no account")
    void recordsAttemptForUnknownEmail() {
        // These are the attempts most worth noticing, and they have no user to reference.
        jdbc.update(
                "INSERT INTO login_attempts (id, email, successful, failure_reason) VALUES (?, ?, false, ?)",
                UUID.randomUUID(),
                "nobody@finpay.test",
                "USER_NOT_FOUND");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM login_attempts WHERE email = ?", Integer.class, "nobody@finpay.test"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a successful attempt that also carries a failure reason")
    void refusesContradictoryAttempt() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO login_attempts (id, email, successful, failure_reason) VALUES (?, ?, true, ?)",
                        UUID.randomUUID(),
                        "contradiction@finpay.test",
                        "BAD_PASSWORD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("keeps the attempt history when the user is deleted")
    void retainsAttemptsAfterUserDeletion() {
        UUID userId = insertUser("retained@finpay.test");
        jdbc.update(
                "INSERT INTO login_attempts (id, user_id, email, successful) VALUES (?, ?, ?, true)",
                UUID.randomUUID(),
                userId,
                "retained@finpay.test");

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        // The row survives with a null user: deleting an account must not erase the security
        // record of how it was accessed.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM login_attempts WHERE email = ?", Integer.class, "retained@finpay.test"))
                .isEqualTo(1);
    }

    // --- revoked tokens ---------------------------------------------------------------------

    @Test
    @DisplayName("refuses the same token id revoked twice")
    void refusesDuplicateJti() {
        UUID jti = UUID.randomUUID();
        insertRevokedToken(jti);

        assertThatThrownBy(() -> insertRevokedToken(jti)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- indexes ----------------------------------------------------------------------------

    @ParameterizedTest(name = "creates {0}")
    @DisplayName("creates the indexes the hot-path queries depend on")
    @ValueSource(
            strings = {
                "idx_users_status",
                "idx_users_locked_until",
                "idx_refresh_tokens_user_active",
                "idx_refresh_tokens_family",
                "idx_refresh_tokens_expires_at",
                "idx_login_attempts_email_time",
                "idx_login_attempts_user_time",
                "idx_login_attempts_failures",
                "idx_revoked_tokens_expires_at",
                "idx_revoked_tokens_user"
            })
    void createsExpectedIndexes(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class,
                indexName);

        assertThat(count).isEqualTo(1);
    }

    // --- helpers ----------------------------------------------------------------------------

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class,
                table);
        return count != null && count == 1;
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, email);
        return id;
    }

    private void insertCredential(UUID userId) {
        jdbc.update(
                "INSERT INTO credentials (id, user_id, password_hash) VALUES (?, ?, ?)",
                UUID.randomUUID(),
                userId,
                "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
    }

    private UUID insertRefreshToken(UUID userId, String tokenHash) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at)
                VALUES (?, ?, ?, ?, now() + interval '30 days')
                """,
                id,
                userId,
                tokenHash,
                UUID.randomUUID());
        return id;
    }

    private void insertRevokedToken(UUID jti) {
        jdbc.update(
                "INSERT INTO revoked_tokens (id, jti, expires_at) VALUES (?, ?, now() + interval '15 minutes')",
                UUID.randomUUID(),
                jti);
    }
}
