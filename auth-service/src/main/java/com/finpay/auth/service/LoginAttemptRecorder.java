package com.finpay.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.finpay.auth.config.LockoutProperties;
import com.finpay.auth.dto.ClientContext;
import com.finpay.auth.entity.LoginAttempt;
import com.finpay.auth.entity.LoginFailureReason;
import com.finpay.auth.repository.LoginAttemptRepository;
import com.finpay.auth.repository.UserRepository;

/**
 * Writes the record of a failed sign-in, in its own transaction.
 *
 * <p>This exists because of an ordering problem that is easy to get wrong. A failed sign-in ends
 * by throwing, which rolls the caller's transaction back - and if the audit row were written in
 * that transaction, the rollback would erase the very record the failure was supposed to leave
 * behind. Attempts would silently never be stored, the attempt history would show only successes,
 * and lockout, which counts recent failures, would never trigger.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this one independently, so
 * the record survives the failure that caused it.
 */
@Service
public class LoginAttemptRecorder {

    private final LoginAttemptRepository loginAttempts;
    private final UserRepository users;
    private final LockoutProperties lockout;

    public LoginAttemptRecorder(LoginAttemptRepository loginAttempts, UserRepository users, LockoutProperties lockout) {
        this.loginAttempts = loginAttempts;
        this.users = users;
        this.lockout = lockout;
    }

    /**
     * Records a failed attempt and, when the account exists, counts the failure against it and
     * locks the account once too many have accumulated.
     *
     * <p>The lock is applied here rather than in {@code LoginService} for the same reason the
     * attempt is: it is a write on a path that ends by throwing. A lock set in the caller's
     * transaction would be rolled back by the rejection that provoked it, and lockout would
     * silently never engage no matter how many times an attacker guessed.
     *
     * @param userId null when no account matched the address
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId, String email, LoginFailureReason reason, ClientContext client) {
        loginAttempts.save(LoginAttempt.failure(userId, email, reason, client.ipAddress(), client.userAgent()));

        if (userId == null) {
            // No account to count against. The attempt is still recorded above, because attempts
            // on addresses that do not exist are the ones most worth noticing.
            return;
        }

        // Counted and locked with one statement rather than by mutating a loaded entity: the
        // entity belongs to the transaction that is about to roll back, so its changes would be
        // discarded along with everything else.
        users.recordFailureAndLockIfNeeded(
                userId, lockout.getMaxFailedAttempts(), Instant.now().plus(lockout.getDuration()));
    }
}
