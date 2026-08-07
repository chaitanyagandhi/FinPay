package com.finpay.auth.repository;

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
     * Counts one more consecutive failed sign-in against a user.
     *
     * <p>A statement rather than a loaded entity, so it can be applied from a transaction that is
     * independent of the one handling the request. See {@code LoginAttemptRecorder}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = u.failedLoginAttempts + 1 where u.id = :userId")
    int incrementFailedLoginAttempts(@Param("userId") UUID userId);
}
