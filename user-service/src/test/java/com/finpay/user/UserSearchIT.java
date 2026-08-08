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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.finpay.platform.web.identity.IdentityHeaders;
import com.finpay.user.dto.UpdateProfileRequest;

/**
 * The payee directory.
 *
 * <p>Most of these tests are about what the endpoint refuses to reveal. A search over real people
 * is the easiest thing in a payments platform to turn into a harvesting tool, so the limits - a
 * minimum term, a prefix rather than a substring, a hard cap, a tiny result shape - are the
 * feature, not an optimisation around it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "finpay.user.search.minimum-term-length=3",
            "finpay.user.search.maximum-results=5",
            "management.server.port=" + UserSearchIT.MANAGEMENT_PORT
        })
@Testcontainers
class UserSearchIT {

    static final String MANAGEMENT_PORT = "19202";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    // --- finding people -----------------------------------------------------------------------

    @Test
    @DisplayName("finds a user by the start of their display name")
    void findsByDisplayNamePrefix() {
        UUID target = givenProfile("grace-hopper", null);

        List<Map<String, Object>> results = search("grace", UUID.randomUUID());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("userId")).isEqualTo(target.toString());
        assertThat(results.get(0).get("displayName")).isEqualTo("grace-hopper");
    }

    @Test
    @DisplayName("matches a display name regardless of case")
    void matchesCaseInsensitively() {
        givenProfile("Katherine-Johnson", null);

        assertThat(search("katherine", UUID.randomUUID())).hasSize(1);
    }

    @Test
    @DisplayName("finds a user by their full phone number")
    void findsByPhoneNumber() {
        UUID target = givenProfile("margaret", "+441632960971");

        List<Map<String, Object>> results = search("+441632960971", UUID.randomUUID());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("userId")).isEqualTo(target.toString());
    }

    // --- what it will not reveal --------------------------------------------------------------

    @Test
    @DisplayName("returns only an id, a display name and an avatar")
    void returnsNothingPersonal() {
        givenProfile("annie-easley", "+441632960972");

        String body = restTemplate
                .exchange(
                        "/api/v1/users/search?q=annie",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(UUID.randomUUID())),
                        String.class)
                .getBody();

        // A directory that hands back phone numbers and real names is a harvesting tool for
        // anyone who can open an account.
        assertThat(body)
                .contains("annie-easley")
                .doesNotContain("+441632960972")
                .doesNotContain("firstName")
                .doesNotContain("phoneNumber");
    }

    @Test
    @DisplayName("ignores a term shorter than the minimum")
    void ignoresShortTerms() {
        givenProfile("dorothy-vaughan", null);

        // Two characters would match a large share of any real directory.
        assertThat(search("do", UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("matches a prefix, never a fragment from the middle")
    void doesNotMatchSubstrings() {
        givenProfile("dorothy-vaughan", null);

        // "contains" matching would let a short fragment sweep the directory, and could not use
        // an index while doing it.
        assertThat(search("vaughan", UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("caps the number of results, so the directory cannot be walked")
    void capsResults() {
        for (int i = 0; i < 9; i++) {
            givenProfile("mass-" + i, null);
        }

        // There is no paging either: the cap is the end of what a caller can obtain.
        assertThat(search("mass", UUID.randomUUID())).hasSize(5);
    }

    @Test
    @DisplayName("answers an unmatched search with an empty list, not a 404")
    void unmatchedSearchLooksLikeAnyOther() {
        // "no such user" and "no match" must be the same answer, or a caller can test whether a
        // particular number is registered by watching which one they get.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/search?q=+441632960999",
                HttpMethod.GET,
                new HttpEntity<>(headersFor(UUID.randomUUID())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    @DisplayName("never returns the caller to themselves")
    void excludesTheCaller() {
        UUID caller = givenProfile("self-search", null);

        // Nobody needs to find themselves in a list of people to pay.
        assertThat(search("self", caller)).isEmpty();
    }

    @Test
    @DisplayName("refuses to search without an identity from the gateway")
    void refusesAnonymousSearch() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/users/search?q=grace", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ------------------------------------------------------------------------------

    private HttpHeaders headersFor(UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(IdentityHeaders.USER_ID, userId.toString());
        headers.set(IdentityHeaders.USER_ROLES, "USER");
        return headers;
    }

    private UUID givenProfile(String displayName, String phoneNumber) {
        UUID userId = UUID.randomUUID();
        restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.PATCH,
                new HttpEntity<>(
                        new UpdateProfileRequest(displayName, null, null, phoneNumber, null, null, null),
                        headersFor(userId)),
                Map.class);
        return userId;
    }

    private List<Map<String, Object>> search(String term, UUID caller) {
        return restTemplate
                .exchange(
                        "/api/v1/users/search?q={q}",
                        HttpMethod.GET,
                        new HttpEntity<>(headersFor(caller)),
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {},
                        term)
                .getBody();
    }
}
