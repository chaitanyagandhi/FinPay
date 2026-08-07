package com.finpay.platform.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.finpay.platform.web.testapp.TestWebApplication;

/**
 * Asserts the published API description, generated from a real running service.
 *
 * <p>The point is what a service gets <em>without asking</em>: the error envelope, the failure
 * responses every endpoint can return, and the authentication scheme. A controller here carries no
 * OpenAPI annotations at all, so anything present in the document came from the shared strategy.
 */
@SpringBootTest(classes = TestWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformOpenApiIT {

    @Autowired
    private TestRestTemplate restTemplate;

    private static DocumentContext document;

    @BeforeAll
    static void resetDocument() {
        document = null;
    }

    private DocumentContext apiDocs() {
        if (document == null) {
            String body = restTemplate.getForObject("/v3/api-docs", String.class);
            assertThat(body)
                    .as("the service should publish an OpenAPI document")
                    .isNotBlank();
            document = JsonPath.parse(body);
        }
        return document;
    }

    @Test
    @DisplayName("publishes a document describing the service")
    void publishesOpenApiDocument() {
        DocumentContext docs = apiDocs();

        assertThat(docs.read("$.openapi", String.class)).startsWith("3.");
        assertThat(docs.read("$.info.title", String.class)).isNotBlank();
        assertThat(docs.read("$.info.version", String.class)).isEqualTo("v1");
    }

    @Test
    @DisplayName("points clients at the gateway rather than the service's own address")
    void documentsTheGatewayAsTheEntryPoint() {
        // A client that calls a service directly bypasses routing, authentication and rate
        // limiting, so the service's own port is never advertised.
        assertThat(apiDocs().read("$.servers[0].url", String.class)).isEqualTo("http://localhost:8080");
    }

    @Test
    @DisplayName("describes the shared error envelope once, in components")
    void describesTheErrorEnvelope() {
        DocumentContext docs = apiDocs();

        assertThat(docs.read("$.components.schemas.ApiError", Object.class)).isNotNull();
        assertThat(docs.<java.util.Map<String, Object>>read("$.components.schemas.ApiError.properties"))
                .containsKeys("timestamp", "status", "error", "code", "message", "path", "requestId");
    }

    @Test
    @DisplayName("documents the failure responses on an endpoint that declares none itself")
    void documentsUniversalFailureResponses() {
        // ProbeController carries no @ApiResponse annotations: everything here is centralised.
        DocumentContext docs = apiDocs();

        for (String status : new String[] {"400", "401", "403", "404", "500"}) {
            assertThat(docs.read("$.paths./probe/ok.get.responses.%s.$ref".formatted(status), String.class))
                    .as("status %s should reference the shared response component", status)
                    .isEqualTo("#/components/responses/Error%s".formatted(status));
        }
    }

    @Test
    @DisplayName("resolves every shared failure response to the error envelope")
    void sharedResponsesReferenceTheEnvelope() {
        DocumentContext docs = apiDocs();

        for (String status : new String[] {"400", "401", "403", "404", "500"}) {
            assertThat(docs.read(
                            "$.components.responses.Error%s.content.application/json.schema.$ref".formatted(status),
                            String.class))
                    .isEqualTo("#/components/schemas/ApiError");
        }
    }

    @Test
    @DisplayName("documents the bearer token scheme clients will need")
    void documentsTheAuthenticationScheme() {
        DocumentContext docs = apiDocs();

        assertThat(docs.read("$.components.securitySchemes.bearerAuth.type", String.class))
                .isEqualTo("http");
        assertThat(docs.read("$.components.securitySchemes.bearerAuth.scheme", String.class))
                .isEqualTo("bearer");
        assertThat(docs.read("$.components.securitySchemes.bearerAuth.bearerFormat", String.class))
                .isEqualTo("JWT");
    }

    @Test
    @DisplayName("does not overwrite a status an endpoint documents itself")
    void keepsEndpointDeclaredResponses() {
        // The success response is generated from the handler signature, not from our defaults,
        // so it must survive the customizer.
        java.util.Map<String, Object> success = apiDocs().read("$.paths./probe/ok.get.responses.200");

        assertThat(success).isNotNull();
        assertThat(success)
                .as("a real response described inline, not a reference to a shared error component")
                .doesNotContainKey("$ref");
    }

    @Test
    @DisplayName("serves the Swagger UI")
    void servesSwaggerUi() {
        assertThat(restTemplate
                        .getForEntity("/swagger-ui/index.html", String.class)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }
}
