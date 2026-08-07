package com.finpay.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.finpay.auth.dto.ClientContext;
import com.finpay.auth.dto.LoginRequest;
import com.finpay.auth.dto.LoginResponse;
import com.finpay.auth.dto.LogoutRequest;
import com.finpay.auth.dto.RefreshRequest;
import com.finpay.auth.dto.RegistrationRequest;
import com.finpay.auth.dto.RegistrationResponse;
import com.finpay.auth.service.LoginService;
import com.finpay.auth.service.LogoutService;
import com.finpay.auth.service.RefreshTokenService;
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

    /**
     * Set by the gateway, which is the only thing that should be reaching this service. The value
     * originates with the caller and is trustworthy only because everything in front of it is
     * ours; it is recorded for the audit trail and never used to authorise anything.
     */
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** The scheme prefix on an {@code Authorization} header carrying an access token. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final LogoutService logoutService;

    public AuthController(
            RegistrationService registrationService,
            LoginService loginService,
            RefreshTokenService refreshTokenService,
            LogoutService logoutService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.logoutService = logoutService;
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

    @PostMapping("/login")
    @Operation(
            summary = "Sign in",
            description =
                    """
                    Verifies the credentials and returns a short-lived access token. Every failure -                     unknown address, wrong password, locked or disabled account - returns the same                     401 with the same message, so this endpoint cannot be used to discover which                     addresses have accounts.""")
    @ApiResponse(responseCode = "200", description = "Signed in; an access token is returned.")
    @ApiResponse(responseCode = "401", description = "The credentials were not accepted.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return loginService.login(request, clientContextOf(httpRequest));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Exchange a refresh token",
            description =
                    """
                    Returns a new access token and a new refresh token. The presented refresh token is \
                    spent by this call and will not work again - a client must replace its stored copy \
                    with the one returned here. Presenting a token that was already spent is treated as \
                    theft: the whole session is revoked, and this endpoint answers exactly as it does \
                    for a token that never existed.""")
    @ApiResponse(responseCode = "200", description = "A new token pair was issued.")
    @ApiResponse(responseCode = "401", description = "The refresh token was not accepted.")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        RefreshTokenService.RotationResult result =
                refreshTokenService.rotate(request.refreshToken(), clientContextOf(httpRequest));

        return LoginResponse.bearer(
                result.accessToken().value(),
                result.accessToken().issuedAt(),
                result.accessToken().expiresAt(),
                result.refreshToken().value(),
                result.refreshToken().expiresAt());
    }

    @PostMapping("/logout")
    @Operation(
            summary = "End a session",
            description =
                    """
                    Revokes the refresh token's family, so the session cannot be extended, and denylists \
                    the access token in the Authorization header, if one is sent, for the remainder of \
                    its short life. Always answers 204: reporting whether the token existed would make \
                    this a way of testing whether a token is genuine.""")
    @ApiResponse(responseCode = "204", description = "The session is ended, whether or not it existed.")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request, HttpServletRequest httpRequest) {
        logoutService.logout(request.refreshToken(), bearerTokenOf(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** The access token from the {@code Authorization} header, or null when none was sent. */
    private String bearerTokenOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length()) : null;
    }

    /**
     * Where the request appeared to come from.
     *
     * <p>Behind the gateway the socket address is always the gateway's, so the forwarded header is
     * the only thing carrying the caller's address. Only the first entry is taken: the rest are
     * proxies, and the whole header is client-settable on a direct connection.
     */
    private ClientContext clientContextOf(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        String ipAddress =
                forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();

        return new ClientContext(ipAddress, request.getHeader("User-Agent"));
    }
}
