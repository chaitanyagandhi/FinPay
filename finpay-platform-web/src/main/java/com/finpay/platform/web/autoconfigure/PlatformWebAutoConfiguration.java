package com.finpay.platform.web.autoconfigure;

import java.util.List;

import jakarta.servlet.Filter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.server.WebFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.finpay.platform.web.reactive.RequestIdWebFilter;
import com.finpay.platform.web.servlet.CallerIdentityArgumentResolver;
import com.finpay.platform.web.servlet.GlobalExceptionHandler;
import com.finpay.platform.web.servlet.RequestIdFilter;

/**
 * Wires the shared web behaviour into whichever stack the service runs on.
 *
 * <p>A service gets request-id propagation and the platform error envelope by depending on this
 * module, with no configuration to copy and nothing to remember. Servlet services get the servlet
 * half, the reactive gateway gets the reactive half, and each half is inert where it does not apply.
 */
@AutoConfiguration
@EnableConfigurationProperties(PlatformWebProperties.class)
public class PlatformWebAutoConfiguration {

    /** Servlet services: request id in the logging context, plus the shared error handler. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(Filter.class)
    static class ServletConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "finpay.web.request-id", name = "enabled", matchIfMissing = true)
        RequestIdFilter requestIdFilter(PlatformWebProperties properties) {
            return new RequestIdFilter(properties.getRequestId().getOrder());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "finpay.web.error-handling", name = "enabled", matchIfMissing = true)
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        /**
         * Lets any controller take a {@code CallerIdentity} parameter.
         *
         * <p>Registered as a {@link WebMvcConfigurer} rather than a bare bean: an argument
         * resolver only takes effect once it is added to the resolver list, and a resolver bean
         * nobody registered is silently ignored - the controller would simply fail to bind.
         */
        @Bean
        @ConditionalOnMissingBean(name = "callerIdentityWebMvcConfigurer")
        @ConditionalOnClass(WebMvcConfigurer.class)
        WebMvcConfigurer callerIdentityWebMvcConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new CallerIdentityArgumentResolver());
                }
            };
        }
    }

    /**
     * Reactive services: request id established at the edge and forwarded downstream.
     *
     * <p>No error handler here. The gateway renders errors through its own {@code ErrorAttributes},
     * which is the extension point Spring Cloud Gateway routes failures through.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(WebFilter.class)
    static class ReactiveConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "finpay.web.request-id", name = "enabled", matchIfMissing = true)
        RequestIdWebFilter requestIdWebFilter(PlatformWebProperties properties) {
            return new RequestIdWebFilter(properties.getRequestId().getOrder());
        }
    }
}
