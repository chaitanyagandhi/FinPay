package com.finpay.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Entry point for the FinPay config server.
 *
 * <p>Serves configuration to every other FinPay service, so shared defaults and environment-specific
 * overrides live in one place instead of being copied across eleven modules.
 *
 * <p>Configuration is read from {@code classpath:/config} through Spring Cloud Config's native
 * backend, which keeps local development self-contained: no second Git repository has to be cloned or
 * reachable for the platform to start. A production deployment points the server at a dedicated
 * configuration repository instead, which is why the backend is selected by profile rather than
 * hard-coded.
 *
 * <p>No secret is stored in the served files. Sensitive values are written as {@code ${ENV_VAR}}
 * placeholders and resolved from the environment of the service that consumes them.
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
