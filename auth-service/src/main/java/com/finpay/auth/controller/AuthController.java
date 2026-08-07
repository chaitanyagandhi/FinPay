package com.finpay.auth.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.auth.dto.RegistrationResponse;
import com.finpay.auth.service.RegistrationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public authentication endpoints.
 *
 * <p>Holds no logic: it validates the request shape, delegates, and maps the result onto HTTP.
 * Anything that decides something belongs in the service layer.
 *
 * <p>Failure responses are not declared here. Every operation inherits the platform's error
 * envelope and its standard statuses from the shared OpenAPI strategy, so only the outcomes
 * specific to this endpoint are worth stating.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, sign-in and token lifecycle")
public class AuthController {

    private final RegistrationService registrationService;

    public AuthController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Create an account",
            description =
                    """
                    Creates an account in PENDING_VERIFICATION status with the USER role and stores a \
                    BCrypt hash of the password. Registration does not sign the caller in: no token \
                    is issued and none is implied. The response never contains the password or its hash.""")
    @ApiResponse(responseCode = "201", description = "The account was created.")
    @ApiResponse(responseCode = "409", description = "The email address already has an account.")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
        RegistrationResponse response = registrationService.register(request);

        return ResponseEntity.created(
                        UriComponentsBuilder.fromPath("/api/v1/users/{id}").build(response.userId()))
                .body(response);
    }
}
