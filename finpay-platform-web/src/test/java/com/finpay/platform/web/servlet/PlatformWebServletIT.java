package com.finpay.platform.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.finpay.platform.web.RequestCorrelation;
import com.finpay.platform.web.error.ApiError;
import com.finpay.platform.web.error.PlatformErrorCode;
import com.finpay.platform.web.testapp.TestWebApplication;

/**
 * Exercises the servlet half against a running application: what a caller receives, and what it must
 * never receive.
 *
 * <p>Nothing here configures the filter or the error handler. A service gets both by adding the
 * dependency, and this test asserts that is true.
 */
@SpringBootTest(classes = TestWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformWebServletIT {

    @Autowired
    private TestRestTemplate restTemplate;

    // --- request id ------------------------------------------------------------------------

    @Test
    @DisplayName("returns a generated request id when the caller sent none")
    void generatesRequestIdWhenAbsent() {
        ResponseEntity<String> response = restTemplate.getForEntity("/probe/ok", String.class);

        String requestId = response.getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @Test
    @DisplayName("adopts the caller's request id so one identifier spans the whole call")
    void adoptsInboundRequestId() {
        String inbound = "req-abc-123";

        ResponseEntity<String> response = restTemplate.exchange(
                "/probe/ok", HttpMethod.GET, new HttpEntity<>(headersWithRequestId(inbound)), String.class);

        assertThat(response.getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER))
                .isEqualTo(inbound);
    }

    @Test
    @DisplayName("replaces an inbound request id that breaks the accepted format")
    void replacesUnacceptableInboundRequestId() {
        // Control characters cannot be put on the wire at all - the HTTP client rejects them - so
        // the case that actually reaches a server is a value that is transmittable but outside the
        // accepted character set or length. Character-level rejection is covered by
        // RequestCorrelationTest.
        String inbound = "req 123 with spaces";

        ResponseEntity<String> response = restTemplate.exchange(
                "/probe/ok", HttpMethod.GET, new HttpEntity<>(headersWithRequestId(inbound)), String.class);

        String returned = response.getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER);
        assertThat(returned).isNotEqualTo(inbound);
        assertThat(UUID.fromString(returned)).isNotNull();
    }

    @Test
    @DisplayName("replaces an over-long inbound request id")
    void replacesOverLongInboundRequestId() {
        String inbound = "x".repeat(RequestCorrelation.MAX_LENGTH + 1);

        ResponseEntity<String> response = restTemplate.exchange(
                "/probe/ok", HttpMethod.GET, new HttpEntity<>(headersWithRequestId(inbound)), String.class);

        assertThat(response.getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER))
                .isNotEqualTo(inbound);
    }

    // --- error envelope --------------------------------------------------------------------

    @Test
    @DisplayName("renders an anticipated failure with its own code and status")
    void rendersDomainFailure() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity("/probe/domain-failure", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.error()).isEqualTo("Bad Request");
        assertThat(body.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.message()).isEqualTo("The amount must be greater than zero.");
        assertThat(body.path()).isEqualTo("/probe/domain-failure");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("quotes the same request id in the body as in the header")
    void bodyAndHeaderCarryTheSameRequestId() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity("/probe/domain-failure", ApiError.class);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().requestId())
                .isNotBlank()
                .isEqualTo(response.getHeaders().getFirst(RequestCorrelation.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("never leaks exception text, SQL or a stack trace on an unexpected failure")
    void hidesInternalsOnUnexpectedFailure() {
        ResponseEntity<String> raw = restTemplate.getForEntity("/probe/unexpected", String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(raw.getBody())
                .contains("INTERNAL_ERROR")
                .contains("An unexpected error occurred.")
                // The thrown message and type must not reach the caller.
                .doesNotContain("constraint wallet_pkey")
                .doesNotContain("IllegalStateException")
                .doesNotContain("java.lang")
                .doesNotContain("at com.finpay");
    }

    @Test
    @DisplayName("reports validation failures with the offending field")
    void reportsValidationFailure() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/probe/validated", HttpMethod.POST, new HttpEntity<>("{\"reference\":\"\"}", headers), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).contains("reference");
    }

    @Test
    @DisplayName("reports an unparseable body without quoting it back")
    void reportsMalformedBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/probe/validated", HttpMethod.POST, new HttpEntity<>("{not json", headers), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(response.getBody().message()).doesNotContain("not json");
    }

    @Test
    @DisplayName("reports an unknown path as a not-found in the platform envelope")
    void reportsUnknownPath() {
        ResponseEntity<ApiError> response = restTemplate.getForEntity("/probe/no-such-thing", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(PlatformErrorCode.RESOURCE_NOT_FOUND.code());
        assertThat(response.getBody().requestId()).isNotBlank();
    }

    @Test
    @DisplayName("reports a wrong method as method-not-allowed")
    void reportsWrongMethod() {
        ResponseEntity<ApiError> response =
                restTemplate.exchange("/probe/ok", HttpMethod.DELETE, HttpEntity.EMPTY, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(PlatformErrorCode.METHOD_NOT_ALLOWED.code());
    }

    private HttpHeaders headersWithRequestId(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestCorrelation.REQUEST_ID_HEADER, requestId);
        return headers;
    }
}
