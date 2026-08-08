package com.finpay.user;

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
 * Verifies the user schema against a real PostgreSQL of the version compose runs.
 *
 * <p>As with the auth schema, the point is not that the SQL parses but that the constraints refuse
 * the writes they exist to refuse: a phone number saved twice, a number in a second spelling, a
 * beneficiary who is the owner, a KYC rejection with no reason. A migration that creates tables and
 * forgets a constraint looks identical to a correct one until the day it matters.
 */
@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
        })
@Testcontainers
class UserSchemaMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    // --- migration state ----------------------------------------------------------------------

    @Test
    @DisplayName("applies the baseline migration successfully")
    void appliesBaselineMigration() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank", String.class);

        assertThat(applied).contains("1");
    }

    @ParameterizedTest(name = "creates {0}")
    @DisplayName("creates every table the service owns")
    @ValueSource(strings = {"user_profiles", "user_preferences", "beneficiaries", "kyc_records"})
    void createsExpectedTables(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class,
                table);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("stores every timestamp with a time zone")
    void storesTimestampsWithTimeZone() {
        // flyway_schema_history is excluded: Flyway creates installed_on without a zone and we do
        // not control its DDL.
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

    // --- user_profiles ------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a second profile with the same phone number")
    void refusesDuplicatePhoneNumber() {
        insertProfile(UUID.randomUUID(), "+441632960961");

        // Two profiles on one number would let a payee search match two people, and a payer
        // choose the wrong one.
        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), "+441632960961"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest(name = "refuses {0}")
    @DisplayName("refuses a phone number that is not E.164")
    @ValueSource(strings = {"07700900461", "+0044123456789", "+44 1632 960961", "441632960961", "+44163296096123456"})
    void refusesNonE164PhoneNumbers(String phoneNumber) {
        // One number must have exactly one spelling, or the uniqueness constraint above protects
        // nothing.
        assertThatThrownBy(() -> insertProfile(UUID.randomUUID(), phoneNumber))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("accepts a well-formed E.164 number")
    void acceptsE164PhoneNumber() {
        // The negative cases above prove nothing unless a valid value is actually accepted.
        UUID userId = UUID.randomUUID();
        insertProfile(userId, "+14155552671");

        assertThat(jdbc.queryForObject(
                        "SELECT phone_number FROM user_profiles WHERE user_id = ?", String.class, userId))
                .isEqualTo("+14155552671");
    }

    @Test
    @DisplayName("refuses a country code that is not two upper-case letters")
    void refusesMalformedCountryCode() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_profiles (user_id, country_code) VALUES (?, ?)", UUID.randomUUID(), "gb"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a display name that is only whitespace")
    void refusesBlankDisplayName() {
        // A name of spaces is not a name, and it would sort and match unpredictably in the
        // directory.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_profiles (user_id, display_name) VALUES (?, ?)", UUID.randomUUID(), "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("keeps updated_at current without the application setting it")
    void maintainsUpdatedAtByTrigger() {
        UUID userId = UUID.randomUUID();
        insertProfile(userId, null);
        jdbc.update(
                "UPDATE user_profiles SET created_at = now() - interval '1 hour', updated_at = now() - interval '1 hour' WHERE user_id = ?",
                userId);

        jdbc.update("UPDATE user_profiles SET display_name = 'ada' WHERE user_id = ?", userId);

        Boolean refreshed = jdbc.queryForObject(
                "SELECT updated_at > created_at FROM user_profiles WHERE user_id = ?", Boolean.class, userId);
        assertThat(refreshed).as("the trigger should have advanced updated_at").isTrue();
    }

    // --- beneficiaries ------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a beneficiary who is the owner")
    void refusesSelfAsBeneficiary() {
        UUID owner = UUID.randomUUID();
        insertProfile(owner, null);

        // Paying yourself is not a transfer; it can only be a mistake or a probe, and letting it
        // be saved means the payment service has to handle it later.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO beneficiaries (id, owner_user_id, beneficiary_user_id) VALUES (?, ?, ?)",
                        UUID.randomUUID(),
                        owner,
                        owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses the same payee saved twice by one owner")
    void refusesDuplicateBeneficiary() {
        UUID owner = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        insertProfile(payee, null);
        insertBeneficiary(owner, payee);

        // Two identical entries make "which one did I pay" ambiguous in the one place it must not be.
        assertThatThrownBy(() -> insertBeneficiary(owner, payee)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a payee who has no profile")
    void refusesPayeeWithoutProfile() {
        // V2's foreign key. A payee with no profile has no name, and a payment confirmation that
        // cannot say who is being paid is worse than no confirmation at all.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO beneficiaries (id, owner_user_id, beneficiary_user_id) VALUES (?, ?, ?)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("does not require the owner to have a profile")
    void ownerNeedsNoProfile() {
        // Deliberately asymmetric: gating a private action - saving a payee - on a public one -
        // filling in your own profile - would be a rule with no purpose behind it.
        UUID payee = UUID.randomUUID();
        insertProfile(payee, null);

        jdbc.update(
                "INSERT INTO beneficiaries (id, owner_user_id, beneficiary_user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(),
                UUID.randomUUID(),
                payee);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM beneficiaries WHERE beneficiary_user_id = ?", Integer.class, payee))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("removes a payee from every list when their profile is deleted")
    void cascadesProfileDeletion() {
        UUID payee = UUID.randomUUID();
        insertProfile(payee, null);
        insertBeneficiary(UUID.randomUUID(), payee);

        jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", payee);

        // Otherwise the entry lingers as somebody nobody can render or pay.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM beneficiaries WHERE beneficiary_user_id = ?", Integer.class, payee))
                .isZero();
    }

    @Test
    @DisplayName("allows two owners to save the same payee")
    void allowsSharedPayee() {
        UUID payee = UUID.randomUUID();
        insertProfile(payee, null);

        insertBeneficiary(UUID.randomUUID(), payee);
        insertBeneficiary(UUID.randomUUID(), payee);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM beneficiaries WHERE beneficiary_user_id = ?", Integer.class, payee))
                .isEqualTo(2);
    }

    // --- kyc_records --------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a decided check with no decision date")
    void refusesDecisionWithoutDate() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO kyc_records (id, user_id, status) VALUES (?, ?, 'APPROVED')",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a pending check that already has a decision date")
    void refusesPendingWithDecisionDate() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO kyc_records (id, user_id, status, decided_at) VALUES (?, ?, 'PENDING', now())",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses a rejection that does not say why")
    void refusesRejectionWithoutReason() {
        // A rejection nobody can explain is one support cannot act on.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO kyc_records (id, user_id, status, decided_at) VALUES (?, ?, 'REJECTED', now())",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("refuses an unknown KYC status")
    void refusesUnknownStatus() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO kyc_records (id, user_id, status) VALUES (?, ?, 'PROBABLY_FINE')",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- user_preferences ---------------------------------------------------------------------

    @Test
    @DisplayName("refuses a currency that is not a three-letter code")
    void refusesMalformedCurrency() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_preferences (user_id, preferred_currency) VALUES (?, ?)",
                        UUID.randomUUID(),
                        "dollars"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("defaults marketing email consent to off")
    void defaultsMarketingConsentToOff() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_preferences (user_id) VALUES (?)", userId);

        // Consent is something a person gives, not something they have to withdraw.
        assertThat(jdbc.queryForObject(
                        "SELECT marketing_emails FROM user_preferences WHERE user_id = ?", Boolean.class, userId))
                .isFalse();
    }

    // --- helpers ------------------------------------------------------------------------------

    private void insertProfile(UUID userId, String phoneNumber) {
        jdbc.update("INSERT INTO user_profiles (user_id, phone_number) VALUES (?, ?)", userId, phoneNumber);
    }

    private void insertBeneficiary(UUID owner, UUID payee) {
        jdbc.update(
                "INSERT INTO beneficiaries (id, owner_user_id, beneficiary_user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(),
                owner,
                payee);
    }
}
