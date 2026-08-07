package com.finpay.platform.web.autoconfigure;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.finpay.platform.web.openapi.PlatformOpenApiCustomizer;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * Gives every service the same API description without each one restating it.
 *
 * <p>Applies only when a service has opted in by adding a springdoc starter. What is centralised is
 * the part that must not vary: the error envelope, the failure responses every endpoint can return,
 * the authentication scheme, and the fact that the documented base URL is the gateway rather than
 * the service's own address - a client that calls a service directly is bypassing the edge.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
@EnableConfigurationProperties(OpenApiProperties.class)
@ConditionalOnProperty(prefix = "finpay.openapi", name = "enabled", matchIfMissing = true)
public class PlatformOpenApiAutoConfiguration {

    /** Name clients see for the bearer scheme; referenced by secured operations. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    @ConditionalOnMissingBean
    OpenAPI finPayOpenApi(
            OpenApiProperties properties, @Value("${spring.application.name:finpay}") String serviceName) {

        Info info = new Info()
                .title(properties.getTitle() != null ? properties.getTitle() : serviceName)
                .description(properties.getDescription())
                .version(properties.getVersion())
                .license(new License().name("Not yet specified"));

        return new OpenAPI()
                .info(info)
                // The gateway is the supported entry point; service ports are an internal detail.
                .servers(List.of(new Server().url(properties.getPublicUrl()).description("API gateway")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Short-lived access token issued by the auth service. "
                                                + "Send it as: Authorization: Bearer <token>")));
    }

    @Bean
    @ConditionalOnMissingBean
    PlatformOpenApiCustomizer platformOpenApiCustomizer() {
        return new PlatformOpenApiCustomizer();
    }
}
