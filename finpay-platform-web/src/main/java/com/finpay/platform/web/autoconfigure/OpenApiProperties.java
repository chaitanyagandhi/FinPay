package com.finpay.platform.web.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Describes the API a service publishes.
 *
 * <p>Only the parts that genuinely differ per service are settable. Everything shared - the error
 * envelope, the standard failure responses, the authentication scheme - is applied identically
 * everywhere, so a client sees one API rather than eight that happen to be adjacent.
 */
@ConfigurationProperties(prefix = "finpay.openapi")
public class OpenApiProperties {

    private boolean enabled = true;

    /** Falls back to the service name when unset. */
    private String title;

    private String description = "Part of the FinPay digital wallet platform.";

    private String version = "v1";

    /** Public base URL clients should call, which is the gateway rather than the service itself. */
    private String publicUrl = "http://localhost:8080";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }
}
