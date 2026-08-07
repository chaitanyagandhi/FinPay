package com.finpay.platform.web.openapi;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;

import com.finpay.platform.web.error.ApiError;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * Adds the failure half of the API description to every operation.
 *
 * <p>Left to each controller, error documentation is written once, copied twice, and then quietly
 * diverges: one endpoint documents a 500, another does not, a third invents its own error shape.
 * Applying it centrally means the published contract matches what {@code GlobalExceptionHandler}
 * actually returns, everywhere, without a single {@code @ApiResponse} annotation.
 *
 * <p>Responses are emitted as references to one shared component, so the envelope is described once
 * in the document rather than inlined on every operation.
 */
public class PlatformOpenApiCustomizer implements OpenApiCustomizer {

    static final String ERROR_SCHEMA_NAME = "ApiError";
    private static final String ERROR_RESPONSE_PREFIX = "Error";
    private static final String JSON = "application/json";

    /** Failures any endpoint can produce, and which the shared handler is guaranteed to render. */
    private static final Map<String, String> UNIVERSAL_RESPONSES = Map.of(
            "400", "The request was rejected: it failed validation or could not be parsed.",
            "401", "Authentication is required, or the supplied credentials were not accepted.",
            "403", "The caller is authenticated but not permitted to perform this operation.",
            "404", "No resource matches this path.",
            "500", "An unexpected error occurred. The response carries a requestId to quote.");

    @Override
    public void customise(OpenAPI openApi) {
        registerErrorSchema(openApi);
        registerSharedResponses(openApi);
        applyToOperations(openApi);
    }

    /** Describes {@link ApiError} once, in components, rather than inline per operation. */
    private void registerErrorSchema(OpenAPI openApi) {
        Components components = components(openApi);
        if (components.getSchemas() != null && components.getSchemas().containsKey(ERROR_SCHEMA_NAME)) {
            return;
        }
        ModelConverters.getInstance().readAll(new AnnotatedType(ApiError.class)).forEach(components::addSchemas);
    }

    private void registerSharedResponses(OpenAPI openApi) {
        Components components = components(openApi);
        Content content = new Content()
                .addMediaType(
                        JSON, new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA_NAME)));

        UNIVERSAL_RESPONSES.forEach((status, description) -> components.addResponses(
                ERROR_RESPONSE_PREFIX + status,
                new ApiResponse().description(description).content(content)));
    }

    private void applyToOperations(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations()
                .forEach(this::addErrorResponses));
    }

    private void addErrorResponses(Operation operation) {
        ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();

        // Ordered so the generated document reads consistently across services.
        new LinkedHashMap<>(UNIVERSAL_RESPONSES).keySet().stream().sorted().forEach(status -> {
            // An endpoint that documents a status itself knows better than this default.
            if (!responses.containsKey(status)) {
                responses.addApiResponse(
                        status, new ApiResponse().$ref("#/components/responses/" + ERROR_RESPONSE_PREFIX + status));
            }
        });

        operation.setResponses(responses);
    }

    private Components components(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        return openApi.getComponents();
    }
}
