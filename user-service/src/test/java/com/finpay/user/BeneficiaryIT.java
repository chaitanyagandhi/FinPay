package com.finpay.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
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
import com.finpay.user.dto.AddBeneficiaryRequest;
import com.finpay.user.dto.UpdateProfileRequest;

/**
 * Saved payees, end to end against a real PostgreSQL.
 *
 * <p>The cases that matter most are the ones about somebody else's list. A payee list names real
 * people, so the tests below check not only that an owner can manage their own but that nothing a
 * caller can send reaches anybody else's - and that being refused reveals nothing about whether the
 * entry existed.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "management.server.port=" + BeneficiaryIT.MANAGEMENT_PORT
        })
@Testcontainers
class BeneficiaryIT {

    static final String MANAGEMENT_PORT = "19203";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    // --- saving -------------------------------------------------------------------------------

    @Test
    @DisplayName("saves a payee and returns it with the payee's public details")
    void savesAPayee() {
        UUID owner = givenProfile("owner-a");
        UUID payee = givenProfile("grace");

        ResponseEntity<Map> response = add(owner, payee, "Grace at work");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("nickname")).isEqualTo("Grace at work");
        assertThat(userOf(response.getBody())).containsEntry("displayName", "grace");
        assertThat(response.getBody().get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("returns a Location header pointing at the saved entry")
    void returnsLocation() {
        UUID owner = givenProfile("owner-b");
        UUID payee = givenProfile("katherine");

        ResponseEntity<Map> response = add(owner, payee, null);

        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/beneficiaries/" + response.getBody().get("id"));
    }

    @Test
    @DisplayName("accepts a payee with no nickname")
    void nicknameIsOptional() {
        UUID owner = givenProfile("owner-c");

        ResponseEntity<Map> response = add(owner, givenProfile("annie"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("nickname")).isNull();
    }

    @Test
    @DisplayName("treats a nickname of only whitespace as absent rather than refusing it")
    void blankNicknameBecomesAbsent() {
        UUID owner = givenProfile("owner-d");

        // The database refuses a blank nickname. Turning it into "no nickname" is kinder than a
        // 400 for a field the caller did not really mean to send.
        ResponseEntity<Map> response = add(owner, givenProfile("mary"), "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("nickname")).isNull();
    }

    // --- what saving refuses ------------------------------------------------------------------

    @Test
    @DisplayName("refuses an owner saving themselves")
    void refusesSelf() {
        UUID owner = givenProfile("owner-e");

        // Paying yourself nets to nothing and still has to be reconciled, so it is refused at the
        // first point anybody could ask for it.
        ResponseEntity<ApiError> response = addExpectingFailure(owner, owner, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("CANNOT_ADD_SELF");
    }

    @Test
    @DisplayName("refuses a payee who has no profile, because there would be no name to show")
    void refusesPayeeWithoutProfile() {
        UUID owner = givenProfile("owner-f");

        ResponseEntity<ApiError> response = addExpectingFailure(owner, UUID.randomUUID(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("BENEFICIARY_NOT_FOUND");
    }

    @Test
    @DisplayName("refuses the same payee saved twice")
    void refusesDuplicate() {
        UUID owner = givenProfile("owner-g");
        UUID payee = givenProfile("dorothy");
        add(owner, payee, null);

        ResponseEntity<ApiError> response = addExpectingFailure(owner, payee, "again");

        // Two identical entries would make "which one did I pay" ambiguous in the one place it
        // must not be.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("BENEFICIARY_ALREADY_SAVED");
    }

    @Test
    @DisplayName("lets two different owners save the same payee")
    void allowsSharedPayee() {
        UUID payee = givenProfile("popular");

        assertThat(add(givenProfile("owner-h"), payee, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add(givenProfile("owner-i"), payee, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("rejects a request with no payee id")
    void rejectsMissingPayeeId() {
        UUID owner = givenProfile("owner-j");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/v1/beneficiaries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("nickname", "nobody"), headersFor(owner)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- listing ------------------------------------------------------------------------------

    @Test
    @DisplayName("lists an owner's payees newest first")
    void listsNewestFirst() {
        UUID owner = givenProfile("owner-k");
        add(owner, givenProfile("first-saved"), null);
        add(owner, givenProfile("second-saved"), null);
        add(owner, givenProfile("third-saved"), null);

        List<Map<String, Object>> list = list(owner);

        assertThat(list).hasSize(3);
        assertThat(displayNamesOf(list)).containsExactly("third-saved", "second-saved", "first-saved");
    }

    @Test
    @DisplayName("lists only the caller's own payees")
    void listsOnlyOwnPayees() {
        UUID mine = givenProfile("owner-l");
        UUID theirs = givenProfile("owner-m");
        add(mine, givenProfile("my-payee"), null);
        add(theirs, givenProfile("their-payee"), null);

        assertThat(displayNamesOf(list(mine))).containsExactly("my-payee");
        assertThat(displayNamesOf(list(theirs))).containsExactly("their-payee");
    }

    @Test
    @DisplayName("returns an empty list for an owner who has saved nobody")
    void listsNothingForNewOwner() {
        assertThat(list(givenProfile("owner-n"))).isEmpty();
    }

    @Test
    @DisplayName("reveals no more about a payee than the directory would")
    void revealsNothingPersonal() {
        UUID owner = givenProfile("owner-o");
        UUID payee = UUID.randomUUID();
        patch(payee, new UpdateProfileRequest("private-person", "Real", "Name", "+441632960991", null, null, null));
        add(owner, payee, null);

        String body = restTemplate
                .exchange("/api/v1/beneficiaries", HttpMethod.GET, new HttpEntity<>(headersFor(owner)), String.class)
                .getBody();

        // Returning more here would work around the limits the search endpoint exists to impose.
        assertThat(body)
                .contains("private-person")
                .doesNotContain("+441632960991")
                .doesNotContain("Real")
                .doesNotContain("phoneNumber");
    }

    // --- removing -----------------------------------------------------------------------------

    @Test
    @DisplayName("removes a saved payee")
    void removesAPayee() {
        UUID owner = givenProfile("owner-p");
        String id =
                (String) add(owner, givenProfile("removable"), null).getBody().get("id");

        ResponseEntity<Void> response = remove(owner, UUID.fromString(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(owner)).isEmpty();
    }

    @Test
    @DisplayName("cannot remove somebody else's payee, and says only that there is no such payee")
    void cannotRemoveAnotherOwnersPayee() {
        UUID victim = givenProfile("owner-q");
        UUID attacker = givenProfile("owner-r");
        String id = (String)
                add(victim, givenProfile("victims-payee"), null).getBody().get("id");

        ResponseEntity<ApiError> response = removeExpectingFailure(attacker, UUID.fromString(id));

        // 404, not 403: a 403 would confirm the entry exists and is simply not theirs, which is a
        // slower way of enumerating other people's payee lists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // And it must still be there.
        assertThat(list(victim)).hasSize(1);
        assertThat(beneficiaryCount(victim)).isEqualTo(1);
    }

    @Test
    @DisplayName("answers a payee that never existed exactly as one belonging to somebody else")
    void unknownAndForeignAreIndistinguishable() {
        UUID victim = givenProfile("owner-s");
        UUID attacker = givenProfile("owner-t");
        String id = (String)
                add(victim, givenProfile("another-payee"), null).getBody().get("id");

        ApiError foreign = removeExpectingFailure(attacker, UUID.fromString(id)).getBody();
        ApiError missing = removeExpectingFailure(attacker, UUID.randomUUID()).getBody();

        assertThat(foreign.status()).isEqualTo(missing.status());
        assertThat(foreign.code()).isEqualTo(missing.code());
        assertThat(foreign.message()).isEqualTo(missing.message());
    }

    @Test
    @DisplayName("removing the same payee twice is a 404 the second time")
    void removingTwiceIsNotFound() {
        UUID owner = givenProfile("owner-u");
        String id = (String)
                add(owner, givenProfile("twice-removed"), null).getBody().get("id");
        remove(owner, UUID.fromString(id));

        assertThat(removeExpectingFailure(owner, UUID.fromString(id)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("removing a payee leaves their profile alone")
    void removingDoesNotDeleteThePerson() {
        UUID owner = givenProfile("owner-v");
        UUID payee = givenProfile("still-exists");
        String id = (String) add(owner, payee, null).getBody().get("id");

        remove(owner, UUID.fromString(id));

        // Unsaving somebody is not deleting them.
        assertThat(jdbc.queryForObject("select count(*) from user_profiles where user_id = ?", Integer.class, payee))
                .isEqualTo(1);
    }

    // --- identity -----------------------------------------------------------------------------

    @Test
    @DisplayName("refuses every operation without an identity from the gateway")
    void refusesWithoutIdentity() {
        assertThat(restTemplate
                        .getForEntity("/api/v1/beneficiaries", ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate
                        .postForEntity(
                                "/api/v1/beneficiaries",
                                new AddBeneficiaryRequest(UUID.randomUUID(), null),
                                ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ------------------------------------------------------------------------------

    private HttpHeaders headersFor(UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdentityHeaders.USER_ID, userId.toString());
        headers.set(IdentityHeaders.USER_ROLES, "USER");
        return headers;
    }

    /** A user who has a profile, and is therefore payable. */
    private UUID givenProfile(String displayName) {
        UUID userId = UUID.randomUUID();
        patch(userId, new UpdateProfileRequest(displayName, null, null, null, null, null, null));
        return userId;
    }

    private void patch(UUID userId, UpdateProfileRequest request) {
        restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.PATCH, new HttpEntity<>(request, headersFor(userId)), Map.class);
    }

    private ResponseEntity<Map> add(UUID owner, UUID payee, String nickname) {
        return restTemplate.exchange(
                "/api/v1/beneficiaries",
                HttpMethod.POST,
                new HttpEntity<>(new AddBeneficiaryRequest(payee, nickname), headersFor(owner)),
                Map.class);
    }

    private ResponseEntity<ApiError> addExpectingFailure(UUID owner, UUID payee, String nickname) {
        return restTemplate.exchange(
                "/api/v1/beneficiaries",
                HttpMethod.POST,
                new HttpEntity<>(new AddBeneficiaryRequest(payee, nickname), headersFor(owner)),
                ApiError.class);
    }

    private List<Map<String, Object>> list(UUID owner) {
        return restTemplate
                .exchange(
                        "/api/v1/beneficiaries",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(owner)),
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .getBody();
    }

    private ResponseEntity<Void> remove(UUID owner, UUID id) {
        return restTemplate.exchange(
                "/api/v1/beneficiaries/" + id, HttpMethod.DELETE, new HttpEntity<>(headersFor(owner)), Void.class);
    }

    private ResponseEntity<ApiError> removeExpectingFailure(UUID owner, UUID id) {
        return restTemplate.exchange(
                "/api/v1/beneficiaries/" + id, HttpMethod.DELETE, new HttpEntity<>(headersFor(owner)), ApiError.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> userOf(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("user");
    }

    @SuppressWarnings("unchecked")
    private List<String> displayNamesOf(List<Map<String, Object>> entries) {
        return entries.stream()
                .map(entry -> (Map<String, Object>) entry.get("user"))
                .map(user -> (String) user.get("displayName"))
                .toList();
    }

    private int beneficiaryCount(UUID owner) {
        return jdbc.queryForObject("select count(*) from beneficiaries where owner_user_id = ?", Integer.class, owner);
    }
}
