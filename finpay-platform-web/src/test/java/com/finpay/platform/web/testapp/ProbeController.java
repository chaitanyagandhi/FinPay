package com.finpay.platform.web.testapp;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.finpay.platform.web.error.FinPayException;
import com.finpay.platform.web.error.PlatformErrorCode;

/** Endpoints that fail in each of the ways the shared error handler must cope with. */
@RestController
public class ProbeController {

    /** Request body with a constraint, for exercising validation failures. */
    public record ValidatedRequest(@NotBlank(message = "must not be blank") String reference) {}

    @GetMapping("/probe/ok")
    public String ok() {
        return "ok";
    }

    @GetMapping("/probe/domain-failure")
    public String domainFailure() {
        throw new FinPayException(PlatformErrorCode.VALIDATION_FAILED, "The amount must be greater than zero.");
    }

    @GetMapping("/probe/unexpected")
    public String unexpected() {
        // Stands in for the kind of failure whose message describes internals: the handler must
        // log this text and return none of it.
        throw new IllegalStateException("could not execute statement; constraint wallet_pkey");
    }

    @PostMapping("/probe/validated")
    public String validated(@Valid @RequestBody ValidatedRequest request) {
        return request.reference();
    }
}
