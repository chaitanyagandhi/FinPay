package com.finpay.platform.web.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;

/**
 * Settings for the shared web behaviour.
 *
 * <p>Both features default to on: a service that adds this module wants them, and having to opt in
 * would mean a service could quietly ship without request ids or with framework error pages.
 */
@ConfigurationProperties(prefix = "finpay.web")
public class PlatformWebProperties {

    private final RequestId requestId = new RequestId();
    private final ErrorHandling errorHandling = new ErrorHandling();

    public RequestId getRequestId() {
        return requestId;
    }

    public ErrorHandling getErrorHandling() {
        return errorHandling;
    }

    public static class RequestId {

        private boolean enabled = true;

        /**
         * Filter order. Runs very early so that anything logged during a request - including
         * failures in later filters, such as authentication - already carries the id.
         */
        private int order = Ordered.HIGHEST_PRECEDENCE + 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getOrder() {
            return order;
        }

        public void setOrder(int order) {
            this.order = order;
        }
    }

    public static class ErrorHandling {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
