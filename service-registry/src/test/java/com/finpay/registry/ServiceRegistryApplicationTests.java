package com.finpay.registry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.eureka.server.EurekaServerAutoConfiguration;
import org.springframework.context.ApplicationContext;

import com.netflix.eureka.EurekaServerContext;

/**
 * Smoke test asserting that the registry starts as a standalone Eureka server.
 */
@SpringBootTest
class ServiceRegistryApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EurekaServerContext eurekaServerContext;

    @Test
    @DisplayName("starts as a Eureka server that does not register with a peer")
    void contextLoads() {
        // Asserts the server side is actually enabled rather than the application merely starting:
        // without @EnableEurekaServer this configuration is absent and the module would silently
        // become an ordinary web application.
        assertThat(applicationContext.getBeansOfType(EurekaServerAutoConfiguration.class))
                .isNotEmpty();

        // A standalone registry must not try to register with, or fetch from, itself.
        assertThat(eurekaServerContext.getServerConfig()).isNotNull();
        assertThat(applicationContext.getEnvironment().getProperty("eureka.client.register-with-eureka", Boolean.class))
                .isFalse();
        assertThat(applicationContext.getEnvironment().getProperty("eureka.client.fetch-registry", Boolean.class))
                .isFalse();
    }
}
