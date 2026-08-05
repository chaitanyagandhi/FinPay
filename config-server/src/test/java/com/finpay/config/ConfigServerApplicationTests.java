package com.finpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

/**
 * Smoke test asserting that the config-server context starts and resolves configuration from the
 * bundled native backend.
 */
@SpringBootTest
class ConfigServerApplicationTests {

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Test
    @DisplayName("resolves configuration from the bundled files rather than a Git backend")
    void contextLoads() {
        // The native backend is only registered while the "native" profile is active. If the
        // profile include in application.yml were dropped, the server would fall back to looking
        // for a Git repository, so where the configuration came from is asserted rather than
        // assumed. Checking the resolved source names keeps this independent of which repository
        // implementation Spring Cloud wraps the native backend in.
        Environment environment = environmentRepository.findOne("application", "docker", null);

        assertThat(environment.getPropertySources())
                .isNotEmpty()
                .extracting(PropertySource::getName)
                .anyMatch(name -> name.contains("application-docker"))
                .anyMatch(name -> name.contains("application.yml"));
    }
}
