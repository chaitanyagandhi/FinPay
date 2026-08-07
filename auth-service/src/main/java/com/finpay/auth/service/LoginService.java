package com.finpay.auth.service;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.auth.dto.ClientContext;
import com.finpay.auth.dto.LoginRequest;
import com.finpay.auth.dto.LoginResponse;
import com.finpay.auth.entity.Credential;
import com.finpay.auth.entity.LoginAttempt;
import com.finpay.auth.entity.LoginFailureReason;
import com.finpay.auth.entity.User;
import com.finpay.auth.entity.UserStatus;
import com.finpay.auth.exception.InvalidCredentialsException;
import com.finpay.auth.repository.CredentialRepository;
import com.finpay.auth.repository.LoginAttemptRepository;
import com.finpay.auth.repository.UserRepository;

/**
 * Verifies credentials and issues an access token.
 *
 * <p>Every failure path returns the same exception with the same message. The specific reason is
 * written to the attempt history, where an operator can see it and a caller cannot.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    /**
     * A real BCrypt hash of a value nobody knows, verified against when no account exists.
     *
     * <p>Without it, a request for an unknown address returns as soon as the lookup misses, while
     * a request for a real address pays for a BCrypt comparison first. The difference is
     * measurable and turns response time into an oracle for which addresses have accounts. Doing
     * the work anyway costs one hash on a request that was going to fail regardless.
     */
    private static final String DUMMY_HASH = "$2a$12$Ph1cQGm2lNQXkQF6QpQ4EOc4bXWjPQpEXNhJmxJ5nZ0GcJgHqYlKm";

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final LoginAttemptRepository loginAttempts;
    private final LoginAttemptRecorder failureRecorder;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;

    public LoginService(
            UserRepository users,
            CredentialRepository credentials,
            LoginAttemptRepository loginAttempts,
            LoginAttemptRecorder failureRecorder,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer) {
        this.users = users;
        this.credentials = credentials;
        this.loginAttempts = loginAttempts;
        this.failureRecorder = failureRecorder;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
    }

    /**
     * Signs a user in.
     *
     * @throws InvalidCredentialsException for every failure, regardless of cause
     */
    @Transactional
    public LoginResponse login(LoginRequest request, ClientContext client) {
        String email = RegistrationService.normalise(request.email());
        Optional<User> found = users.findByEmail(email);

        if (found.isEmpty()) {
            // Compare against a fixed hash so the timing matches the case where an account does
            // exist, then fail exactly as every other path fails.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            return failed(null, email, LoginFailureReason.USER_NOT_FOUND, client);
        }

        User user = found.get();

        if (!passwordMatches(user, request.password())) {
            return failed(user.getId(), email, LoginFailureReason.BAD_PASSWORD, client);
        }

        // Status is checked only after the password has been verified. Rejecting a locked account
        // before checking the password would answer "is this address locked" to anyone who asks,
        // which is a slower way of answering "does this address exist".
        Optional<LoginFailureReason> refusal = refusalFor(user);
        if (refusal.isPresent()) {
            return failed(user.getId(), email, refusal.get(), client);
        }

        user.recordSuccessfulLogin(Instant.now());
        loginAttempts.save(LoginAttempt.success(user.getId(), email, client.ipAddress(), client.userAgent()));

        AccessTokenIssuer.AccessToken token = accessTokenIssuer.issue(user);
        log.info("Issued access token {} for user {}", token.tokenId(), user.getId());

        return LoginResponse.bearer(token.value(), token.issuedAt(), token.expiresAt());
    }

    private boolean passwordMatches(User user, String presented) {
        return credentials
                .findByUserId(user.getId())
                .map(Credential::getPasswordHash)
                // A user with no credential row cannot sign in. Comparing against the dummy hash
                // rather than returning early keeps the timing uniform here too.
                .map(hash -> passwordEncoder.matches(presented, hash))
                .orElseGet(() -> {
                    passwordEncoder.matches(presented, DUMMY_HASH);
                    return false;
                });
    }

    /** Which account states refuse a sign-in even when the password was right. */
    private Optional<LoginFailureReason> refusalFor(User user) {
        if (user.getStatus() == UserStatus.DISABLED) {
            return Optional.of(LoginFailureReason.ACCOUNT_DISABLED);
        }
        if (user.getStatus() == UserStatus.LOCKED || user.isCurrentlyLockedOut(Instant.now())) {
            return Optional.of(LoginFailureReason.ACCOUNT_LOCKED);
        }
        // PENDING_VERIFICATION may sign in. Verification gates what an account can do with money,
        // which is the wallet's concern, not whether its owner can see it at all.
        return Optional.empty();
    }

    /**
     * Records the attempt, then fails the way every other path fails.
     *
     * <p>The record is written through {@link LoginAttemptRecorder}, in a transaction of its own,
     * because the exception thrown here rolls this one back.
     */
    private LoginResponse failed(java.util.UUID userId, String email, LoginFailureReason reason, ClientContext client) {

        failureRecorder.recordFailure(userId, email, reason, client);
        // The reason is logged, never returned.
        log.info("Failed sign-in: reason={} user={}", reason, userId);

        throw new InvalidCredentialsException();
    }
}
