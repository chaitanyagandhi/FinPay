package com.finpay.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.finpay.auth.entity.User;

/** Data access for {@link User}. Emails are always queried in their normalised, lower-cased form. */
public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /**
     * Counts one more consecutive failed sign-in and locks the account if that was the last one
     * allowed.
     *
     * <p>Counting and locking are one statement on purpose. Reading the count, deciding in Java,
     * and writing the lock back would let several concurrent failures each read the same
     * pre-threshold count and each decide not to lock - which is precisely the situation lockout
     * exists to stop, since an attacker guessing in parallel is the one who produces it. The
     * database evaluates the threshold against the value it is already updating.
     *
     * <p>A bulk statement rather than a loaded entity, so it can be applied from a transaction
     * independent of the one handling the request. See {@code LoginAttemptRecorder}.
     *
     * @param lockUntil when the lock should expire, applied only if the threshold is now reached
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update User u
               set u.failedLoginAttempts = u.failedLoginAttempts + 1,
                   u.lockedUntil = case
                       when u.failedLoginAttempts + 1 >= :threshold then :lockUntil
                       else u.lockedUntil
                   end
             where u.id = :userId
            """)
    int recordFailureAndLockIfNeeded(
            @Param("userId") UUID userId, @Param("threshold") int threshold, @Param("lockUntil") Instant lockUntil);
}
