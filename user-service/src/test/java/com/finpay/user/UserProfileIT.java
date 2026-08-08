package com.finpay.user;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.finpay.platform.web.error.ApiError;
import com.finpay.platform.web.identity.IdentityHeaders;
import com.finpay.user.dto.UpdateProfileRequest;

/**
 * Reading and editing a profile, end to end against a real PostgreSQL.
 *
 * <p>The caller is identified by the headers the gateway writes after validating an access token.
 * This service never sees the token, which is what the tests here reproduce - and what makes the
 * "no identity, no service" case worth asserting: a request that arrives without those headers came
 * from somewhere other than the gateway.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "management.server.port=" + UserProfileIT.MANAGEMENT_PORT
        })
@Testcontainers
class UserProfileIT {

    static final String MANAGEMENT_PORT = "19201";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // --- reading ------------------------------------------------------------------------------

    @Test
    @DisplayName("returns an empty profile for an account that has never filled one in")
    void returnsEmptyProfileForNewAccount() {
        UUID userId = UUID.randomUUID();

        ResponseEntity<Map> response = getProfile(userId);

        // 200 rather than 404: the account exists and the caller is authenticated, so "not found"
        // would answer a different question from the one asked.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("userId")).isEqualTo(userId.toString());
        assertThat(response.getBody().get("displayName")).isNull();
    }

    @Test
    @DisplayName("reading a profile writes nothing to the database")
    void readingCreatesNoRow() {
        UUID userId = UUID.randomUUID();

        getProfile(userId);

        // A safe method with a side effect would fill the table with blank rows for every account
        // that ever opened the app.
        assertThat(profileCount(userId)).isZero();
    }

    @Test
    @DisplayName("never returns an email address, which belongs to the auth service")
    void neverReturnsAnEmail() {
        UUID userId = UUID.randomUUID();
        patchProfile(userId, new UpdateProfileRequest("ada", "Ada", "Lovelace", null, null, null, null));

        String body = restTemplate
                .exchange("/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headersFor(userId)), String.class)
                .getBody();

        // Two services holding the same email is two answers to "who is this".
        assertThat(body).doesNotContain("email");
    }

    // --- writing ------------------------------------------------------------------------------

    @Test
    @DisplayName("creates the profile on first update and returns it")
    void createsProfileOnFirstUpdate() {
        UUID userId = UUID.randomUUID();

        ResponseEntity<Map> response = patchProfile(
                userId,
                new UpdateProfileRequest("ada", "Ada", "Lovelace", "+441632960961", "GB", "Europe/London", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("displayName")).isEqualTo("ada");
        assertThat(profileCount(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select phone_number from user_profiles where user_id = ?", String.class, userId))
                .isEqualTo("+441632960961");
    }

    @Test
    @DisplayName("leaves omitted fields alone, so sending one field never erases the others")
    void patchDoesNotEraseOmittedFields() {
        UUID userId = UUID.randomUUID();
        patchProfile(userId, new UpdateProfileRequest("ada", "Ada", "Lovelace", "+441632960962", "GB", null, null));

        patchProfile(userId, new UpdateProfileRequest("ada-lovelace", null, null, null, null, null, null));

        // This is the whole difference between PATCH and PUT, and getting it wrong loses data
        // belonging to whoever sent the smaller request.
        Map<String, Object> row = jdbc.queryForMap("select * from user_profiles where user_id = ?", userId);
        assertThat(row.get("display_name")).isEqualTo("ada-lovelace");
        assertThat(row.get("first_name")).isEqualTo("Ada");
        assertThat(row.get("last_name")).isEqualTo("Lovelace");
        assertThat(row.get("phone_number")).isEqualTo("+441632960962");
    }

    @Test
    @DisplayName("updates only the caller's own profile, whatever else is in flight")
    void updatesOnlyTheCaller() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        patchProfile(theirs, new UpdateProfileRequest("theirs", null, null, null, null, null, null));

        patchProfile(mine, new UpdateProfileRequest("mine", null, null, null, null, null, null));

        // There is no way to name another user in the request, which is why no authorization
        // check is needed here - the endpoint simply cannot address anybody else.
        assertThat(jdbc.queryForObject(
                        "select display_name from user_profiles where user_id = ?", String.class, theirs))
                .isEqualTo("theirs");
    }

    @Test
    @DisplayName("refuses a phone number already on another profile, without naming the holder")
    void refusesDuplicatePhoneNumber() {
        UUID first = UUID.randomUUID();
        patchProfile(first, new UpdateProfileRequest(null, null, null, "+441632960963", null, null, null));

        ResponseEntity<ApiError> response = patchExpectingFailure(
                UUID.randomUUID(), new UpdateProfileRequest(null, null, null, "+441632960963", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("PHONE_NUMBER_ALREADY_USED");
        // Echoing the number back would confirm to whoever submitted it that it belongs to a
        // FinPay account, which is what someone probing a list of numbers wants to learn.
        assertThat(response.getBody().message()).doesNotContain("+441632960963");
    }

    @Test
    @DisplayName("lets a user keep their own phone number across updates")
    void allowsKeepingOwnPhoneNumber() {
        UUID userId = UUID.randomUUID();
        patchProfile(userId, new UpdateProfileRequest(null, null, null, "+441632960964", null, null, null));

        // The uniqueness rule is about other people's numbers; resubmitting your own is not a
        // conflict, and treating it as one would break any client that sends the whole form back.
        ResponseEntity<Map> again =
                patchProfile(userId, new UpdateProfileRequest("ada", null, null, "+441632960964", null, null, null));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("rejects a phone number that is not E.164")
    void rejectsMalformedPhoneNumber() {
        ResponseEntity<ApiError> response = patchExpectingFailure(
                UUID.randomUUID(), new UpdateProfileRequest(null, null, null, "07700 900461", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("rejects a timezone the platform cannot resolve")
    void rejectsUnknownTimezone() {
        // Stored now, it becomes an exception in a scheduled job later, far from this request.
        ResponseEntity<ApiError> response = patchExpectingFailure(
                UUID.randomUUID(), new UpdateProfileRequest(null, null, null, null, null, "Mars/Olympus_Mons", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("UNKNOWN_TIMEZONE");
    }

    @Test
    @DisplayName("trims a padded value before validating it")
    void trimsBeforeValidating() {
        UUID userId = UUID.randomUUID();

        // A copy-pasted number arrives with a trailing space; validation runs after
        // deserialisation, so trimming has to happen in the record itself to be in time.
        ResponseEntity<Map> response = patchProfile(
                userId, new UpdateProfileRequest("  ada  ", null, null, " +441632960965 ", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                        "select phone_number from user_profiles where user_id = ?", String.class, userId))
                .isEqualTo("+441632960965");
    }

    // --- identity -----------------------------------------------------------------------------

    @Test
    @DisplayName("refuses a request that arrives without an identity from the gateway")
    void refusesRequestWithoutIdentity() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity("/api/v1/users/me", ApiError.class);

        // No header means the request did not come through the gateway. Guessing at who is
        // calling is the one thing this service must not do.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().code()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("refuses an identity header that is not a UUID")
    void refusesMalformedIdentity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdentityHeaders.USER_ID, "not-a-uuid");

        ResponseEntity<ApiError> response =
                restTemplate.exchange("/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ------------------------------------------------------------------------------

    private HttpHeaders headersFor(UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdentityHeaders.USER_ID, userId.toString());
        headers.set(IdentityHeaders.USER_ROLES, "USER");
        return headers;
    }

    private ResponseEntity<Map> getProfile(UUID userId) {
        return restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headersFor(userId)), Map.class);
    }

    private ResponseEntity<Map> patchProfile(UUID userId, UpdateProfileRequest request) {
        return restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH, new HttpEntity<>(request, headersFor(userId)), Map.class);
    }

    private ResponseEntity<ApiError> patchExpectingFailure(UUID userId, UpdateProfileRequest request) {
        return restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH, new HttpEntity<>(request, headersFor(userId)), ApiError.class);
    }

    private int profileCount(UUID userId) {
        return jdbc.queryForObject("select count(*) from user_profiles where user_id = ?", Integer.class, userId);
    }
}
