package com.finpay.gateway.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import com.finpay.platform.web.RequestCorrelation;
import com.finpay.platform.web.error.PlatformErrorCode;

/**
 * Renders gateway failures in the same envelope the services use.
 *
 * <p>Without this, a caller would get one error shape when a service rejects a request and a
 * different one when the gateway cannot reach that service - and the second is exactly the case
 * where a clear, quotable response matters most.
 *
 * <p>The default attributes are replaced rather than extended. Spring's version can include the
 * exception type and message, which name internal classes and downstream hosts.
 */
@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(request, options);

        HttpStatus status = resolveStatus(defaults.get("status"));
        PlatformErrorCode errorCode = errorCodeFor(status);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", errorCode.code());
        body.put("message", messageFor(errorCode));
        body.put("path", request.path());
        body.put("requestId", requestId(request));
        return body;
    }

    private HttpStatus resolveStatus(Object rawStatus) {
        if (rawStatus instanceof Integer statusCode) {
            HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private PlatformErrorCode errorCodeFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> PlatformErrorCode.RESOURCE_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> PlatformErrorCode.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case UNAUTHORIZED -> PlatformErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> PlatformErrorCode.FORBIDDEN;
            // No instance was available for the route's target service.
            case SERVICE_UNAVAILABLE, BAD_GATEWAY -> PlatformErrorCode.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT, REQUEST_TIMEOUT -> PlatformErrorCode.GATEWAY_TIMEOUT;
            default ->
                status.is4xxClientError() ? PlatformErrorCode.MALFORMED_REQUEST : PlatformErrorCode.INTERNAL_ERROR;
        };
    }

    /** Fixed text per code: the underlying exception message may name internal hosts or classes. */
    private String messageFor(PlatformErrorCode errorCode) {
        return switch (errorCode) {
            case RESOURCE_NOT_FOUND -> "No resource matches this path.";
            case METHOD_NOT_ALLOWED -> "This method is not supported for this path.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request content type is not supported.";
            case UNAUTHORIZED -> "Authentication is required.";
            case FORBIDDEN -> "This operation is not permitted.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable. Please retry.";
            case GATEWAY_TIMEOUT -> "The service did not respond in time. Please retry.";
            case MALFORMED_REQUEST -> "The request could not be processed.";
            default -> "An unexpected error occurred.";
        };
    }

    private String requestId(ServerRequest request) {
        return request.attribute(RequestCorrelation.REQUEST_ID_ATTRIBUTE)
                .map(String::valueOf)
                .orElseGet(() -> request.headers().firstHeader(RequestCorrelation.REQUEST_ID_HEADER) != null
                        ? request.headers().firstHeader(RequestCorrelation.REQUEST_ID_HEADER)
                        : RequestCorrelation.generate());
    }
}
